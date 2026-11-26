# bob-reviewer

Spring Boot service that reviews GitHub pull requests with an LLM.

It receives GitHub `pull_request` webhooks, fetches the PR diff from the GitHub API, asks a model through OpenRouter (Spring AI) for a review, and stores the result in Postgres. A separate `GET /ai` endpoint proxies ad-hoc prompts.

## Stack

- Kotlin 2.4.21, Java 25 toolchain
- Spring Boot 4.2.0, Spring AI 2.1.0-M1
- Postgres + Liquibase (H2 in tests)
- Jackson 3 (`tools.jackson.databind.ObjectMapper`)

## Flow

```
GitHub ──POST /webhook──> WebhookController
                             │ verifies X-Hub-Signature-256 (when GITHUB_WEBHOOK_SECRET is set)
                             ▼
                         WebhookService ──> github_webhook_event (payload, dedupe by delivery id)
                             │ pull_request opened/reopened/synchronize, non-draft
                             ▼
                         applicationTaskExecutor (202 returned immediately)
                             │
                             ├─> GitHubClient: fetch diff (truncated to 30k chars)
                             └─> ChatClient (OpenRouter) ──> review text ──> github_webhook_event.comment
```

The review is **not** posted back to GitHub yet — it is only stored in the database. Posting, retries, and a stale-review re-check are planned in [PLAN.md](PLAN.md).

## Endpoints

| Method | Path | Behaviour |
|---|---|---|
| `GET` | `/ai?message=...` | Sends the prompt to the model, returns the text. Defaults to "Tell me a joke". Capped at 4,000 chars (413). Requires the `X-AI-Token` header when `AI_ACCESS_TOKEN` is set. |
| `POST` | `/webhook` | GitHub webhook receiver. `202` queued, `200` duplicate/ping, `204` ignored, `400` malformed payload, `401` bad signature. |

## Configuration

| Variable | Default | Purpose |
|---|---|---|
| `OPENROUTER_API_KEY` | — | Required. Model API key. |
| `GITHUB_WEBHOOK_SECRET` | blank | Enables `X-Hub-Signature-256` verification. Blank disables it with a startup warning. |
| `GITHUB_TOKEN` | blank | Authenticated GitHub API calls (private repos, higher rate limits). Without it, public repos only. |
| `AI_ACCESS_TOKEN` | blank | Enables `X-AI-Token` auth on `/ai`. Blank leaves it open with a startup warning. |
| `DB_URL` | `jdbc:postgresql://localhost:5432/bob_reviewer` | Datasource URL. |
| `DB_USERNAME` | `postgres` | Datasource user. |
| `DB_PASSWORD` | `password` | Datasource password. |

Model and provider settings live in `src/main/resources/application.yaml`.

## Run locally

Gradle 9 (wrapper `9.9.2`) needs Java 25; the shell default here is Java 11, so set `JAVA_HOME`:

```sh
export JAVA_HOME=/opt/homebrew/opt/openjdk@25/libexec/openjdk.jdk/Contents/Home
```

Requires Postgres and `OPENROUTER_API_KEY`; Liquibase creates the schema at startup.

```sh
./gradlew bootRun
```

Expose the port to GitHub with a tunnel (ngrok, smee.io, or `gh extension install cli/gh-webhook` + `gh webhook forward`) and point the webhook at `/webhook`. Subscribe to pull request events and set the same secret as `GITHUB_WEBHOOK_SECRET`.

## Tests

The `test` profile uses in-memory H2 in PostgreSQL mode with Liquibase, so no Postgres or API key is needed.

```sh
./gradlew test
./gradlew test --tests "com.pashtetovv.bob_reviewer.repository.WebhookEventRepositoryTest"
```

There is no lint or formatter task; `./gradlew test` is the only automated check.

## Layout

```
src/main/kotlin/com/pashtetovv/bob_reviewer/
├── controller/    ChatController (/ai), WebhookController (/webhook)
├── service/       WebhookService (verify, dedupe, queue), PullRequestReviewService (prompt + model call)
├── client/        GitHubClient (diff fetch)
├── repository/    WebhookEventRepository (JdbcClient)
└── dto/           GitHub webhook payload models
```

Webhook payloads are stored as `text`, not `jsonb`, so the same Liquibase changelog runs on H2. Schema changes go in `src/main/resources/db/changelog/changes/` and must be appended to `db.changelog-master.yaml`.

## Roadmap

See [PLAN.md](PLAN.md): scheduled re-check of broken reviews, posting reviews back to GitHub, and retry policy.
