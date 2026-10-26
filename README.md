# Policy Impact

Java/Spring Boot backend for profile-aware immigration policy retrieval and impact assessment.

## Requirements

- Java 21+
- Docker Desktop (for PostgreSQL with pgvector)
- Ollama with the `mxbai-embed-large` embedding model

Set `JAVA_HOME` to your JDK installation before using the Maven Wrapper.

## Run locally in WSL

From the cloned `CS203` directory (not the parent `Project` directory), start
PostgreSQL and install the Ollama embedding model:

```bash
docker compose up -d
docker compose ps
ollama pull mxbai-embed-large
```

Make sure the Ollama server is running. If it is not already running, use a separate
terminal and keep it open:

```bash
ollama serve
```

Start Spring Boot from the `CS203` directory:

```bash
export JWT_SECRET="$(openssl rand -base64 32)"
./mvnw spring-boot:run
```

### Authentication and JWT

The application uses short-lived JWT bearer access tokens and seven-day refresh
tokens. Access tokens include the user's role and are used for authenticated and
role-protected requests. Refresh tokens are stored as hashes, rotated after use,
and rejected after revocation or reuse. The signing secret is read from the
`JWT_SECRET` environment variable and must not be
committed to this repository.

From WSL, set the secret in the same terminal session used to start Spring Boot:

```bash
export JWT_SECRET="$(openssl rand -base64 32)"
./mvnw spring-boot:run
```

To check that the secret is set without printing it:

```bash
echo "${#JWT_SECRET}"
```

The value should be approximately 44 characters long. If you open a new WSL
terminal, set `JWT_SECRET` again before starting the application, or store it in
your local shell configuration. Never commit the secret or add the real value
to `application.yaml`.

Register a user:

```bash
curl -s -X POST http://localhost:8080/api/v1/auth/register \
  -H "Content-Type: application/json" \
  -d '{
    "userName": "Test User",
    "userEmail": "test@example.com",
    "userPassword": "Password123",
    "visaType": "F-1",
    "academicLevel": "Undergraduate",
    "programEndDate": "2028-05-31"
  }'
```

Log in and store the returned JWT:

```bash
TOKEN=$(curl -s -X POST http://localhost:8080/api/v1/auth/login \
  -H "Content-Type: application/json" \
  -d '{
    "userEmail": "test@example.com",
    "userPassword": "Password123"
  }' | python3 -c "import sys,json; print(json.load(sys.stdin)['token'])")

  echo $TOKEN
```

The response also contains `refreshToken`. Exchange it for a new access token
when the 15-minute access token expires:

```bash
curl -s -X POST http://localhost:8080/api/v1/auth/refresh \
  -H "Content-Type: application/json" \
  -d '{"refreshToken":"<refresh-token>"}'
```

Use the token to access the authenticated profile endpoint:

```bash
curl -s http://localhost:8080/api/v1/profiles/me \
  -H "Authorization: Bearer $TOKEN"
```

New email/password and WorkOS accounts start with `profileComplete = false`.
Until onboarding is saved, the account can access only profile/onboarding,
current-user, and account-deletion endpoints; everything else answers 403 "Complete your
profile before using this feature". Save the profile to finish onboarding:

```bash
curl -s -X PUT http://localhost:8080/api/v1/profiles/me \
  -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" \
  -d '{"visaType": "F-1", "academicLevel": "Undergraduate", "programEndDate": "2028-05-31"}'
```

F-1, J-1, and H-1B profiles do not
need Visa Bulletin fields. Visa Bulletin fields are optional, but if one is supplied,
immigrant category, chargeability area, and priority date must all be supplied.

Wait for `Started MyGrantApplication` before testing. Keep this terminal open while
using the API or browser interface. After code changes, stop Spring with `Ctrl+C`
and run `./mvnw spring-boot:run` again; a running JVM does not reload Java changes.

The health endpoint is available at `http://localhost:8080/actuator/health`.
Swagger UI is available at `http://localhost:8080/swagger-ui.html`; see
[Swagger UI and the API contract](#swagger-ui-and-the-api-contract).

### Import a Visa Bulletin

Sign in as an admin, then open **Admin → Visa Bulletin**. The panel shows
import health and saved cutoff entries for a publication month. It can attempt to
fetch the official page or import a saved official HTML page. Applicants and managers cannot
access the panel or its API. Every `/api/v1/ingestion` request needs an admin JWT; an
applicant's `$TOKEN` gets 403. Sign in as the [admin account](#admin-account-setup) with
`.env` loaded:

```bash
ADMIN_TOKEN=$(curl -s -X POST http://localhost:8080/api/v1/auth/login \
  -H "Content-Type: application/json" \
  -d "{\"userEmail\": \"$ADMIN_EMAIL\", \"userPassword\": \"$ADMIN_PASSWORD\"}" \
  | python3 -c "import sys,json; print(json.load(sys.stdin)['token'])")
```

Then (`YYYY-MM` is the bulletin publication month):

```bash
curl -X POST http://localhost:8080/api/v1/ingestion/visa-bulletin/2026-10 \
  -H "Authorization: Bearer $ADMIN_TOKEN"
curl http://localhost:8080/api/v1/ingestion/visa-bulletin/health \
  -H "Authorization: Bearer $ADMIN_TOKEN"
curl "http://localhost:8080/api/v1/ingestion/visa-bulletin/entries/2026-10?size=200" \
  -H "Authorization: Bearer $ADMIN_TOKEN"
```

The entries come back as a [page](#pagination), 20 at a time unless you pass `size`; a
month has about 150.

If the Department of State blocks the server download (HTTP 403), use the
**Open official page** link in the admin panel, save that page as HTML in a
normal browser, and import the saved copy. The import detects the publication
month from the page heading and rejects unrelated or incomplete HTML. This
checks content and layout; it cannot prove where an uploaded file originated.
The equivalent API request is:

```bash
curl -X POST http://localhost:8080/api/v1/ingestion/visa-bulletin/saved \
  -H "Authorization: Bearer $ADMIN_TOKEN" -H "Content-Type: text/html" \
  --data-binary @saved-bulletin.html
```

The importer saves the four family/employment cutoff charts in
`visa_bulletin_entries`. A repeated import updates the same monthly keys. If
the page is missing a chart or its layout changes, no partial chart is saved
and health becomes `FAILED`; the last successful import time remains visible.
This is immigrant preference data. The bulletin's family `F1` category is
different from an `F-1` student visa, so it is not automatically matched to
student profiles.

The myGRANT application is available at `http://localhost:8080/`. Create an account
there or sign in with an existing account. The browser stores the JWT in session storage
(it is cleared when that browser tab is closed), loads the signed-in profile and matching
policies from PostgreSQL, and sends the JWT with Federal Register import and RAG requests.
Do not use VS Code Live Server for normal testing; opening the Spring URL avoids sending
API requests to the static-file server.

### Admin account setup

The application can create or promote one admin account during startup. Add the admin
credentials to the local `.env` file:

```env
ADMIN_EMAIL=admin@example.com
ADMIN_PASSWORD=use-a-strong-local-password
```

Both values must be provided. The password is hashed before it is stored. Never commit
the real password, `.env`, or any other secret to Git.

Load the variables in WSL and restart Spring Boot:

```bash
set -a
source .env
set +a
./mvnw spring-boot:run
```

Then open `http://localhost:8080/` and sign in using `ADMIN_EMAIL` and
`ADMIN_PASSWORD`. Admin users can access the RAG inspector, import tools, and the
user-management controls. From the admin view, an admin can change another user's role
to `APPLICANT`, `MANAGER`, or `ADMIN`.

After a role change, the affected user must sign out and sign in again so that a new JWT
containing the updated role is issued. If either admin variable is omitted, admin
bootstrap is skipped.

### Device sessions and logout

Each successful login creates an independent device session with its own refresh token.
Refreshing or logging out on one device revokes only that device's refresh token;
other signed-in devices remain usable.

The API uses stateless JWT access tokens. Logging out immediately removes the
device's local tokens and revokes its refresh token, so it cannot obtain another
access token. An access token already issued before logout is not server-revoked
and remains valid only until its configured expiry (15 minutes by default). Account
deletion/deactivation is different: inactive accounts are rejected by the JWT
authentication filter on every request, across all devices.

### Using the interface

- After a student completes onboarding, use the profile icon in the top header to view
  or edit their profile. The icon is hidden for staff and incomplete student profiles.
- Open **Notifications** to see matching policy alerts, then use **Feedback** to contact
  the support team. Applicant and staff messages appear in distinct chat bubbles; staff
  can reply through the **Feedback inbox**.
- Open the **RAG inspector** to import a Federal Register document and inspect the
  resulting policy assessment. Keep PostgreSQL and the configured AI services running
  for the relevant features.

Configuration can be overridden with `DATABASE_URL`, `DATABASE_USERNAME`,
`DATABASE_PASSWORD`, `OLLAMA_BASE_URL`, and `OLLAMA_EMBEDDING_MODEL`.
`JWT_SECRET` is required and must be a Base64-encoded key of at least 32 bytes.
Copy `.env.example` to `.env` for the complete local configuration template. `.env`
is ignored by Git and must never be committed.

### Google account welcome emails

WorkOS creates the account and emits a signed `user.created` webhook. The backend
verifies that webhook and sends one welcome email through Resend. Add these values
to the local `.env` file:

```env
WORKOS_WEBHOOK_SECRET=whsec_...
RESEND_API_KEY=re_...
RESEND_FROM_EMAIL=MyGrant <onboarding@your-verified-domain.example>
```

Configure this public HTTPS endpoint in the WorkOS Dashboard and subscribe it to
the `user.created` event:

```text
https://your-public-host.example/api/v1/webhooks/workos
```

WorkOS cannot deliver webhooks to `localhost`. For local testing, expose port 8080
with a temporary HTTPS tunnel such as ngrok and use the generated HTTPS URL in the
WorkOS Dashboard. The Resend sender must be a verified sending domain or address.

### Google account welcome emails

WorkOS creates the account and emits a signed `user.created` webhook. The backend
verifies that webhook and sends one welcome email through Resend. Add these values
to the local `.env` file:

```env
WORKOS_WEBHOOK_SECRET=whsec_...
RESEND_API_KEY=re_...
RESEND_FROM_EMAIL=MyGrant <onboarding@your-verified-domain.example>
```

Configure this public HTTPS endpoint in the WorkOS Dashboard and subscribe it to
the `user.created` event:

```text
https://your-public-host.example/api/v1/webhooks/workos
```

WorkOS cannot deliver webhooks to `localhost`. For local testing, expose port 8080
with a temporary HTTPS tunnel such as ngrok and use the generated HTTPS URL in the
WorkOS Dashboard. The Resend sender must be a verified sending domain or address.

## Architecture

The Java code follows Maven's standard source layout. Feature packages live under
`src/main/java/mygrant` alongside the authentication, user, and shared application
infrastructure packages. Tests mirror the same package layout under `src/test/java/mygrant`.

Spring AI creates the `policy_chunks` vector table on first startup. Schema-name
validation is intentionally disabled during automatic initialization because validation
requires the table to exist before it can be created.
The vector dimension is `1024`, matching the configured `mxbai-embed-large` model.

The implemented RAG path is:

```text
POST /api/v1/ingestion/documents
  -> save the original policy in policy_documents
  -> split the policy into chunks
  -> create Ollama embeddings
  -> store chunks and citation metadata in pgvector

POST /api/v1/rag/query
  -> embed the question
  -> retrieve relevant chunks filtered by visa type
  -> rank primary/effective evidence first
  -> generate a grounded OpenAI GPT-6.1 Sol answer
  -> return citations, confidence and review status
```

## API

Every API endpoint is under `/api/v1`. Only the browser UI (`/`, `/mygrant-prototype.html`),
Swagger UI (`/swagger-ui.html`, `/v3/api-docs`) and the health probe (`/actuator/health`) are
outside it.

### Breaking changes in v1

- **Unversioned routes removed.** `/policies`, `/ingestion/**` and `/rag/**` moved to
  `/api/v1/policies`, `/api/v1/ingestion/**` and `/api/v1/rag/**`. There are no aliases: the old
  paths return 404, like any unknown route. Authentication and roles did not change; every
  ingestion endpoint is still ADMIN only.
- **List endpoints return a page, not an array.** Read the items from `content`; see
  [Pagination](#pagination). This affects `GET /api/v1/policies`,
  `/api/v1/profiles/me/documents`, `/api/v1/feedback/messages`,
  `/api/v1/feedback/admin/conversations`, `/api/v1/feedback/admin/conversations/{id}/messages`,
  `/api/v1/admin/users`, `/api/v1/ingestion/status` and
  `/api/v1/ingestion/visa-bulletin/entries/{month}`. `/api/v1/notifications` was already a page.
- **Every error has the same body.** See [Errors](#errors). Responses that used to differ: 401
  (empty body), 403 and every Spring error such as an unknown route, wrong method or missing
  parameter (Spring Boot's `{timestamp, status, error, path}` with no message), the RAG 503
  (`{message}` only), and the WorkOS webhook's 400 and 500 (empty body).
- **CORS is configured only in `SecurityConfig`.** Browsers may call the API from
  `http://localhost:*` and `http://127.0.0.1:*`; other origins get 403. The
  `@CrossOrigin("*")` on the auth endpoints is gone. It never took effect, because the security
  filter refused other origins first, so no working caller changes.

### Errors

Every error from `/api/v1` is JSON with this shape, whatever the request's `Accept` header:

```json
{
  "timestamp": "2026-10-25T14:03:12.481Z",
  "status": 400,
  "error": "Bad Request",
  "message": "Request validation failed",
  "validationErrors": {"question": "must not be blank"}
}
```

`validationErrors` maps field names to problems for request validation and is empty
otherwise. `message` is safe to show to users. An unexpected server error answers 500 with a
generic message and is logged in full on the server; responses never contain stack traces.
The unsubscribe page behind alert emails is the one exception: it is an HTML page for people.

### Pagination

Every endpoint that returns a list returns a Spring Data page:

```json
{
  "content": [ ... ],
  "totalElements": 42,
  "totalPages": 3,
  "number": 0,
  "size": 20,
  "first": true,
  "last": false,
  ...
}
```

Pass `page` (from 0) and `size` (default 20, at most 2000). Each list keeps the order it always
had. `sort=property,asc|desc` reorders `/api/v1/policies` (by `id` otherwise); the other lists
have a fixed order, such as newest first, and `sort` only orders items that tie on it.
`ingestion/status` and `visa-bulletin/entries` are built in memory and ignore `sort`. An
unknown sort property is a 400.

### Swagger UI and the API contract

Open `http://localhost:8080/swagger-ui.html` while the app runs. Endpoints are grouped by
feature (Authentication, Profile, Policies, Notifications, Ingestion, Admin and so on). Each
one shows its summary, request and response schemas, whether it needs a JWT (the lock icon)
and which role, and its error responses with the `ApiError` body. To try a protected
endpoint, call `POST /api/v1/auth/login`, choose **Authorize** and paste the `token`. The
raw document is at `http://localhost:8080/v3/api-docs`.

The document is generated from the controller annotations. `OpenApiConfig` adds the 401
and 403 responses and the role notes from `@PreAuthorize`, so a controller declares only its
own errors. Mark a public endpoint with an empty `@SecurityRequirements`.

`OpenApiContractTest` checks the document on every pull request (the **Check API contract**
step) and fails if:

- the document is not valid OpenAPI,
- a path is outside `/api/v1`,
- an operation lacks a summary, a known tag, a success response or the `ApiError` body on
  its errors, or
- an operation's documented token or role requirement differs from what the app does.

Run it locally with `./mvnw test -Dtest=OpenApiContractTest`; it writes the document to
`target/openapi.json`.

Ingest a recent policy document (ADMIN only):

```http
POST /api/v1/ingestion/documents
Authorization: Bearer <admin token>
Content-Type: application/json

{
  "externalId": "MYGRANT-MANUAL-EXAMPLE",
  "title": "Manual policy example",
  "agency": "Example agency",
  "visaType": "F-1",
  "status": "FINAL",
  "sourceType": "PRIMARY",
  "publicationDate": "2026-07-17",
  "effectiveDate": "2026-09-15",
  "sourceUrl": "https://example.com/manual-policy",
  "content": "Replace this text with the source policy before using it for RAG."
}
```

Query the indexed evidence:

```http
POST /api/v1/rag/query
Authorization: Bearer <token>
Content-Type: application/json

{
  "question": "I am an F-1 PhD student in a five-year program. How could the new rule affect me?",
  "visaType": "F-1"
}
```

Read stored policy records with `GET /api/v1/policies` or `GET /api/v1/policies/{id}`.

## Source connector contract

Source collectors implement `DataConnector<Request, Fetched, Transformed, Result>`
in `mygrant.ingestion.connector`. The contract separates fetching, transforming,
deduplicated persistence, and health. `SourceIdentity` gives every connector a
stable source ID and `PRIMARY` or `SECONDARY` type. `ConnectorFetch` records the
source URL, retrieval time, and SHA-256 content hash; `ConnectorHealth` reports the last attempt,
success, and error. A failed import throws rather than silently storing data.

`FederalRegisterClient` remains the HTTP adapter; `FederalRegisterIngestionService`
implements `DataConnector` because it owns classification and persistence. It
reuses the existing visa classifier and `PolicyIngestionService` for filtering
and content-hash deduplication. Visa Bulletin reuses its parser and upsert store,
and tracks the SHA-256 hash of each month's imported HTML so an unchanged page
does not rewrite its entries. Its persisted health record includes the failure stage.
Their HTTP endpoints and source-specific responses remain unchanged. The USCIS
saved-result and news RSS connectors use the same stages; news reports `SECONDARY`.
Admins can inspect Federal Register's in-memory import health at
`GET /api/v1/ingestion/federal-register/health`; it resets when the app restarts.
`GET /api/v1/ingestion/status` returns health for every installed connector to admins.

### USCIS processing-time observations

The admin's existing “USCIS processing-time data” row in the prototype is a fixed
demo value. The actual connector accepts a saved, admin-reviewed result at
`POST /api/v1/ingestion/uscis-processing-times/saved` and reports persisted health
at `GET /api/v1/ingestion/uscis-processing-times/health` and the shared status route.
Use the JSON shape in `src/test/resources/uscis/saved-i765-result.json`: provide the
official `https://egov.uscis.gov/processing-times/` URL, the date the result was
observed, the selected form category, and copied result-card text containing the
form, office, and “80% of cases are completed within …” figure. The form and office
are extracted from that text; the category is supplied by the reviewing admin.
The checked-in fixture is a representative transcription for offline tests, not a
downloaded official response.

The public current figure is an **80th percentile over completed cases in the
previous six months**, not a median or a prediction for an individual case.
`processing_time_snapshots` stores its number and original `DAYS` or `MONTHS` unit;
`median_value` remains `NULL`. Its observation date, official URL, copied excerpt,
content hash, and import time preserve provenance. A repeat capture returns the existing row;
a different saved result on the same date adds a new row instead of changing
history. For CS203-89/105, treat missing median as missing,
do not turn months into exact days without an explicit conversion rule, and do not
combine this office-specific 80th percentile with USCIS's separate national,
fiscal-year median series as if they were the same measure.

The official processing-time page returned HTTP 403 during implementation and
USCIS's public developer catalog did not list a processing-time API. Therefore
this connector does not schedule live downloads or claim its saved observations
were fetched automatically. An admin must verify the copied result and category
against USCIS before importing it. The shared health endpoint describes imports,
not the freshness of a live feed; scheduling and health UI belong to CS203-78.

### Secondary news RSS

Admins can pull the selected [Guardian US immigration RSS feed](https://www.theguardian.com/us-news/usimmigration/rss)
with `POST /api/v1/ingestion/news-rss/pull`, or import saved RSS XML with
`POST /api/v1/ingestion/news-rss/saved` (`Content-Type: application/xml`).
`GET /api/v1/ingestion/news-rss/health` and the shared `/status` route show its
last success or fetch, parse, or persistence failure. The pull has five-second
connection and ten-second response timeouts; HTTP 429 fails only this import.
There is no scheduled pull in this story.

The connector accepts only Guardian article URLs and selects articles whose
title or summary matches the existing F-1, J-1, or H-1B classifier. It stores
the RSS **summary**, URL, publisher, and publication date in `policy_documents`
with `source_type=SECONDARY` and status `UNKNOWN`; the summary is not the full
article or an official policy statement. Canonical article URLs give repeat
imports a stable ID, and the shared content-hash path prevents reindexing.
Changed summaries at an existing URL are left as duplicates rather than
rewriting previously indexed evidence. Secondary documents remain searchable,
but do not send official policy-change notifications, and the evidence ranker
keeps primary sources above secondary ones. The saved XML fixture is a
representative RSS sample for offline tests; it contains synthetic article text.

## Import and classify a Federal Register document

The Federal Register API does not require an API key. The import endpoint downloads a
document's metadata and complete raw text, detects supported visa categories from policy
language, and passes the result through the normal PostgreSQL and pgvector ingestion path.
The complete source text is retained in PostgreSQL. To keep the first demo's embedding
job manageable, only its opening 6,000 characters (including the rule summary) are
indexed in pgvector; RAG cannot answer questions about sections outside that excerpt.

Restart Spring Boot after pulling new code, then import a document by its Federal Register
document number:

```bash
curl -s -X POST \
  http://localhost:8080/api/v1/ingestion/federal-register/2026-14439 \
  -H "Authorization: Bearer $ADMIN_TOKEN" | jq
```

The response includes classification flags and the signals that caused each match:

```json
{
  "documentNumber": "2026-14439",
  "affectsF1": true,
  "affectsJ1": true,
  "affectsH1b": false,
  "detectedVisaTypes": ["F-1", "J-1"],
  "matchedSignals": ["F-1:academic student", "J-1:exchange visitor"],
  "ingestion": { "chunksIndexed": 6, "alreadyIndexed": false },
  "impactAssessment": {
    "urgency": "HIGH",
    "confidence": 92,
    "summary": "The rule may affect the user's upcoming travel.",
    "reasons": ["The profile contains upcoming travel near the effective date."],
    "recommendedActions": ["Review the primary source and contact the school adviser."],
    "requiresHumanReview": true,
    "provider": "OpenAI",
    "model": "gpt-6.1-sol",
    "evaluation": { "verdict": "YES", "reason": "The assessment is supported.", "model": "claude-haiku-5-5" },
    "attempts": 1
  }
}
```

### Enable profile-specific urgency classification

Add OpenAI and Anthropic API keys only to the ignored `.env` file:

```env
OPENAI_API_KEY=...
OPENAI_MODEL=gpt-6.1-sol
ANTHROPIC_API_KEY=...
ANTHROPIC_JUDGE_MODEL=claude-haiku-5-5
```

Restart Spring Boot after changing `.env`. During each authenticated Federal Register
import, the backend sends only relevant profile fields (not the user's name or email),
policy metadata, deterministic visa matches, and a bounded source excerpt to OpenAI GPT-6.1 Sol. OpenAI
returns strict structured JSON with `HIGH`, `MEDIUM`, `LOW`, or `NOT_APPLICABLE` urgency,
confidence, reasons, actions, and a human-review flag. The RAG Inspector displays that
assessment immediately above the imported source. Claude Haiku 5.5 independently judges
the generated assessment using the Anthropic API.

Claude Haiku 5.5 independently checks the generated urgency and recommended next steps against the
same profile and policy excerpt. A `YES` verdict returns the assessment. A `NO` returns a
safe human-review response without retrying either model. If Claude is unavailable, the
single OpenAI assessment is returned with an `UNAVAILABLE` judge result, a clear
"not independently double-checked" notice, and a link to contact the myGRANT team.
If OpenAI is unavailable, Claude produces one fallback assessment with the same notice;
it is never treated as its own independent approval. A successful judge verdict is not proof of legal accuracy.
High-urgency results always require human review. Confidence is model-reported and is not
a calibrated probability. This review is based on the first 12,000 characters, not the
whole document; the deterministic notification rules remain separate. Both prompts use
the stated visa type and instruct the models not to assume that an I-140 filing means a
different current immigration status. The judge can still make mistakes; consult the
official source and a qualified adviser when appropriate.

To inspect the judge result in the terminal, get `ADMIN_TOKEN` as in
[Import a Visa Bulletin](#import-a-visa-bulletin) and run:

```bash
curl -s -X POST http://localhost:8080/api/v1/ingestion/federal-register/2026-14439 \
  -H "Authorization: Bearer $ADMIN_TOKEN" | jq '.impactAssessment'
```

Check `evaluation.verdict` (`YES`, `NO`, or `UNAVAILABLE`), `evaluation.reason`, and
`attempts`. Re-imports reuse vectors but run a fresh profile assessment and judgment.

If both providers are unavailable, ingestion and pgvector indexing still complete and the
response uses `UNAVAILABLE` rather than fabricating an assessment. In a single-provider
failure, the working provider returns one unverified assessment instead of starting a retry loop.
Spring Boot logs only the error type or HTTP status; it does not log API keys, policy text,
profile details, or provider responses. The displayed `attempts` is always `1`.

To run the focused generator and judge tests in WSL:

```bash
./mvnw '-Dtest=ImpactJudgeServiceTests,ProfileImpactAssessmentServiceTests' test
```

These tests mock OpenAI and Claude; they do not verify live API credentials, model access, or model quality.

The chunk count can vary. Importing the same unchanged document again returns
`"alreadyIndexed":true` and `"chunksIndexed":0`; this means the existing vectors were
reused, not that no vectors exist.

Confirm the metadata was stored:

```bash
curl -s http://localhost:8080/api/v1/policies \
  -H "Authorization: Bearer $TOKEN" | jq '.content[] | {id, externalId, title}'
```

Then confirm that its vector chunks are retrievable for F-1:

```bash
curl -s -X POST http://localhost:8080/api/v1/rag/query \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
    "question": "What does the fixed period of admission policy change for F-1 students?",
    "visaType": "F-1"
  }' | jq
```

### How visa classification works

Classification is a deterministic keyword heuristic, **not** an LLM or RAG decision.
It checks the document title first. If the title names a supported category, only its
title matches are used; this avoids classifying a rule as H-1B merely because H-1B
appears in background comparisons. If the title has no supported signal, it scans the
full text. It reports the matched signals and the booleans `affectsF1`, `affectsJ1`,
and `affectsH1b`. Supported categories are F-1, J-1, and H-1B. An unmatched document
returns HTTP 400 and is not ingested. A match is a routing hint, **not a legal
determination**; review ambiguous policies before sending personalised alerts.

For document `2026-14439`, the title signals `academic student` and `exchange visitor`
produce F-1 and J-1 matches, while `affectsH1b` is false. The saved policy stores the
full source text; only the opening 6,000 characters are searchable in pgvector for this
demo. RAG retrieves those chunks after classification.

## Test policy change notifications

Importing a policy alerts every applicant whose visa category it affects. An alert is
additionally flagged **action required** when the policy touches profile information that
applicant has already filled in — currently the program end date, the academic level, the
upcoming travel date, and the employer or employment status.
That mapping is a deterministic keyword heuristic like the visa classifier, **not** an LLM
decision, and it is a prompt to review rather than a legal determination.

Two behaviours surprise people:

- Alerts are created **at ingestion time**. An account registered after a document was
  imported receives nothing for it.
- Importing the same document twice alerts nobody the second time; the response reports
  `"alreadyIndexed":true` and no new alert is generated.

### 1. Sign in as an applicant whose profile has dates

Use the account registered under [Authentication and JWT](#authentication-and-jwt) above.
It is F-1 with both an academic level and a program end date, which is what the
action-required flag needs: the flag only fires when the field the policy touches is
already populated. An account registered without those fields still receives the alert,
but it arrives unflagged.

Registering an email that already exists returns HTTP 400 with a generic registration
error that does not disclose whether the email is registered. Invalid JSON or dates return HTTP 400, and invalid login
credentials return HTTP 401 without revealing whether the email exists.

### 2. Import a document that affects that category

Sign in at `http://localhost:8080/` as that account, open **RAG inspector** under **Live
tools** in the sidebar, and import one of these Federal Register document numbers:

- `2025-16554` — F-1 and J-1; flags both the academic level and the program end date, so
  one alert names two fields.
- `2026-18631` — H-1B only; flags the employer and employment status for an H-1B account
  that has either saved under **Your profile**. F-1 and J-1 accounts receive nothing: the
  title names no visa type, and F-1/J-1 appear only in a footnote comparison.
- `2020-20845` — F-1 and J-1; also flags both fields.
- `2025-20932` — F-1 and J-1; an information collection notice about school forms, so the
  alert arrives with no flag.

The unread badge on the **Notifications** link updates as soon as the import succeeds.

### 3. Read the inbox

Click **Notifications** in the sidebar, or read the same data from the terminal:

```bash
curl -s http://localhost:8080/api/v1/notifications \
  -H "Authorization: Bearer $TOKEN" | jq '.content'
```

Each entry reports whether it needs attention and which profile fields it refers to:

```json
{
  "id": 7,
  "policyId": 5,
  "message": "New policy affecting F-1: ... Action needed: review your program end date.",
  "read": false,
  "actionRequired": true,
  "affectedFields": ["program end date"]
}
```

### 4. Mark an alert as read

```bash
curl -s -X POST http://localhost:8080/api/v1/notifications/7/read \
  -H "Authorization: Bearer $TOKEN" | jq
curl -s http://localhost:8080/api/v1/notifications/unread-count \
  -H "Authorization: Bearer $TOKEN"
```

Read state is stored in PostgreSQL, so it survives a reload. Each account can only read
or mark its own alerts; another account's notification ID returns HTTP 404.

### If the inbox is empty

This is usually correct behaviour rather than a fault:

- **A fresh clone has an empty inbox.** PostgreSQL runs locally per developer, so alerts
  exist only for documents imported on your own machine.
- **The account registered after the import.** Register first, then import.
- **The document was already imported.** Use a different document number.
- **The visa category does not match**, or the account is not an applicant. Only
  `APPLICANT` users are alerted.

To re-run the demo with a document you already imported, delete it and import it again.
The alerts cascade with the policy; the vector chunks are removed separately because the
`policy_chunks` table has no foreign key to `policy_documents`:

```bash
docker exec -i policy-impact-postgres psql -U policy_user -d policy_impact <<'SQL'
DELETE FROM policy_chunks WHERE metadata->>'externalId' = '2025-16554';
DELETE FROM policy_documents WHERE external_id = '2025-16554';
SQL
```

### Test clock (temporary)

The test clock replays alerts without deleting anything by hand. It changes the date the
app treats as today, and a policy counts as published only once that date reaches its
publication date:

- **Profile → Test clock** moves only your own date and re-matches only your inbox.
- **Admin → Monitored sources → Test clock** moves every applicant's date, replacing any
  date they set themselves, and re-matches every applicant's inbox.

When the date moves, each affected inbox is brought in line with it. Alerts for policies
published after the new date are withdrawn. Every official policy published by then for
the applicant's **current** visa category (F-1, J-1 or H-1B) that they have no alert for
is alerted now and pushed to the live inbox. To watch one arrive, click **Day before** next
to a policy, then **Publish day**. After you change your visa category, setting the clock
alerts you about the policies for the new category. **Back to the real date** returns to
normal and restores alerts withdrawn earlier.

The clock only replays policies that are in the database. To test a date nobody has
imported for, leave **Also import Federal Register policies** ticked when the admin sets
the date. It searches the Federal Register for rules, proposed rules and notices from
DHS, State and Labor that mention F-1, J-1 or H-1B and were published in the 30 days up
to the date. It imports up to 20 of them before updating inboxes. Documents the classifier
finds are not about a supported visa type are skipped. These imports skip the AI impact
assessment, but each still downloads the full text and embeds it with Ollama, so
expect it to take a minute. Imported policies stay in the database after the clock moves.

Dates are kept in memory, so a restart returns everyone to the real date. Alerts created
this way are real rows, so they are also emailed when email alerts are configured. Turn
the feature off with `TEST_CLOCK_ENABLED=false`, which removes the
`/api/v1/test-clock/me` and `/api/v1/admin/test-clock` endpoints and hides both panels.

## Email alerts

Alerts can also arrive by email. Each applicant sets two modes under **Email alerts** on
**Your profile**: one for critical alerts and one for everything else. Each mode is
**Email me right away**, **Include in my daily digest** or **Do not email me**. The
defaults are right away for critical alerts and the digest for the rest. In-app alerts are
never affected.

- **Critical** means the alert asks the reader to act (`actionRequired`), or impact
  assessment rated it `HIGH`. Nothing rates individual recipients yet, so today this is
  just `actionRequired`. The rule lives in `AlertBucket.of`.
- **Right away**: within about 30 seconds, one email per alert. It holds the alert's
  message, its date and a **View in myGRANT** link that opens the alert after sign-in,
  WorkOS included.
- **Daily digest**: at 08:00 America/New_York, one email listing every new alert from the
  buckets set to the digest. A user with nothing new gets no email.
- Emails never contain the impact analysis, only the alert's own message.
- Every email has **Manage email settings** and **Unsubscribe** links, plus
  `List-Unsubscribe` and `List-Unsubscribe-Post: List-Unsubscribe=One-Click` headers.
  Unsubscribing turns both modes off. Opening the link only shows a confirm button,
  because mail scanners open links; the button (or a mail client's one-click POST) does
  the change.
- Only unread alerts are emailed, and each alert goes out in at most one email.
- Only recent alerts are emailed: the last 24 hours for immediate emails and the last 48
  hours for the digest. So the first deploy, or turning emails on, does not mail anyone
  their old inbox, and a digest missed while the app was down rolls into the next one.

Endpoints: `GET`/`PUT /api/v1/profiles/me/email-preferences`, the public
`GET`/`POST /api/v1/email/unsubscribe?token=...` page, `GET /api/v1/notifications/{id}`,
and the ADMIN-only `POST /api/v1/admin/email/digest/run`.

### Configuration

| Variable | Default | Purpose |
|---|---|---|
| `RESEND_API_KEY`, `RESEND_FROM_EMAIL` | none | Shared with the welcome email. Without both, the email jobs log one warning and do nothing (this is the case in CI). |
| `APP_BASE_URL` | `http://localhost:8080` | Base of every link in an email. Use the public HTTPS address in a deployment; mail clients only offer one-click unsubscribe for HTTPS links. |
| `EMAIL_JOBS_ENABLED` | `true` | `false` runs no email jobs at all. The test suite sets this. |
| `EMAIL_DISPATCH_INTERVAL` | `30s` | How often immediate alerts are queued and due emails sent. |
| `EMAIL_DIGEST_CRON` | `0 0 8 * * *` | Spring cron for the digest: second, minute, hour, day, month, weekday. |
| `EMAIL_DIGEST_ZONE` | `America/New_York` | Zone for the cron, the digest date and the times shown in emails. |
| `EMAIL_MAX_ATTEMPTS` | `4` | Attempts per email, counting the first. |
| `EMAIL_RETRY_BACKOFF` | `1m,5m,15m` | Wait before each retry; the last value repeats. |
| `EMAIL_IMMEDIATE_LOOKBACK` | `24h` | Oldest alert emailed right away. |
| `EMAIL_DIGEST_LOOKBACK` | `48h` | Oldest alert a digest collects. |
| `EMAIL_STUCK_AFTER` | `10m` | A send claimed this long ago is treated as interrupted and retried. |
| `EMAIL_BATCH_SIZE` | `50` | Most emails one dispatch run sends. |
| `EMAIL_ALLOW_UNVERIFIED_RECIPIENTS` | `false` | Local demos only; see below. |

### Who receives email

Only verified addresses receive alerts, but there is no email-verified flag yet
(CS203-25). Until there is, an account counts as verified once it has signed in with
WorkOS (**Continue with Google**), because WorkOS rejects unverified addresses. Accounts
registered with a password are unverified, and their settings page says so. Signing in
once with **Continue with Google** using the same address links the account and verifies
it. Inactive and deleted accounts never receive email.

`EMAIL_ALLOW_UNVERIFIED_RECIPIENTS=true` also emails password accounts, for local demos.
Never enable it on a shared deployment: anyone can register with someone else's address.

### Try it locally

1. Add `RESEND_API_KEY` and `RESEND_FROM_EMAIL` to `.env`. With Resend's shared test sender
   (`onboarding@resend.dev`) and no verified domain, Resend only delivers to your own
   account's address and rejects anything else with HTTP 403. The address
   `delivered@resend.dev` always reports delivery, so it is the safest recipient for
   demos. Accounts at `@example.com` addresses would bounce.
2. Register an applicant with `delivered@resend.dev` (or your own address). Then either
   sign in with **Continue with Google**, or set `EMAIL_ALLOW_UNVERIFIED_RECIPIENTS=true`
   to use a password account.
3. Start Spring Boot and import a document as in
   [Test policy change notifications](#test-policy-change-notifications). Critical alerts
   are emailed within `EMAIL_DISPATCH_INTERVAL`; check **Emails** in the Resend dashboard.
4. Build the digest without waiting for 08:00, signed in as the
   [admin account](#admin-account-setup):

   ```bash
   ADMIN_TOKEN=$(curl -s -X POST http://localhost:8080/api/v1/auth/login \
     -H "Content-Type: application/json" \
     -d "{\"userEmail\": \"$ADMIN_EMAIL\", \"userPassword\": \"$ADMIN_PASSWORD\"}" \
     | python3 -c "import sys,json; print(json.load(sys.stdin)['token'])")
   curl -s -X POST http://localhost:8080/api/v1/admin/email/digest/run \
     -H "Authorization: Bearer $ADMIN_TOKEN" | jq
   ```

   ```json
   {"digestDate": "2026-10-24", "digestsCreated": 1, "emailsSent": 1,
    "emailsRetrying": 0, "emailsFailed": 0, "emailsSkipped": 0}
   ```

   Each user gets at most one digest per date, so a second run on the same day only
   reaches users who had none yet. To run the demo again, pass an earlier date with
   `?date=YYYY-MM-DD`. Future dates are rejected, because they would take that day's slot
   and suppress the real digest.
5. Inspect the outbox:

   ```bash
   docker exec -i policy-impact-postgres psql -U policy_user -d policy_impact -c \
     "SELECT kind, digest_date, status, attempts, last_error, sent_at FROM email_deliveries ORDER BY created_at DESC LIMIT 10;"
   ```

The automated tests use a fake provider and never call Resend. The `mygrant.email`
integration tests run against the local PostgreSQL and delete the rows they create.

### Retries and failures

- Creating an alert never calls email code. A Resend outage cannot delay or fail an
  alert, its live update in open pages, or an import.
- Each email is claimed with a conditional update that also counts the attempt. It is
  sent with no database transaction or lock held, and its outcome is recorded afterwards.
  An overlapping run finds nothing to claim.
- Rate limits (429), server errors, timeouts and `409 concurrent_idempotent_requests` are
  retried after 1, 5 and 15 minutes; after 4 attempts the email is `FAILED`. Other 4xx
  errors, such as a bad key or an unverified sender, are not retried.
  `409 invalid_idempotent_request` is logged at error level.
- The delivery's UUID is sent as Resend's `Idempotency-Key`, so a retry after a timeout
  cannot deliver twice. Resend keeps keys for 24 hours, so keep the whole retry schedule
  well inside that.
- Before its first attempt, an email may still drop alerts the user has since switched
  off. After any attempt its contents are frozen: a retry sends exactly the same alerts,
  or the whole email is skipped if the user opted out or can no longer receive email.
- A `FAILED` immediate email keeps its alert, which is then never emailed again. It does
  not fall back into the digest, because Resend may have delivered it.
- An email left `SENDING` for `EMAIL_STUCK_AFTER`, for example after a crash, is retried
  with the same key, or marked `FAILED` if it has no attempts left.
- `last_error` stores only the HTTP status and Resend's error name, never the recipient,
  the body or the key.

### Single-instance assumption

The jobs are built for one running instance. Spring's single scheduler thread runs them
one at a time. A second instance would not double-send, because claims are conditional,
digests are unique per user and date, and Resend drops repeated idempotency keys. It would
still scan the same tables on every run and log its own warnings, so add a scheduler lock
(for example ShedLock) before running several instances. All users share one digest time
and zone; there is no per-user time zone.

## Test RAG from the terminal

Open a second WSL terminal while Spring Boot is still running. `jq` formats the JSON;
remove `| jq` if it is not installed. The queries need `$TOKEN` from
[Authentication and JWT](#authentication-and-jwt), for an account that has finished
onboarding.

### 1. Check the services

```bash
curl -s http://localhost:8080/actuator/health
ollama list
docker compose ps
```

The health response should contain `"status":"UP"`; PostgreSQL should be healthy and
the Ollama embedding model should be listed.

### 2. Ask an F-1 question

```bash
curl -s -X POST http://localhost:8080/api/v1/rag/query \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
    "question": "What does the fixed period of admission rule change for F-1 students, and when is it effective?",
    "visaType": "F-1"
  }' | jq
```

Look for citation `2026-14439` and an answer about changing from duration of status to
a fixed admission period, effective September 15, 2026. `confidence: LOW` and
`requiresReview: true` are possible; they mean the answer needs human review, not that
the API failed.

### 3. Ask a J-1 question

```bash
curl -s -X POST http://localhost:8080/api/v1/rag/query \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
    "question": "Does the fixed period of admission rule also apply to J-1 exchange visitors?",
    "visaType": "J-1"
  }' | jq
```

Check for citation `2026-14439` and compare the answer with the linked source.

### 4. Verify the visa filter

Ask about this F-1/J-1 rule using an H-1B filter:

```bash
curl -s -X POST http://localhost:8080/api/v1/rag/query \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
    "question": "What does rule 2026-14439 change for F-1 students?",
    "visaType": "H-1B"
  }' | jq
```

The response must **not** cite `2026-14439`. It may cite other H-1B documents if you
have indexed them. Do not judge correctness from answer text alone: inspect `citations`,
`confidence`, `requiresReview`, and `warnings`.

## Run automated tests

```bash
./mvnw test
```

## Support chat verification and AI setup

The **Feedback** button on Notifications opens the human support chat.
Human messages are delivered to the in-app **Feedback inbox** for ADMIN and MANAGER
accounts. Staff must sign in and monitor that inbox; this does not send email or connect
an external customer service system. Use a separate browser or private window to try
both sides with existing applicant and staff accounts.

With PostgreSQL running and local `.env` configured, run:

```bash
./mvnw test -Dtest=SupportIntegrationTest,FeedbackServiceTests,RagServiceTests
node --test src/test/js/api-response.test.cjs
```

The integration tests use real PostgreSQL persistence and JWT authentication. They
check applicant-to-staff delivery, the return reply, applicant access restrictions,
and an unavailable AI returning HTTP 503 while the same JWT remains usable. Test
accounts and messages roll back. The vector service is mocked to simulate an outage.
The JavaScript tests check that HTTP 403 and 503 preserve the session, while 401
requires signing in again.

The separate RAG inspector calls `/api/v1/rag/query`. The RAG flow is:

1. Ingest policy documents using the RAG inspector (or `/api/v1/ingestion/documents`).
2. Split documents and embed their text with Ollama `mxbai-embed-large`.
3. Store embeddings and source metadata in PostgreSQL/pgvector.
4. Embed the user's question and retrieve matching chunks filtered by visa type.
5. Pass the retrieved text and question to OpenAI `gpt-6.1-sol` and return an answer
   with citations. If nothing matches, return an insufficient-evidence answer.

Start Ollama and confirm the embedding model is available with `ollama list`. If missing,
run `ollama pull mxbai-embed-large`. Import a source before
asking about it; an empty index cannot provide a grounded answer.

OpenAI is used only after evidence retrieval; sending a question without retrieved
evidence is not part of this RAG flow. Keeping the current embedding model avoids
rebuilding the existing 1024-dimensional vector index; changing the embedding model
requires re-embedding the documents and checking dimensions.
