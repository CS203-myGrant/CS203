# Failure and load (CS203-157)

How myGRANT behaves when an AI provider is slow or failing, how it limits expensive requests,
how it shows that a source has stopped updating, and how it was load tested.

## Timeouts and retries for AI calls (CS203-174)

Every call to an AI provider has a connect timeout, a read timeout and a bounded retry:

| Call | Client | Timeouts from | Retry |
| --- | --- | --- | --- |
| OpenAI chat (`OpenAiGenerationService`) | Own `RestClient` | `mygrant.ai.http.*` | `AiCallRetry` |
| Anthropic judge (`ImpactJudgeService`) | Own `RestClient` | `mygrant.ai.http.*` | `AiCallRetry` |
| OpenAI embeddings (Spring AI) | Spring AI's `OpenAiApi` | `spring.http.client.*` | `AiCallRetry`, through Spring AI's `RetryTemplate` |

### The retry rule

`AiCallRetry` retries a call only when it:

- timed out or hit another I/O failure (`ResourceAccessException`);
- was answered `429 Too Many Requests`; or
- was answered with a `5xx`.

Anything else, such as `400` or `401`, fails at once, because retrying cannot fix it. Waits grow
exponentially from `initial-backoff` up to `max-backoff`. A `Retry-After` header (seconds or an
HTTP date) is honoured: the wait is at least that long. If it asks for longer than
`max-retry-after`, the call fails at once instead, because waiting that long would hold up a
user's request or a background worker.

### How Boot's HTTP client settings and Spring AI's retry interact

Spring AI builds its OpenAI client from the application's shared `RestClient.Builder`, so the
embedding calls take their timeouts from Boot's `spring.http.client.*`. Those settings also
apply to every other client built from that builder: the Federal Register, Visa Bulletin and
USCIS importers, which had no timeout before. The chat and judge clients set their own, longer
read timeout on top, because a chat completion answers only when generation has finished.
`spring.http.client.factory: jdk` names the JDK `HttpClient`, which is what Boot detects today,
so a new dependency cannot silently switch the client.

Spring AI 1.0.9's own retry (`spring.ai.retry.*`) is not suitable here:

- it makes 10 attempts, waiting 2 s, then ×5, up to 3 minutes, so about 20 minutes in total;
- it retries only `TransientAiException`, so a timeout is never retried;
- it treats `429` as permanent unless it is listed in `on-http-codes`;
- it ignores `Retry-After`.

Its `RetryTemplate` and `ResponseErrorHandler` beans are conditional on missing beans, so
`AiResilience` defines both:

- the error handler turns `429` and `5xx` into `RetryableAiException` (carrying `Retry-After`) and
  other errors into `NonTransientAiException`;
- the template applies `AiCallRetry`.

The `spring.ai.retry.*` properties are therefore not used.

### When a call still fails

| Path | Result |
| --- | --- |
| Policy page (`POST /api/v1/policies/{id}/recommendation`) | `503` with `Retry-After: 30` and "The AI service is temporarily unavailable. Please try again later." The page shows it with **Try again**. Nothing is stored. |
| A Spring AI embedding error reaching any endpoint | The same `503`, via `GlobalExceptionHandler` |
| Policy questions and `/api/v1/rag/query` | `503`, as before |
| Background assessment after ingestion | Ingestion completes; the affected users' assessments are saved as `UNAVAILABLE` |
| Embedding a document during a scheduled sync | That document is recorded in `sync_failures` and retried on the next run; the run completes |

### Configuration

| Variable | Default | Meaning |
| --- | --- | --- |
| `HTTP_CLIENT_CONNECT_TIMEOUT` | `5s` | Shared clients, including Spring AI embeddings |
| `HTTP_CLIENT_READ_TIMEOUT` | `30s` | Shared clients, including Spring AI embeddings |
| `AI_HTTP_CONNECT_TIMEOUT` | `5s` | OpenAI chat and the Anthropic judge |
| `AI_HTTP_READ_TIMEOUT` | `90s` | OpenAI chat and the Anthropic judge |
| `AI_RETRY_MAX_ATTEMPTS` | `3` | Attempts per call, including the first |
| `AI_RETRY_INITIAL_BACKOFF` | `1s` | Wait after the first failure |
| `AI_RETRY_MULTIPLIER` | `2.0` | Growth of each further wait |
| `AI_RETRY_MAX_BACKOFF` | `8s` | Longest computed wait |
| `AI_RETRY_MAX_RETRY_AFTER` | `20s` | Longest `Retry-After` honoured; a longer one fails the call |

With the defaults, a policy page call that times out every time takes at most about
3 × 90 s + 3 s. In practice OpenAI either answers well within the timeout or returns an error
quickly.

### Known limit

`PolicyRecommendationStore.getOrGenerate` holds a database transaction (and an advisory lock on
the user and policy) while it calls the model. The timeouts above bound how long, and the rate
limit on that endpoint bounds how many at once. Moving the call out of the transaction is
follow-up work.

### Tests (CS203-174)

These tests use a local stub server, never the real APIs:

- `AiCallRetryTest`: which failures are retried, `Retry-After` handling, and giving up.
- `AiResilienceHttpTest`:
  - the chat read timeout fires and is retried;
  - `503` and `429` are retried and `400` is not;
  - Spring AI embeddings use this rule and the shared timeout.
- `PolicyRecommendationControllerTest`: the `503` responses.
- `IngestionAssessmentIntegrationTest`: a timeout is saved as `UNAVAILABLE` and ingestion completes.

## Source freshness (CS203-175)

The impact feed (`GET /api/v1/impacts`), one assessment (`GET /api/v1/impacts/{id}`, also from
`PATCH`) and the consolidated view (`GET /api/v1/impacts/consolidated`) include
`sourceFreshness`:

```json
"sourceFreshness": {
  "stale": true,
  "staleSince": "2026-11-01T08:00:00Z",
  "sources": [
    {"name": "Federal Register", "type": "FEDERAL_REGISTER", "lastSuccessAt": "2026-11-01T08:00:00Z",
     "pollIntervalSeconds": 3600, "stale": true}
  ]
}
```

- **Sources covered:** the enabled sources that produce policy documents (`FEDERAL_REGISTER`,
  `NEWS`, `RSS`). The Visa Bulletin and USCIS sources feed other pages.
- **When a source is stale:** it has never synced, or its `lastSuccessAt` is older than
  `pollIntervalSeconds` × `SOURCE_STALE_GRACE_FACTOR` (default `3`, minimum `1`). The factor
  allows for a missed run or two, from a slow provider or a redeploy, before users are warned.
- **`staleSince`:** the oldest `lastSuccessAt` among stale sources. It is `null` when nothing is
  stale, or when a stale source has never synced.
- **What the page shows:** the dashboard feed and the assessment detail page show "Some sources
  haven't updated since <date>; information may be out of date." Freshness is read once per
  request. Every item in a page carries the same value, because a Spring `Page` cannot carry
  extra top-level fields.

Tests: `SourceFreshnessServiceTest`, `ImpactFeedIntegrationTest`, and the node tests in
`impact-feed.test.cjs`.

## Rate limits (CS203-176)

`RateLimiter` (formerly `QuestionRateLimiter`) is one per-user sliding-window mechanism with a
separate allowance for each kind of AI-backed request. Over an allowance the API answers
`429 Too Many Requests` with a `Retry-After` header and a message naming what to slow down. A
rejected request does not count against the allowance. The frontend shows that message where
the request was made.

| Allowance | Endpoints | Default | Variables |
| --- | --- | --- | --- |
| `questions` | `POST /api/v1/policies/{id}/questions`, `POST /api/v1/rag/query` (shared) | 10 per minute | `QUESTION_RATE_LIMIT_ENABLED`, `QUESTION_RATE_LIMIT_MAX_REQUESTS`, `QUESTION_RATE_LIMIT_WINDOW` |
| `recommendations` | `POST /api/v1/policies/{id}/recommendation` | 30 per minute | `RECOMMENDATION_RATE_LIMIT_*` |
| `evaluations` | `POST /api/v1/evaluate` (admins) | 10 per minute | `EVALUATION_RATE_LIMIT_*` |

Opening a policy page counts against `recommendations` even when the stored result is reused,
so the limit is generous. The properties moved from `mygrant.questions.rate-limit.*` to
`mygrant.rate-limits.<allowance>.*`. The question variables kept their names.

Tests: `RateLimiterTest` (separate allowances, messages, switching off),
`PolicyRecommendationControllerTest` (the `429`), `PolicyChatControllerTest` (questions), and
the node test in `policy-recommendation.test.cjs`.
