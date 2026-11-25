# PLAN

## Foundation (needed by all three items)

A row cannot currently tell "queued", "running", "done", "ignored", or "crashed": `comment is null` means all of those at once, and `saveIfNew` stores ignored events too — so a naive "comment is null and old" query would re-review closed and draft PRs. A `status` column is the minimum that makes items 1 and 3 possible.

`src/main/resources/db/changelog/changes/002-add-review-status.sql`:

```sql
alter table github_webhook_event add column status varchar(20) not null default 'PENDING';
alter table github_webhook_event add column attempts int not null default 0;
alter table github_webhook_event add column last_attempt_at timestamp with time zone;
alter table github_webhook_event add column comment_id bigint;   -- GitHub review id
create index ix_webhook_event_status on github_webhook_event (status, last_attempt_at);
-- backfill for rows written before this changeset
update github_webhook_event set status = 'IGNORED' where event_type <> 'pull_request';
update github_webhook_event set status = 'DONE' where comment is not null;
```

Statuses: `PENDING | RUNNING | DONE | FAILED | IGNORED`. `saveIfNew` gains a status argument; the ignored-event and ignored-action paths pass `IGNORED`, the queued path passes `PENDING`.

Single claim statement (no `SELECT FOR UPDATE`, portable to H2, prevents double runs):

```sql
update github_webhook_event
   set status = 'RUNNING', attempts = attempts + 1, last_attempt_at = :now
 where delivery_id = :deliveryId
   and attempts < :maxAttempts
   and (status = 'PENDING' or (status = 'RUNNING' and last_attempt_at < :cutoff))
```

Returning `1` means "you own this run". The same predicate resolves the cron-vs-executor race: whoever claims second sees a fresh `last_attempt_at` and backs off.

Extract `service/ReviewRunner.kt` — claim → fetch diff → model call → post → persist outcome, used by both the webhook path and the scheduler. `WebhookService` keeps only `taskExecutor.execute { reviewRunner.run(...) }`.

---

## 1) Cron re-check of broken reviews

- `@EnableScheduling` on `BobReviewerApplication`.
- `service/ReviewRecoveryService.kt`:

```kotlin
@Scheduled(cron = "\${app.review.recheck-cron:0 */5 * * * *}", zone = "UTC")
fun recheck() { ... }
```

- Query: `event_type = 'pull_request' and attempts < :maxAttempts and ((status = 'PENDING' and received_at < :cutoff) or (status = 'RUNNING' and last_attempt_at < :cutoff))`, `order by received_at`, `limit 10`. Cutoff computed in Kotlin (`now - staleAfter`), **not** as a SQL interval — keeps H2 and Postgres identical.
- Re-parse `payload` from the row (it is the PR snapshot at delivery time), then `reviewRunner.run(...)`; the claim discards it if another worker got there first.
- Config: `app.review.stale-after: 20m`, `app.review.recheck-cron`.
- Default scheduler pool is 1, so firings serialize and never overlap; a long model call just delays the next tick.

Note: with `await-termination: 120s`, graceful shutdown resolves within 2 min, so the 20-minute threshold only catches hard kills (SIGKILL, OOM, host loss).

## 2) Post the review to the PR

- `GitHubClient.postReview(repoFullName, number, body): Long` — `POST /repos/{owner}/{repo}/pulls/{number}/reviews` with `{"body": "...", "event": "COMMENT"}`. Success is **200** (not 201), response carries `id` → store in `comment_id`.
- `event: "COMMENT"` (not `REQUEST_CHANGES`) — a bot should not block merges. `commit_id` omitted = latest commit.
- Same `RestClient`, but the default `Accept: application/vnd.github.v3.diff` must be overridden per-request to `application/vnd.github+json`.
- Requires `GITHUB_TOKEN` with `pull_requests: write` — currently optional. With a blank token, nothing can be posted (see decisions).
- Order: post, then persist. A row left as `RUNNING` after a crash gets retried, so a double-post is possible if GitHub succeeded and the DB write failed.

## 3) Retries

No retry engine, no `next_attempt_at`. The cron interval **is** the backoff (stale-after 20 min × 3 attempts ≈ 1 hour of coverage), and `attempts` is the only state.

- Retryable → leave/return to `RUNNING` (or `PENDING`) so the next tick picks it up: timeouts, `IOException`, GitHub/OpenRouter 5xx, 429.
- Permanent → `status = 'FAILED'` at once: 400/401/403/404/422 (bad key, no permission, missing PR, closed PR).
- `comment_id` already set → `DONE`, never re-post.
- After `maxAttempts` → `FAILED`, log with delivery id.

---

## Open decisions

1. **Post as a PR review (`event: COMMENT`, Reviews pane) or a plain timeline comment (`POST /issues/{n}/comments`)?** Plan picks review; a timeline comment is easier to `PATCH` later for one editable message per PR.
2. **Blank `GITHUB_TOKEN`:** skip posting and keep `DONE` with the text in DB plus a WARN — or fail the run? Plan picks skip.
3. **Defaults:** 20 min stale / 5 min cron / 3 attempts.
4. **Double-post protection:** accept the rare duplicate, or append a hidden `<!-- bob-reviewer:<deliveryId> -->` marker and check existing reviews before posting? Plan picks accept (the second option costs an extra API call on every retry).

## TODO

| # | Step | Touches |
|---|------|---------|
| 1 | `002` changeset + `status`/`attempts`/`last_attempt_at`/`comment_id`; `saveIfNew` takes status | `db/changelog/changes/002-*.sql`, master yaml, `WebhookEventRepository` |
| 2 | `claimForReview`, `findStale`, `markDone(comment, commentId)`, `markFailed` | `WebhookEventRepository` |
| 3 | Extract `ReviewRunner` (claim → review → persist), rewire `WebhookService` | `service/ReviewRunner.kt`, `WebhookService.kt` |
| 4 | `GitHubClient.postReview` + typed retryable/permanent errors | `client/GitHubClient.kt` |
| 5 | Persist + post in the runner; `comment_id` idempotency | `ReviewRunner` |
| 6 | `@EnableScheduling` + `ReviewRecoveryService` + `app.review.*` config | app class, new service, `application.yaml` |
| 7 | Tests: claim semantics, stale pickup, attempts exhaustion, `postReview` wire format (MockRestServiceServer), webhook happy path posts once | `WebhookEventRepositoryTest`, new `ReviewRecoveryServiceTest`, `GitHubClientTest`, `WebhookControllerTest` |
| 8 | Update `AGENTS.md` (new flow, columns, config) and run `./gradlew test` | docs |

Note for step 7: `WebhookControllerTest` mocks `PullRequestReviewService`; once the runner also calls `GitHubClient`, those tests need a `@MockitoBean GitHubClient` or posting will hit the network.

## Out of scope

Quartz, ShedLock/multi-instance locking (the claim statement already makes a duplicate run impossible, so a second instance is safe but wasteful), an outbox table, inline per-file comments, and a manual requeue endpoint.

## Risk

Re-running a review costs a model call, so a systematically failing PR can burn 3 calls before `FAILED` — capped by `attempts`.
