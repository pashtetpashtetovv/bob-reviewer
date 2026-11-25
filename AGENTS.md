# AGENTS.md

Small single-module Kotlin/Spring Boot app that proxies chat prompts to OpenRouter through Spring AI.

## Build & run

The default shell `JAVA_HOME` is Java 11, which Gradle 9 cannot run on. The project toolchain requires Java 25. Always prefix Gradle commands:

```sh
export JAVA_HOME=/opt/homebrew/opt/openjdk@25/libexec/openjdk.jdk/Contents/Home
./gradlew test
./gradlew bootRun
```

- Do not use `/usr/libexec/java_home -v 17`; on this machine it resolves to Java 11.
- Run one test: `./gradlew test --tests "com.pashtetovv.bob_reviewer.BobReviewerApplicationTests"`
- Tests pass without `OPENROUTER_API_KEY` or Postgres: the `test` profile uses in-memory H2 (`src/test/resources/application-test.yaml`) and Liquibase creates the schema there.
- Running the app requires Postgres and `OPENROUTER_API_KEY`; DB env vars are `DB_URL` (default `jdbc:postgresql://localhost:5432/bob_reviewer`), `DB_USERNAME`, `DB_PASSWORD`. Liquibase runs at startup from `src/main/resources/db/changelog/db.changelog-master.yaml`.
- `AI_ACCESS_TOKEN` enables auth on `GET /ai` via the `X-AI-Token` header; if blank, the endpoint is open with a startup warning. Messages longer than 4,000 chars get 413.
- `GITHUB_WEBHOOK_SECRET` enables webhook signature verification; if blank, verification is disabled with a startup warning. Set it to match the GitHub webhook secret when exposing the app.
- `GITHUB_TOKEN` enables authenticated GitHub API calls for PR diffs. Without it, public repos still work but are rate-limited; private repos return no diff and the review falls back to metadata only.
- There is no lint or formatter task; `./gradlew test` is the only automated check.

## Layout & conventions

- Entrypoint: `src/main/kotlin/com/pashtetovv/bob_reviewer/BobReviewerApplication.kt`.
- Root package is `com.pashtetovv.bob_reviewer` with an underscore, not `bob-reviewer`; keep it that way (the hyphen is not a valid package character).
- `GET /ai?message=...` defaults to "Tell me a joke", requires `X-AI-Token` when `AI_ACCESS_TOKEN` is set, caps messages at 4,000 chars, and logs only the message length. `POST /webhook` accepts GitHub JSON webhooks, verifies `X-Hub-Signature-256` when `GITHUB_WEBHOOK_SECRET` is set, and queues reviews for `pull_request` actions `opened`, `reopened`, and `synchronize` on non-draft PRs; it returns 202 immediately and runs the review on `applicationTaskExecutor`. Ignored events return 204, invalid signatures 401, malformed payloads 400. Signature-valid, well-formed deliveries are inserted into `github_webhook_event`; malformed ones are rejected before the insert so the same delivery ID can be retried, and duplicate `X-GitHub-Delivery` IDs return 200 without re-queuing.
- `ChatController` builds its own `ChatClient`. `WebhookController` only binds HTTP and maps `WebhookOutcome` to a status; verification, dedupe, filtering, parsing, and queueing live in `service/WebhookService.kt`. Reviews go through `service/PullRequestReviewService`, and deliveries through `repository/WebhookEventRepository` (plain `JdbcClient` insert, `DuplicateKeyException` means duplicate; `updateComment()` stores the async review text in `github_webhook_event.comment`). Webhook DTOs live in `dto/GitHubPullRequestEvent.kt`.
- `PullRequestReviewService` fetches the PR diff through `client/GitHubClient` (`Accept: application/vnd.github.v3.diff`) and caps it at 30,000 chars. If the fetch fails or returns blank, the prompt says `Diff: unavailable` instead of failing the review.
- The webhook payload is stored as `text`, not `jsonb`, so the same Liquibase changelog runs on H2 tests; switch to `jsonb` only together with a Postgres-based test setup.
- Spring Boot 4 uses Jackson 3: inject `tools.jackson.databind.ObjectMapper`, while annotations such as `@JsonProperty` remain under `com.fasterxml.jackson.annotation`.
- Model/provider settings are only in `src/main/resources/application.yaml` (OpenRouter base URL, `deepseek/deepseek-v4.1-flash-1017`). DEBUG logging for Spring AI HTTP traffic is enabled there and is very noisy.
- Versions are pinned in `build.gradle`: Kotlin 2.4.21, Spring Boot 4.2.0, Spring AI 2.1.0-M1 (BOM). Update the Spring AI BOM version when changing AI dependencies.
- Commit `gradle/wrapper/` (wrapper is not vendored elsewhere); `build/`, `.gradle/`, and `.DS_Store` are ignored.
