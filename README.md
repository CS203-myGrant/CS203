# Policy Impact

Java/Spring Boot backend for profile-aware immigration policy retrieval and impact assessment.

## Requirements

- Java 21+
- Docker Desktop (for PostgreSQL with pgvector)
- An OpenAI API key in `OPENAI_API_KEY`, used for RAG embeddings and answers

## Deployment (CS203-82)

### Recorded choices

- **Frontend stack:** Keep the existing single-file HTML/CSS/JavaScript frontend for
  this MVP. The current app is served by Spring Boot and its backend-connected
  screens already use the same-origin API. A React + TypeScript migration would add
  a separate build and deployment workflow before it is needed for the current
  scope. The architecture proposal's React recommendation remains a future option
  if the UI grows enough to benefit from component and type boundaries.
- **App hosting:** Render image-backed Docker web service. The app is a stateless
  Java 21 Spring Boot HTTP service and already serves the checked-in frontend.
  GitHub Actions builds the repository Dockerfile, publishes a commit-tagged image
  to GHCR, and asks Render to deploy that exact image. Render exposes the service
  over a public HTTPS URL.
- **Database:** Render managed PostgreSQL, with a separate database for staging
  and production. The existing Flyway migrations manage relational schema;
  `V22__enable_pgvector.sql` enables pgvector after the current V1–V21 schema,
  and Spring AI creates the
  `policy_chunks` table on startup.
- **AI services:** OpenAI provides embeddings (`text-embedding-3-small`, 1024
  dimensions) and generates RAG answers, and Anthropic provides the optional
  impact judge. The app calls these providers over HTTP, so no GPU or model
  hosting is required.

These are deployment recommendations based on the current code, not accounts
or services already provisioned. Render supports Docker builds and PostgreSQL
with pgvector ([Docker deployment](https://render.com/docs/docker),
[PostgreSQL extensions](https://render.com/docs/postgresql-extensions)).

### Build and run the container locally

```bash
cp .env.example .env
docker compose up -d postgres
docker build -t policy-impact .
docker run --rm --env-file .env \
  -e DATABASE_URL=jdbc:postgresql://host.docker.internal:5432/policy_impact \
  -e DATABASE_PASSWORD=policy_password \
  -e JWT_SECRET="$(openssl rand -base64 32)" \
  -p 8080:8080 policy-impact
```

The Compose file's fallback password is for local development only. For a
non-container Spring run, use `DATABASE_URL=jdbc:postgresql://localhost:5432/policy_impact`.

Set `OPENAI_API_KEY` in `.env`; the container reaches OpenAI over the internet, so
nothing else needs to run on the host.

### Staging and production

Create one Render image-backed web service and one managed PostgreSQL database
per environment. The workflows expect image-backed services using the GHCR image
described below; do not connect these services to the Git repository's automatic
build/deploy path. Set `SPRING_PROFILES_ACTIVE=staging` or `production` on its service
and only that environment's database credentials. Each first deployment applies
Flyway migrations at startup; pgvector must be available to the database role.
If the app database role cannot create extensions, have the database administrator
enable `vector` once before the first app deploy. Set the web service's HTTP port
to `8080` (or provide `PORT`, which the app honors).
Use separate JWT signing keys, OpenAI keys, and connector callback/webhook
configuration in the two environments. Keep database backups enabled before
applying schema changes. Never share a database between staging and production.

Set these app variables in each service's environment/secret settings:

| Variable | Required | Purpose |
| --- | --- | --- |
| `SPRING_PROFILES_ACTIVE` | Yes in hosting | `staging` or `production` |
| `DATABASE_URL` | Yes | JDBC URL for that environment's PostgreSQL database |
| `DATABASE_USERNAME` | Yes | Database login name |
| `DATABASE_PASSWORD` | Yes | Database credential; secret |
| `JWT_SECRET` | Yes | Base64-encoded signing key, at least 32 bytes; secret |
| `OPENAI_API_KEY` | Yes | OpenAI API credential for RAG embeddings and answers; secret. Staging and production refuse to start without it |
| `ANTHROPIC_API_KEY` | Required for verified AI output | Enables independent review of recommendations and policy Q&A; secret |
| `WORKOS_API_KEY` | For WorkOS sign-in | WorkOS API credential; secret |
| `WORKOS_CLIENT_ID` | For WorkOS sign-in | WorkOS client identifier |
| `WORKOS_REDIRECT_URI` | For WorkOS sign-in | Environment callback URL ending in `/api/v1/auth/workos/callback` |
| `WORKOS_WEBHOOK_SECRET` | For WorkOS webhooks | WorkOS signing secret; secret |
| `RESEND_API_KEY` | For email delivery | Resend API credential; secret |
| `RESEND_FROM_EMAIL` | For email delivery | Verified sender address |
| `APP_BASE_URL` | For email links | Public HTTPS app URL for this environment |
| `CORS_ALLOWED_ORIGINS` | No | Comma-separated frontend origins; defaults to `APP_BASE_URL` plus local development origins. Set explicitly when the frontend uses a different origin from the API |
| `OPENAI_EMBEDDING_MODEL` | No | Defaults to `text-embedding-3-small`; must support 1024-dimension vectors |
| `JWT_EXPIRATION_MS` | No | Defaults to `900000` (15 minutes) |

Configure WorkOS separately for staging and production. Register each service's
`WORKOS_REDIRECT_URI` in WorkOS and point its webhook subscription to
`https://<environment-host>/api/v1/webhooks/workos`. Keep the callback and webhook
secrets in that environment's platform secret store. Preserve the frontend at `/`;
the Docker image copies the existing `app/` directory into the runtime image.

The `.env.example` file contains variable names and blank credential fields,
not usable secrets. Keep real values in Render's environment settings, and never
commit `.env`. The local Compose PostgreSQL service is only for development.

### CI/CD and production promotion (CS203-82)

The GitHub Actions workflows implement this release path:

```text
Pull request to main -> existing Maven CI + Docker build
Merge to main        -> build/push SHA-tagged image -> deploy that image to staging
Staging              -> HTTPS health, login, profile, notification API and SSE checks
Production           -> manually dispatch promotion for a staging-passed commit SHA
Rollback             -> manually promote a previous staging-passed SHA
```

The staging workflow builds and publishes `ghcr.io/<owner>/<repository>:<commit-sha>`
to GitHub Container Registry, then asks Render to deploy that image. Configure both
Render services as image-backed services using that image name. Render must be able
to pull it: make the package public or configure a Render registry credential for
GitHub Container Registry. The deployment action targets the exact SHA image, so
production promotion reuses the image that passed staging.

Configure GitHub repository environments and values before enabling the workflows:

| Environment | Variables | Secrets |
| --- | --- | --- |
| `staging` | `RENDER_SERVICE_ID`, `APP_BASE_URL` | `RENDER_API_KEY`, `SMOKE_USER_EMAIL`, `SMOKE_USER_PASSWORD` |
| `production` | `RENDER_SERVICE_ID`, `APP_BASE_URL` | `RENDER_API_KEY`, `SMOKE_USER_EMAIL`, `SMOKE_USER_PASSWORD` |

Use the Render service ID and an HTTPS base URL for each environment. Create a
dedicated smoke-test account in each database. In GitHub repository settings,
require at least one reviewer for the `production` environment and restrict its
deployment branch to `main`. The production workflow only runs from `main`; it
accepts a full commit SHA and checks that the SHA is on `main` and has a successful
staging workflow before waiting for the environment approval. Protect `main` with
pull requests, required review, and the **Maven tests** status check from the
`PR build and test` workflow;
the staging workflow only deploys after a commit reaches that protected branch.

The frontend and API currently ship from the same Spring Boot service. The browser
app loads `app/config.js`; leave `apiBaseUrl` empty to use the current browser origin
in staging and production. Set `APP_BASE_URL` on each Render service to its public
HTTPS origin. `SecurityConfig` uses `CORS_ALLOWED_ORIGINS` when supplied; otherwise
the CORS allowlist includes `APP_BASE_URL` and local development origins. For a local
static preview or separately hosted frontend, set
`window.MYGRANT_CONFIG.apiBaseUrl` in `app/config.js` to the API origin (for example,
`http://localhost:8080` for local development). Set `CORS_ALLOWED_ORIGINS` to the
exact HTTPS frontend origin when the frontend is hosted separately. Do not use `*`
for hosted origins.

The staging workflow checks password login and protected APIs. Also verify WorkOS
sign-in returns through the environment's registered HTTPS callback before release.

After staging deploys, CI checks health, signs in with the staging smoke account,
calls the authenticated profile and notification APIs, and opens the authenticated
notification event stream. Also check browser notification permission and delivery
manually in a real staging browser as part of CS203-69 release review.

#### Staging acceptance demo (CS203-128)

The deployment smoke check above verifies availability and authenticated endpoints.
For the broader acceptance demo, use a disposable staging account and run the
acceptance script from the repository root. It creates the account on the first run
(or logs into it if it already exists), saves and reads a known profile, runs an F-1
RAG query, and checks the notification inbox and unread-count APIs. It does not print
the account password or bearer token. Before saving profile fields, it asks you to
confirm consent for the disposable test profile; answer `yes` only if you agree to the
listed purposes.

Set `APP_BASE_URL`, `STAGING_ACCEPTANCE_EMAIL`, and `STAGING_ACCEPTANCE_PASSWORD`
in your terminal session using a unique email and a password used only for this
disposable account. Do not put these values in the repository. Ensure staging has at
least one indexed policy and reachable embedding and answer-generation providers,
then run:

```bash
bash .github/scripts/staging-acceptance.sh
```

To check profile persistence, restart or redeploy the staging service in Render,
then run the same script with `VERIFY_PERSISTENCE_ONLY=true`. This mode logs into the
same account and checks the saved profile before it writes anything; it will fail if
the profile was lost. Keep the same account variables in the terminal session.

The script checks that the notification APIs respond, but a new disposable account
may have an empty inbox. To verify notification creation and delivery, sign up the
account before importing an F-1 policy using the staging admin flow, then confirm the
alert appears in its inbox. The [notification demo](#test-policy-change-notifications)
documents the expected behavior. Review RAG citations and warnings against the source;
a successful HTTP response alone does not establish answer accuracy. The script needs
an indexed F-1 policy and working AI services, so it will fail when those prerequisites
are unavailable.

To roll back, open **Actions → Promote known-good build to production**, select the
`main` branch, and enter the full SHA of the last known-good staging deployment.
The workflow verifies that SHA and deploys its existing GHCR image after production
approval. Render's Deploys history also records each deployment and supports its
dashboard rollback action. Keep previous SHA-tagged images available in GHCR.

Set `JAVA_HOME` to your JDK installation before using the Maven Wrapper.

## Run locally in WSL

From the cloned `CS203` directory (not the parent `Project` directory), start
PostgreSQL:

```bash
docker compose up -d
docker compose ps
```

Put your OpenAI key in `.env` as `OPENAI_API_KEY=...`. The app starts without one, but
importing policies and asking RAG questions then fail with HTTP 401 from OpenAI.

### Moving from Ollama to OpenAI embeddings

Policy chunks used to be embedded with Ollama `mxbai-embed-large`. They are now embedded
with OpenAI `text-embedding-3-small`, asked for the same 1024 dimensions, so the
`policy_chunks` table is unchanged. The two models' vectors cannot be compared, so if
your database has policies imported before the switch, re-embed them once as an admin:

```bash
curl -X POST http://localhost:8080/api/v1/ingestion/embeddings/rebuild \
  -H "Authorization: Bearer $ADMIN_TOKEN"
```

The response reports `chunksReembedded`. Policies, alerts and citations are untouched;
only the vectors are replaced. Until you run it, RAG answers match against the old
vectors and are unreliable. Ollama is no longer needed.

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

#### Find your app user ID

The manager dashboard's **Applicant user ID** field expects the numeric `userId` from the
app's `users` table. An applicant can get their own ID while signed in; it is not admin-only.

In the browser where you are signed in, open Developer Tools (`F12`), select **Console**,
and run:

```javascript
fetch('/api/v1/users/me', {
  headers: { Authorization: `Bearer ${sessionStorage.getItem('mygrantToken')}` }
}).then(response => response.json())
  .then(user => console.log(user.userId, user.userEmail));
```

The first value printed is the user ID. Paste this JavaScript into the browser console, not
into Bash or a terminal. Keep the bearer token private; the command prints only the ID and
email.

From a terminal, if `$TOKEN` already contains your access token, use:

```bash
curl -s http://localhost:8080/api/v1/users/me \
  -H "Authorization: Bearer $TOKEN" \
  | python3 -c "import sys,json; print(json.load(sys.stdin)['userId'])"
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
import health and saved cutoff entries for a publication month. Open the official page in a
normal browser, save its complete HTML, and use **Import saved HTML**. Applicants and managers cannot
access the panel or its API. Every `/api/v1/ingestion` request needs an admin JWT; an
applicant's `$TOKEN` gets 403. Sign in as the [admin account](#admin-account-setup) with
`.env` loaded:

```bash
ADMIN_TOKEN=$(curl -s -X POST http://localhost:8080/api/v1/auth/login \
  -H "Content-Type: application/json" \
  -d "{\"userEmail\": \"$ADMIN_EMAIL\", \"userPassword\": \"$ADMIN_PASSWORD\"}" \
  | python3 -c "import sys,json; print(json.load(sys.stdin)['token'])")
```

The server's direct HTML and PDF downloads currently receive HTTP 403 from the Department
of State, so the scheduled source sync and bulk sync skip Visa Bulletin. The old direct
fetch API remains available for a future permitted access route but is not used by the
admin interface. Import the saved official page when each new monthly bulletin is
published; publication is not necessarily on the first day of the month. The import detects the publication
month from the page heading and rejects unrelated or incomplete HTML. This
checks content and layout; it cannot prove where an uploaded file originated.
The equivalent API request is:

```bash
curl -X POST http://localhost:8080/api/v1/ingestion/visa-bulletin/saved \
  -H "Authorization: Bearer $ADMIN_TOKEN" -H "Content-Type: text/html" \
  --data-binary @saved-bulletin.html
```

Check the import and saved rows (`YYYY-MM` is the publication month):

```bash
curl http://localhost:8080/api/v1/ingestion/visa-bulletin/health \
  -H "Authorization: Bearer $ADMIN_TOKEN"
curl "http://localhost:8080/api/v1/ingestion/visa-bulletin/entries/2026-10?size=200" \
  -H "Authorization: Bearer $ADMIN_TOKEN"
```

The entries come back as a [page](#pagination), 20 at a time unless you pass `size`; a
month has about 150.

The importer saves the four family/employment cutoff charts in
`visa_bulletin_entries`. A repeated import updates the same monthly keys. If
the page is missing a chart or its layout changes, no partial chart is saved
and health becomes `FAILED`; the last successful import time remains visible.
This is immigrant preference data. The bulletin's family `F1` category is
different from an `F-1` student visa. A student's F-1 status alone does not
enable tracking.

Applicants may optionally turn on **Visa Bulletin updates** in Your profile after saving
an immigrant preference category (such as `EB-2`), an exact chargeability chart column,
and a priority date, with consent to use all three fields. This is separate from F-1,
OPT and H-1B status. The applicant dashboard shows the latest saved chart cutoffs and,
after a later monthly import changes their row, an in-app alert. The first imported month
is a baseline; importing an older month does not announce a historical change. Re-imports
update the same alert rather than duplicating it. Turning tracking off deletes its alerts.
These are published cutoff changes, not eligibility or filing advice. The user must check
the official bulletin and USCIS chart selection. `GET` and `PUT`
`/api/v1/visa-bulletin/tracking` are authenticated applicant-only endpoints; `PUT` accepts
`{"enabled":true}` or `{"enabled":false}`.

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
`DATABASE_PASSWORD`, `OPENAI_API_KEY`, and `OPENAI_EMBEDDING_MODEL` (any OpenAI model
that can return 1024-dimension vectors).
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
  -> create OpenAI embeddings
  -> store chunks and citation metadata in pgvector
  -> link the policy to its rule and record any stage change (policy_rules, CS203-86)

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

Read stored policy records with `GET /api/v1/policies` or `GET /api/v1/policies/{id}`, and the
stage history of the rule a policy belongs to with `GET /api/v1/policies/{id}/timeline` (see
[Rule stages and timeline](#rule-stages-and-timeline-cs203-86)).

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

### Scheduled Federal Register sync and failed documents

`SourceSyncScheduler` syncs each enabled policy source on its own interval, and an admin can run
one with `POST /api/v1/ingestion/sync`. A Federal Register run searches from the date of the last
completed run to today and imports each document found. One document failing no longer fails the
run and holds back every later import:

- The failure is logged with the document number, the source and the error, recorded in
  `sync_failures`, and the run carries on with the rest. The run's summary line in the log, its
  `error` in the sync response and its `lastError` in `GET /api/v1/ingestion/status` name the
  documents that failed.
- The run still completes, so the search window moves past the failed document. It is not lost:
  every later run retries the `RETRYING` documents in `sync_failures`, whether or not the search
  still finds them. One that imports becomes `RESOLVED`. After 5 failures in a row
  (`mygrant.ingestion.max-document-attempts`) it becomes `PERMANENTLY_FAILED`, and later runs
  skip it even when the search finds it again.
- A search result that the classifier finds is about no supported visa type is not a failure:
  it is counted in the summary, not recorded and not retried.
- If the search itself fails, the whole run fails as before and the window does not move.

Admins list failed documents, newest failure first, with `GET /api/v1/ingestion/sync-failures`;
filter with `?status=RETRYING`, `PERMANENTLY_FAILED` or `RESOLVED`. Once the cause of a
permanent failure is fixed, import the document with
`POST /api/v1/ingestion/federal-register/{documentNumber}`. Its `sync_failures` entry stays
`PERMANENTLY_FAILED` as the record of what the sync tried.

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

### USCIS processing forecast (CS203-89)

Applicants can save `i765FilingDate` or `i539FilingDate` in their existing profile.
The signed-in user's `GET /api/v1/forecast/processing-times?formType=I-765`
returns a decision-window projection from up to 12 latest distinct observation dates
for one exact form, category and office. If multiple categories or offices exist,
the endpoint returns `UNAVAILABLE` with `AMBIGUOUS_SERIES` and `availableSeries`.
The user can choose the matching series on the dashboard; the app saves it with
`PUT /api/v1/forecast/processing-times/selection` and reuses it for later
forecasts and alerts. Callers can also supply exact `formCategory` and `office`
query parameters. It never averages unlike series. I-140 uses the existing
`i140FilingDate` if matching snapshots exist.

The method converts months to days with 30.44 days per month. It takes the median
of the selected *published 80th-percentile durations* as a central source figure.
Its uncertainty margin is 7 days plus twice the median absolute deviation of those
durations, half any rise from oldest to newest, and 7 days for each observation
below five. The projected upper date is filing date plus the central source figure
and margin; the start of the displayed range subtracts the margin from the
published median if present, or from the 80th-percentile figure otherwise. At
least two dated snapshots are required. This band is **uncalibrated**: it is not
an 80% probability interval. `medianDecisionDate` stays null unless at least two
snapshots contain an actual published case median. The response includes the
snapshot dates, method, and reasons the margin widened. It returns `UNAVAILABLE`
and null dates for missing or future filing dates, unsupported forms, no history,
insufficient history, or ambiguous series. Individual case decisions can differ.

Only I-765 is compared with `optStartDate`; only I-539 is compared with
`visaExpiryDate`. If the projected upper date is later, an in-app timing alert is
saved in `forecast_deadline_alerts`, returned only to its owner by
`GET /api/v1/forecast/deadline-alerts`, and shown in the dashboard timeline and
Notifications section. The key
`(user_id, form_type, deadline_type)` prevents duplicates; a changed forecast
updates that row, and a safe or unavailable forecast clears it. Evaluation runs
after a profile save, series selection, new USCIS snapshot import, and daily at 02:15 server
time. Loading or refreshing the dashboard only reads the forecast and cannot
create alerts. Account purge removes these alerts.

For a **synthetic local demo**, sign in as an admin and import
`src/test/resources/uscis/saved-i765-result.json` through the saved-result API.
Copy that file and change only `observedOn` from `2026-09-15` to `2026-09-22`,
then import the copy. Sign in as an applicant, save `i765FilingDate` and an
`optStartDate` earlier than the projected upper date in the profile, then call:

```bash
curl -H "Authorization: Bearer $USER_TOKEN" \
  'http://localhost:8080/api/v1/forecast/processing-times?formType=I-765'
```

Open **Impact dashboard → USCIS processing timeline** to see the same window and
timing warning. This demo data is a saved sample; it is not a live USCIS forecast.

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
The complete source text is retained in PostgreSQL and indexed in pgvector, including
passages after the first 6,000 characters. Chunks are embedded in batches of 50 to
keep embedding requests manageable. A long rule can take longer and use more embedding
tokens than a short one.

After deploying this change, an admin can repair Federal Register documents already
stored with the old 6,000-character limit. This uses the saved full text and does not
download it again, recreate a rule, or send policy alerts:

```bash
curl -s -X POST http://localhost:8080/api/v1/ingestion/federal-register/reindex \
  -H "Authorization: Bearer $ADMIN_TOKEN" | jq
```

The response gives `documentsReindexed` and `chunksIndexed`. Run it once with a working
OpenAI embedding key. It processes each document in its own transaction and can be
rerun safely if it stops partway through. This repairs search and citations only.
`ProfileImpactAssessmentService` still sends at most the first 12,000 characters to
the assessment model; choosing relevant chunks for that prompt remains separate work.

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
  "impactAssessment": null
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

Restart Spring Boot after changing `.env`. After a new Federal Register or manual
policy import commits, assessments run in the background for every profile whose
visa type matches the stored visa tags. Federal Register tags come from the
classifier; manual tags come from the ingestion request. The importing admin's
identity does not determine who is assessed. The import response keeps the legacy
`impactAssessment` field as `null` and no longer waits for model generation.

Only populated, consented profile facts go into the prompt. Names, emails and user
IDs are omitted; an employer name is represented only by `employerProvided: true`.
The prompt includes policy metadata, visa matches, structured restriction metadata
and at most the first 12,000 characters of source text. OpenAI returns structured
`HIGH`, `MEDIUM`, `LOW` or `NOT_APPLICABLE` urgency, confidence, reasons, actions
and a review flag. Claude independently evaluates the candidate.

Results are saved in `impact_assessments` with the captured profile version,
provider and model. A model review request, an unverified candidate or confidence
below `IMPACT_REVIEW_CONFIDENCE_THRESHOLD` (default `60`, integer percent) produces
`PENDING_REVIEW`. A score equal to the threshold does not by itself require review.
The visibility-filtered repository query returns only completed assessments that
do not need review or have been approved; pending/rejected results are excluded.
The existing feed still uses `policy_recommendations`: connecting those consumers
and implementing review actions are separate tasks.

An OpenAI failure saves `status = UNAVAILABLE` with null urgency and confidence,
without undoing ingestion or stopping other profiles. Reprocessing skips existing
rows, including failures; retry execution is separate work. Unchanged re-imports
create neither new assessments nor new vectors. Profile edits do not trigger
regeneration. Concurrent processing can make duplicate model calls, but the
unique user/policy/profile-version constraint allows only one saved result.

Set `IMPACT_ASSESSMENTS_ENABLED=false` to disable background assessments. Work is
queued in the application's in-memory executor after commit; a process restart
can lose queued work, and submission failures are logged without failing ingestion.
A durable job queue and recovery sweep are not implemented here.

Run the focused tests with Docker available:

```bash
./mvnw '-Dtest=IngestionAssessmentIntegrationTest,IngestionAssessmentListenerTests,ImpactAssessmentPersistenceTest,ProfileImpactAssessmentServiceTests,FederalRegisterConnectorTest,PolicyIngestionServiceTests' test
```

These tests mock AI providers; they do not verify live credentials or model quality.
Confidence is model-reported, not a calibrated probability, and a passing independent
judge is not proof of legal accuracy.

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

## Published impact feed

The applicant dashboard now shows published per-user policy assessments above the
notification inbox. An independently approved recommendation created by **See how
this impacts me** appears in this feed. An assessment that needs review is saved
as `PENDING_REVIEW` and is not returned by the applicant feed or detail APIs;
temporary AI failures are not saved. Managers and admins can correct and publish
held assessments with `/api/v1/review/impacts`. Corrections retain previous
versions and show a **Reviewed by a human** marker. `HIGH` assessment urgency is
shown as `HIGH` severity so the dashboard presents it as a warning.

`GET /api/v1/impacts` returns the signed-in user's published assessments, 20 per
page by default, sorted by severity then date. Use `sort=date,desc` or
`sort=severity,asc`, `severity=HIGH`, and `archived=true` for archived items.
`GET /api/v1/impacts/{id}` shows the analysis, source citation, action items and
disclaimer. `PATCH /api/v1/impacts/{id}` with `{"archived":true}` archives the
record; use `false` to unarchive it. A missing, pending, or another user's id
returns 404. Repeating the same archive request is safe. A changed profile or
policy gets a new assessment row, leaving an older archived row untouched.

Each card and detail view shows the assessment's checklist: its stored action
items, described in [Action item checklist](#action-item-checklist-cs203-4) below.
Every entry in `actionItems` keeps a `text` field with the full action, so clients
written before the checklist still work. The source citation links to the stored
policy document. There is no bulk archive or automatic unarchive.

To try the UI, start the app with PostgreSQL and valid `OPENAI_API_KEY` and
`ANTHROPIC_API_KEY`, sign in as an applicant with a complete profile, and have an
admin import a matching Federal Register policy. On the applicant dashboard,
open that policy and select **See how this impacts me**. If the independent
review approves it, return to **Impact dashboard** to see its impact card. Click
**Read details**, then **Archive this assessment**; switch to **Archived** to
find it and unarchive it. If the AI services are unavailable, run the offline
integration test below to verify this workflow with saved assessment data.

With an applicant access token in `$TOKEN`, these requests exercise the API:

```bash
curl -s -H "Authorization: Bearer $TOKEN" \
  'http://localhost:8080/api/v1/impacts?page=0&size=20&sort=severity,desc'
curl -s -H "Authorization: Bearer $TOKEN" \
  'http://localhost:8080/api/v1/impacts/IMPACT_ID'
curl -s -X PATCH -H "Authorization: Bearer $TOKEN" \
  -H 'Content-Type: application/json' -d '{"archived":true}' \
  'http://localhost:8080/api/v1/impacts/IMPACT_ID'
curl -s -H "Authorization: Bearer $TOKEN" \
  'http://localhost:8080/api/v1/impacts?archived=true'
```

To check the human-review marker, sign in as a manager or admin and use that
role's token in `$MANAGER_TOKEN`. The pending-list response supplies the id:

```bash
curl -s -H "Authorization: Bearer $MANAGER_TOKEN" \
  'http://localhost:8080/api/v1/review/impacts'
curl -s -X PATCH -H "Authorization: Bearer $MANAGER_TOKEN" \
  -H 'Content-Type: application/json' \
  -d '{"severity":"HIGH","confidence":85,"summary":"Corrected impact summary", "analysis":["Why the source applies"],"actionItems":["Check the official source"],"missingInformation":[]}' \
  'http://localhost:8080/api/v1/review/impacts/IMPACT_ID'
```

Refetch `/api/v1/impacts/IMPACT_ID` with the owner applicant's token; it will
show the corrected summary, `reviewedByHuman: true`, and a higher version.

For an offline check against a local PostgreSQL test database, run
`mvn -Dtest=ImpactFeedIntegrationTest test` with `DATABASE_URL` and `JWT_SECRET`
set as in the regular test setup. It covers sorting, filters, citations, archive
history, account isolation, pending-review visibility and manager corrections.
`node --test src/test/js/impact-feed.test.cjs` checks the dashboard rendering and
archive requests without calling AI services.

## Action item checklist (CS203-4)

Every published impact assessment now comes with a checklist: one action item for
each step in its recommended actions, in the same order. Applicants work through
the items under **Your checklist** on the dashboard, below the impact feed, and in
each assessment's detail view. Items live in the `action_items` table, and each one
is linked to the assessment that produced it, so progress survives reloads and
later corrections.

How items are made:

- Making items needs no extra AI call. When an assessment is published, either
  by an approved **See how this impacts me** or by a manager's correction, each
  recommended action becomes an item. Blank actions are skipped. An assessment
  held for review gets no items until it is published, and one with no
  recommended actions gets none.
- The title is the action text with spaces trimmed from both ends. Text longer
  than 120 characters is cut to 117 characters and an ellipsis, and the full text
  becomes the description.
- The priority follows the assessment's severity: `HIGH` gives `HIGH`, `MEDIUM` gives `MEDIUM`, and `LOW` and
  `NOT_APPLICABLE` give `LOW`.
- The deadline is the policy's effective date when the item is made. A policy
  without one gives an item without a deadline.
- Each item has a key: the SHA-256 of its text, ignoring case and runs of spaces,
  tabs and line breaks. The key is how a revised assessment recognises an action it
  already had.

When a manager corrects an assessment, or the same assessment is generated again,
each action with a new key gets a new item. An item still `PENDING` is removed if
its action is no longer listed. Items that are `IN_PROGRESS`, `COMPLETED` or
`DISMISSED` are always kept, and an action that already has an item keeps that
item unchanged. So a dismissed item never comes back, and a kept item keeps its
first priority and deadline.

An item is `PENDING`, `IN_PROGRESS`, `COMPLETED` or `DISMISSED`, and can move from
any status to any other. Completing it records `completedAt` and dismissing it
records `dismissedAt`; leaving either status clears its time. Sending the status an
item already has changes nothing. Completed and dismissed items leave the active
list but are never deleted: the **Completed** and **Dismissed** tabs list them,
and **Reopen** returns them to `PENDING`. An item still to do is `urgent` when its
deadline is at most seven days away, or has passed, and `overdue` once the deadline
has passed. The server works these out from the applicant's date on the test clock,
so moving the clock changes them.

Items follow the impact feed's visibility rule: they can be read or changed only
while their assessment is published to its owner. Items of an archived assessment
are kept but left out of the default list. `GET /api/v1/actions` returns only the
caller's own items. `PATCH` returns 404 for an id that does not exist or whose
assessment is hidden, and **403** for another user's item. The impact feed answers
404 for another user's assessment; the checklist answers 403 on purpose, because
the CS203-4 acceptance criteria require it.

`GET /api/v1/actions` returns a page of 50 items by default, and at most 100.
Without `status` it lists the active items (`PENDING` and `IN_PROGRESS`) of
unarchived assessments. `status` and `priority` can each be repeated, and an unknown
value returns 400. Items are ordered by deadline with undated items last, then by
priority (`HIGH` first), then by assessment and position; a `sort` parameter is
ignored. `PATCH /api/v1/actions/{id}` takes `{"status":"COMPLETED"}`. The impact
feed's `actionItems` are the same items, in every status, ordered by position.

With an applicant access token in `$TOKEN`:

```bash
curl -s -H "Authorization: Bearer $TOKEN" \
  'http://localhost:8080/api/v1/actions'
curl -s -H "Authorization: Bearer $TOKEN" \
  'http://localhost:8080/api/v1/actions?status=COMPLETED&status=DISMISSED&priority=HIGH'
curl -s -X PATCH -H "Authorization: Bearer $TOKEN" \
  -H 'Content-Type: application/json' -d '{"status":"IN_PROGRESS"}' \
  'http://localhost:8080/api/v1/actions/ITEM_ID'
curl -s -X PATCH -H "Authorization: Bearer $TOKEN" \
  -H 'Content-Type: application/json' -d '{"status":"COMPLETED"}' \
  'http://localhost:8080/api/v1/actions/ITEM_ID'
```

Migration `V30__create_action_items.sql` creates the table and backfills items for
every assessment its owner could already see, using the same rules written in SQL.
Restart Spring Boot to apply it.

To try it in the UI, publish an assessment as described under
[Published impact feed](#published-impact-feed), either through **See how this
impacts me** or by having a manager correct a held draft. On the applicant
dashboard, **Your checklist** lists its steps with their priority, deadline and
policy, and **Urgent** or **Overdue** tags where they apply. Use **Start**,
**Complete** and **Dismiss**, then open the **Completed** or **Dismissed** tab and
**Reopen** an item. **Read details** on the impact card shows every item of that
assessment with its status and the same buttons, and changes made there also appear
on the dashboard. To watch an item become urgent, use **Profile → Test clock** to
move your date to within a week of the policy's effective date.

To run the checks offline, set `DATABASE_URL` and `JWT_SECRET` as for the other
tests:

```bash
./mvnw test -Dtest='ActionItem*Test,ImpactFeedIntegrationTest,AccountPurgeServiceTests'
node --test src/test/js/action-checklist.test.cjs src/test/js/impact-feed.test.cjs
```

`ActionItemIntegrationTest` covers generation on publish, held and empty
assessments, owner isolation with 403 and 404, filters and ordering, every status
change, urgency against the test clock, archived assessments and manager
corrections. `ActionItemBackfillParityTest` runs the V30 backfill statement against
the Java rules for awkward text, and `ActionItemTextTest` covers the title and key
rules. The Node tests check the checklist's rendering, tabs, buttons and refetching
without a server.

Known limits:

- There are no recurring items and no dependencies between items.
- Deadlines come from the policy's effective date when an item is made. They are not
  recomputed if the rule is later amended. Likewise, a kept item keeps its priority
  when a correction changes the severity.
- A dismissed item is not brought back, even if the assessment is revised.
- The key lower-cases text with Java's rules in the app and with PostgreSQL's
  `lower()` in the V30 backfill. The two agree for the Latin, Cyrillic and other text
  the parity test checks, but not for a Greek final sigma or the Turkish dotted İ.
  A backfilled item containing those characters counts as a new action the next time
  its assessment is revised.

## Rule stages and timeline (CS203-86)

A rule reaches the Federal Register in stages, and each stage is a separate document: the
proposed rule, the final rule, perhaps a notice that delays it. myGrant groups those documents
into a **policy rule** and keeps the rule's stage, effective date and history. When the stage or
the effective date changes, affected applicants get one alert that says what moved, such as
"effective date moved from 15 Sep 2026 to 1 Nov 2026", instead of another "New policy" alert.
Litigation and court orders are tracked separately, as a **disputed** flag set by news or by a
court order an admin records, and cleared by an admin once a court lifts the block. After that,
only a court order an admin records can set it again.
Rules are in `policy_rules`. `policy_rule_documents` says which rule each document is in and
how it was matched, `policy_document_identifiers` holds the dockets and RINs, and
`policy_rule_history` holds every step with its source.

How documents are linked:

- **Identifiers.** A Federal Register import takes the API's `docket_ids` and
  `regulation_id_numbers`. Every document's header is read as well, so documents stored earlier
  and the saved fixtures link the same way offline: `[DHS Docket No. ICEB-2025-0001]` and
  `RIN 1653-AA95`. Only the text before `SUMMARY:` is read, because the body of a long rule cites
  other rulemakings. A docket is kept without its agency label, as `ICEB-2025-0001`. An agency
  code written apart from its number, `Docket No: USCIS 2020-0019` as in the delay notice
  `2021-02665`, is read as `USCIS-2020-0019`.
- **News and other secondary sources.** Articles rarely quote a docket, so the links in an
  article's description count: `federalregister.gov/d/<number>`,
  `federalregister.gov/documents/<date>/<number>/…` and `regulations.gov/docket/<id>`. A labelled
  docket or a `RIN` written in the text counts too. For a secondary source posted to
  `POST /api/v1/ingestion/documents`, the same links in `sourceUrl` and `sourceReferences` count,
  which is how an admin records a court order (see
  [Record a court order](#record-a-court-order)).
- **Matching.** A document joins the rule whose primary documents share any one of its
  identifiers. When its identifiers point to more than one rule, it joins the rule matched by
  docket (else RIN, else document number) with `MEDIUM` confidence, flagged for review with the
  other rules named.
- **Title fallback.** Used only when no identifier matches. Both titles are lower-cased, a
  trailing part that names the document's stage is dropped (`; Proposed Rule`, `; Final Rule`,
  `; Correction`, `; Delay of Effective Date` and the like), the rest is split into words
  (hyphenated words such as `f-1` stay whole), and these stopwords are removed: a, an, and,
  are, as, at, be, by, for, from, in, into, is, its, of, on, or, than, that, the, their, this,
  to, under, with, within. The score is the Jaccard similarity: the words both titles share
  divided by all the words either has. At **0.8** or above the rule is suggested with `LOW`
  confidence and always flagged for review. For the fixture rule's 17-word title, one replaced
  word scores 0.89 and still matches; two replaced words score 0.79 and do not.
- **Conflicting identifiers beat a similar title.** The fallback never suggests a rule with a
  docket or RIN that differs from the document's. That is why `2020-20845`, the 2020 proposal
  with the same title on docket ICEB-2019-0006, starts a rule of its own.
- A primary document that matches nothing starts a new rule. A news item never does; it stays
  unlinked.
- **Review flag.** `needs_review` and `review_reason` on `policy_rule_documents`, and a
  `RuleLinkFlaggedEvent`, mark a title-only match, identifiers pointing to several rules, a
  stage that would move backwards, litigation reported on a link that is not certain, and
  litigation held because an admin cleared the rule's dispute (see **Disputed** below).
  Nothing consumes the event yet, so a flag sends no alert: anomaly review (CS203-90) will
  consume it. There is no UI and no list of flagged documents. A flagged document shows only in
  its rule's timeline, under `documents` with `needsReview` and `reviewReason`, and in
  `policy_rule_documents`.

Stages:

- A Federal Register `Proposed Rule` is `PROPOSED` and a `Rule` (final, interim final, a delay
  or a correction) is `FINAL`; either is `WITHDRAWN` when its action mentions a withdrawal.
  Notices and anything else carry no stage: they can start a rule but never move one.
- The stage only moves forward: `PROPOSED`, then `FINAL`, then `WITHDRAWN`. A proposal published
  after the rule became final joins the rule but is not applied, and is flagged for review.
- Only a final rule's effective date is the rule's. A document without an effective date never
  changes or clears the stored one.
- On a rule that is already `FINAL`, a final document moves the effective date only when it is
  about the date: its `ACTION:` line or the `; …` stage suffix of its title says delay,
  postponement, effective date or correction, such as `ACTION: Final rule; delay of effective
  date.` or a `; Correction` title. The `ACTION:` line is read from the stored header, before
  `SUMMARY:`, like the identifiers. Any other final document there is a **separate final rule**
  in the same rulemaking. DHS finalised the 2023 H-1B proposal (`2023-23381`) that way:
  `2024-01770`, effective 4 Mar 2024, covered registration, and `2024-29354`, effective 17 Jan
  2025, the rest. The second is recorded as its own `ADDITIONAL_FINAL_RULE` step with its own
  date, the rule keeps 4 Mar 2024, and applicants get its usual "New policy" alert, not a date
  move.
- A document about the date gives its **new date in its `DATES:` paragraph**, after `SUMMARY:`,
  and that is where it is read. The Federal Register's `effective_on` is never used for it: for
  the real delay notice `2021-02665` it is 8 Jan 2021, the date the delayed rule was published,
  while `DATES` says "the effective date of the final rule published January 8, 2021 … is
  delayed to December 31, 2021". The wordings read are "delayed to" or "until", "postponed to" or
  "until", and "new effective date is", "will be" or "of", followed by a date such as
  `December 31, 2021`, across line breaks. The noun is not read, because the same paragraph says
  "comments on this delay until March 10, 2021". A paragraph with no such date, or with two
  different ones, gives none.
- When a delay, postponement or change-of-date notice gives no new date that can be read, its
  step is recorded, the rule's date stays, no date alert is sent, and the document is flagged
  for review with its `DATES` text as the reason. A correction that gives none leaves the date
  alone without a flag, because most corrections fix text, not the date. The generic "New
  policy" alert never states `effective_on` for a document about the date.
- A document published before the one behind the current state, such as the proposal imported
  after the final rule, is recorded in the history in date order but changes nothing.
- `EFFECTIVE` is never stored. The timeline reports it once the rule is `FINAL`, not disputed,
  and the reader's date on the test clock (their own, else the admin's, else today) has reached
  the effective date. Reaching the date sends no alert: readers were told the date when it was
  set. A disputed rule is never shown as `EFFECTIVE`, because a court may have blocked it.
- **Disputed** is a separate flag. It is set when a secondary source linked by an identifier
  (`HIGH` confidence), a news item or a court order an admin recorded, reports litigation or a
  stay, and it never changes the primary stage. The litigation phrases include lawsuit, sued,
  sues, suing, injunction, enjoined, restraining order, vacated and blocked by a judge or court;
  the full list is `LitigationSignal.PHRASES`. "Court" or "judge" followed, within the next three
  words, by blocks, halts, pauses, postpones, stays or enjoins (or blocked, halted, paused,
  postponed, stayed, enjoined) counts too, so "Court Temporarily Blocks …" and "Federal Court
  Postpones …" match but "DHS postpones the effective date" and "Supreme Court stays out of
  student visa fight" do not. Matching ignores case, so
  "sue" is not on the list: it would hit the name Sue. A stay otherwise counts only as "stay of
  the rule", "stayed the rule" or "court stay", because "stay" alone is everywhere in immigration
  text ("length of stay"). A report on a `MEDIUM` or `LOW` link is flagged for review and not
  applied. Once set, disputed stays set until an admin clears it because a court lifted the
  block (see [Clear a dispute](#clear-a-dispute)). The `CHALLENGED` status is not used for
  rules.
- **After an admin clears a dispute, news never disputes the rule again.** News of the lift
  itself ("Appeals court lifts injunction on …") reads as litigation, and nothing tells it apart
  from news of a new block. So a report of litigation from the news feed, whatever its date, is
  held for review:
  - the rule stays not disputed;
  - its link has `needs_review` set, with a `review_reason` saying an admin cleared the dispute
    and giving the lift date;
  - `policy_rule_history` records a step with `applied = false`, the rule's state unchanged
    before and after, and the same text in `reason`;
  - it sends no alert and starts no reassessment, like any other document held for review.

  Only a court order an admin records with `POST /api/v1/ingestion/documents` (see
  [Record a court order](#record-a-court-order)) disputes the rule again, with the usual "status
  disputed" alert. It must be dated after the source that lifted the block: one dated on or
  before it is about the block that was lifted, so it is held the same way. After a further
  clearing, news is held again. A rule no admin has cleared is unaffected: news still disputes
  it.

  News and admin court orders are both `SECONDARY` documents with the same fields, so neither
  the fields nor the wording tell them apart. The endpoint does: only a document posted to
  `POST /api/v1/ingestion/documents`, which is ADMIN only, counts as an admin's. The news feed,
  Federal Register imports and the backfill never do. This is not stored, so a court order
  posted while no rule matched it, and linked later by the backfill, counts as news.

A meaningful change raises one `PolicyChangedEvent`, handled after the change commits, with
the old and new stage, effective date and disputed flag and the source document. It is one of:

- a primary document moves a staged rule forward (`PROPOSED` to `FINAL`, or to `WITHDRAWN`);
- a primary document changes the effective date (on a rule already final, only one about the
  date), or gives a final rule its first one;
- a linked secondary source marks the rule disputed.

Nothing is raised for a rule's first document (its usual "New policy" alert covers it), for a
separate final rule in the same rulemaking (the same), for a document that changes nothing, for
an older document arriving late, for importing a document again, by the backfill, or when an
admin clears a dispute. Repeats
stay idempotent three ways: a document already in a rule is never tracked again, each history
row has a unique key made from its source and the state before and after, and the event is
raised only when that row is actually written, which marks it `alerted`.

Alerts and reassessment:

- A change alert goes to the same applicants as a "New policy" alert, through the same
  `notifications` rows, so the inbox, the live stream and the immediate and digest emails pick
  it up unchanged. It belongs to the document that caused the change: the new Federal Register
  document, or the news item or court order behind a dispute. That document gets no "New
  policy" alert, so each reader gets one alert per change within `UNIQUE (user_id, policy_id)`. A reader who
  already holds an alert for that document, because the event arrived twice, is skipped and
  logged; any other clash fails loudly.
- The wording, with the usual "Action needed: review your …" suffix when the rule touches a
  field the reader has filled in:
  - `Rule update for F-1, J-1: moved from proposed to final, effective 15 Sep 2026: <title>`
  - `Rule update for F-1, J-1: effective date moved from 15 Sep 2026 to 1 Nov 2026: <title>`
  - `Rule update for F-1, J-1: status disputed: litigation or a court stay reported by U.S. District Court for the District of Massachusetts. The rule is final, but its effective date of 15 Sep 2026 is in question: <title>`

  A dispute never says the rule is in effect. For a rule with no final effective date it ends
  "The official status is still proposed".
- After the change commits, a background thread reassesses the users that
  `RuleReassessmentScope` names, against the document that caused the change. By default that
  is everyone with a saved assessment of an earlier document of the same rule, whose advice is
  now stale. It goes through `PolicyRecommendationStore`, the path behind **See how this impacts
  me**, so a result is published only when the judge approves it, otherwise it waits in the
  review queue, and its checklist items follow. The import never waits for it, and the import's
  own preview assessment is unchanged. CS203-153 will widen the scope to every matching profile.

Documents stored before this change are linked by a backfill that runs in the background once
the app has started. It takes unlinked documents, primary first and oldest first, applies the
same rules, marks its history `BACKFILL` and sends no alerts and no reassessments. Running it
again changes nothing, and a failure is logged without stopping the app. It also retries news
that matched no rule when it arrived. Set `RULES_BACKFILL_ON_STARTUP=false` to skip it.

### The timeline endpoint

`GET /api/v1/policies/{id}/timeline` returns the history of the rule that policy document `{id}`
is in. Any document in the rule works, news and court orders included. As with
`GET /api/v1/policies/{id}`, any signed-in user may call it. Steps are oldest first by source
publication date, each with its source, and each step's previous values are those of the step
before.

- `primaryStage` is the stored stage from primary sources.
- `currentStage` is `EFFECTIVE` once a `FINAL` rule's effective date is on or before `asOf`, the
  reader's date, and the rule is not disputed; a derived `TOOK_EFFECT` step shows when.
  Otherwise it is `primaryStage`.
- `disputed` is set by a secondary source that reported litigation or a stay, which
  `disputedBy` names. `effectiveDatePassedWhileDisputed` is `true` when a disputed rule's
  effective date is on or before `asOf`: the UI should say "scheduled for 15 Sep 2026; blocked
  or in dispute", not "in effect". A disputed rule has no `TOOK_EFFECT` step.
- When an admin clears the dispute, that is a `DISPUTED` step back to `false`, whose source is
  the court order or news item that lifted the block (`policyId` is `null`). `disputedBy` is
  `null` again and, once the date has come, `currentStage` is `EFFECTIVE`. The `TOOK_EFFECT`
  step then comes after the clearing step and carries its date, because the rule could not take
  effect while it was blocked. If the rule is disputed again, `disputedBy` names the newest report.
- A report held for review after a clearing is not a step, so it never shows as a dispute and
  the rule stays `EFFECTIVE` once its date has passed. Like every flagged document, it is listed
  under `documents` with `"needsReview": true` and its `reviewReason`.
- A separate final rule in the same rulemaking is an `ADDITIONAL_FINAL_RULE` step. Its own
  effective date is in `ownEffectiveDate`; the step's `effectiveDate` and `previousEffectiveDate`
  stay the rule's, so it never reads as a date move. `ownEffectiveDate` is `null` on every other
  step.

Errors use the usual `ApiError` body:

- 404 `Policy document 57 was not found` when no policy has the id;
- 404 `Policy document 57 is not part of a tracked rule` for a document in no rule, such as news
  that matched none;
- 400 for an id that is not a number, and 401 without a token.

An undisputed rule: the real H-1B proposal and its two final rules, read on 6 Nov 2026 (long
titles and URLs shortened):

```json
{
  "ruleId": 2,
  "title": "Modernizing H-1B Requirements, Providing Flexibility in the F-1 Program, and Program …",
  "primaryStage": "FINAL",
  "currentStage": "EFFECTIVE",
  "asOf": "2026-11-06",
  "effectiveDate": "2024-03-04",
  "disputed": false,
  "effectiveDatePassedWhileDisputed": false,
  "disputedBy": null,
  "identifiers": [{"kind": "DOCKET", "value": "USCIS-2023-0005"}, {"kind": "RIN", "value": "1615-AC70"}],
  "documents": [
    {"policyId": 3, "externalId": "2023-23381", "sourceType": "PRIMARY", "title": "Modernizing H-1B …",
     "publishedOn": "2023-10-23", "matchMethod": "NEW_RULE", "confidence": "HIGH",
     "matchedOn": "DOCKET USCIS-2023-0005; RIN 1615-AC70", "needsReview": false, "reviewReason": null},
    {"policyId": 4, "externalId": "2024-01770", "sourceType": "PRIMARY",
     "title": "Improving the H-1B Registration Selection Process and Program Integrity",
     "publishedOn": "2024-02-02", "matchMethod": "DOCKET_AND_RIN", "confidence": "HIGH",
     "matchedOn": "DOCKET USCIS-2023-0005; RIN 1615-AC70", "needsReview": false, "reviewReason": null},
    {"policyId": 5, "externalId": "2024-29354", "sourceType": "PRIMARY", "title": "Modernizing H-1B …",
     "publishedOn": "2024-12-18", "matchMethod": "DOCKET_AND_RIN", "confidence": "HIGH",
     "matchedOn": "DOCKET USCIS-2023-0005; RIN 1615-AC70", "needsReview": false, "reviewReason": null}
  ],
  "steps": [
    {"observedOn": "2023-10-23", "recordedAt": "2026-11-06T10:09:53.816824Z", "derived": false,
     "primaryStage": "PROPOSED", "previousPrimaryStage": null,
     "effectiveDate": null, "previousEffectiveDate": null, "ownEffectiveDate": null, "disputed": false,
     "changes": ["STAGE"],
     "source": {"policyId": 3, "externalId": "2023-23381", "sourceType": "PRIMARY",
                "url": "https://www.federalregister.gov/documents/2023/10/23/2023-23381/…",
                "title": "Modernizing H-1B …", "agency": "Homeland Security Department",
                "publishedOn": "2023-10-23"}},
    {"observedOn": "2024-02-02", "recordedAt": "2026-11-06T10:09:54.559147Z", "derived": false,
     "primaryStage": "FINAL", "previousPrimaryStage": "PROPOSED",
     "effectiveDate": "2024-03-04", "previousEffectiveDate": null, "ownEffectiveDate": null,
     "disputed": false, "changes": ["STAGE", "EFFECTIVE_DATE"],
     "source": {"policyId": 4, "externalId": "2024-01770", "sourceType": "PRIMARY",
                "url": "https://www.federalregister.gov/documents/2024/02/02/2024-01770/…",
                "title": "Improving the H-1B Registration Selection Process and Program Integrity",
                "agency": "Homeland Security Department", "publishedOn": "2024-02-02"}},
    {"observedOn": "2024-03-04", "recordedAt": null, "derived": true,
     "primaryStage": "FINAL", "previousPrimaryStage": "FINAL",
     "effectiveDate": "2024-03-04", "previousEffectiveDate": "2024-03-04", "ownEffectiveDate": null,
     "disputed": false, "changes": ["TOOK_EFFECT"],
     "source": {"policyId": 4, "externalId": "2024-01770", "…": "as in the step above"}},
    {"observedOn": "2024-12-18", "recordedAt": "2026-11-06T10:09:55.435901Z", "derived": false,
     "primaryStage": "FINAL", "previousPrimaryStage": "FINAL",
     "effectiveDate": "2024-03-04", "previousEffectiveDate": "2024-03-04", "ownEffectiveDate": "2025-01-17",
     "disputed": false, "changes": ["ADDITIONAL_FINAL_RULE"],
     "source": {"policyId": 5, "externalId": "2024-29354", "sourceType": "PRIMARY",
                "url": "https://www.federalregister.gov/documents/2024/12/18/2024-29354/…",
                "title": "Modernizing H-1B …", "agency": "Homeland Security Department",
                "publishedOn": "2024-12-18"}}
  ]
}
```

A disputed rule: the D/S proposal and final rule, with the court order that postponed the final
rule recorded as below, read on 6 Nov 2026. The effective date has passed, but `currentStage` is
`FINAL` and there is no `TOOK_EFFECT` step:

```json
{
  "ruleId": 3,
  "title": "Establishing a Fixed Time Period of Admission and an Extension of Stay Procedure for …",
  "primaryStage": "FINAL",
  "currentStage": "FINAL",
  "asOf": "2026-11-06",
  "effectiveDate": "2026-09-15",
  "disputed": true,
  "effectiveDatePassedWhileDisputed": true,
  "disputedBy": {"policyId": 10, "externalId": "d-mass-1-26-cv-13799-2026-09-14", "sourceType": "SECONDARY",
                 "url": "https://www.bu.edu/isso/2026/09/15/federal-ruling-postpones-implementation-of-dhs-rule-to-eliminate-d-s/",
                 "title": "Federal court postpones the duration of status final rule",
                 "agency": "U.S. District Court for the District of Massachusetts", "publishedOn": "2026-09-14"},
  "identifiers": [{"kind": "DOCKET", "value": "ICEB-2025-0001"}, {"kind": "RIN", "value": "1653-AA95"}],
  "documents": [
    {"policyId": 6, "externalId": "2025-16554", "sourceType": "PRIMARY", "title": "Establishing …",
     "publishedOn": "2025-08-28", "matchMethod": "NEW_RULE", "confidence": "HIGH",
     "matchedOn": "DOCKET ICEB-2025-0001; RIN 1653-AA95", "needsReview": false, "reviewReason": null},
    {"policyId": 9, "externalId": "2026-14439", "sourceType": "PRIMARY", "title": "Establishing …",
     "publishedOn": "2026-07-17", "matchMethod": "DOCKET_AND_RIN", "confidence": "HIGH",
     "matchedOn": "DOCKET ICEB-2025-0001; RIN 1653-AA95", "needsReview": false, "reviewReason": null},
    {"policyId": 10, "externalId": "d-mass-1-26-cv-13799-2026-09-14", "sourceType": "SECONDARY",
     "title": "Federal court postpones the duration of status final rule", "publishedOn": "2026-09-14",
     "matchMethod": "FR_DOCUMENT", "confidence": "HIGH", "matchedOn": "FR_DOCUMENT 2026-14439",
     "needsReview": false, "reviewReason": null}
  ],
  "steps": [
    {"observedOn": "2025-08-28", "…": "PROPOSED, from 2025-16554"},
    {"observedOn": "2026-07-17", "…": "PROPOSED to FINAL, effective 2026-09-15, from 2026-14439"},
    {"observedOn": "2026-09-14", "recordedAt": "2026-11-06T10:09:58.227275Z", "derived": false,
     "primaryStage": "FINAL", "previousPrimaryStage": "FINAL",
     "effectiveDate": "2026-09-15", "previousEffectiveDate": "2026-09-15", "ownEffectiveDate": null,
     "disputed": true, "changes": ["DISPUTED"],
     "source": {"policyId": 10, "externalId": "d-mass-1-26-cv-13799-2026-09-14", "sourceType": "SECONDARY",
                "url": "https://www.bu.edu/isso/2026/09/15/federal-ruling-postpones-implementation-of-dhs-rule-to-eliminate-d-s/",
                "title": "Federal court postpones the duration of status final rule",
                "agency": "U.S. District Court for the District of Massachusetts", "publishedOn": "2026-09-14"}}
  ]
}
```

Read on 1 Sep 2026 instead, the D/S rule has `"effectiveDatePassedWhileDisputed": false`; its
`currentStage` is `FINAL` either way. For a `NEW_RULE` document, `matchedOn` lists the
identifiers it started the rule with.

### Try the rule timeline locally

With the app running and `$ADMIN_TOKEN` and an F-1 applicant's `$TOKEN` set as in
[Run locally in WSL](#run-locally-in-wsl):

```bash
# The 2025 proposal starts a rule. F-1 and J-1 applicants get "New policy that may affect F-1, J-1: …".
curl -s -X POST -H "Authorization: Bearer $ADMIN_TOKEN" \
  http://localhost:8080/api/v1/ingestion/federal-register/2025-16554
# The 2026 final rule joins it by docket and RIN. The same applicants get one alert:
# "Rule update for F-1, J-1: moved from proposed to final, effective 15 Sep 2026: …".
curl -s -X POST -H "Authorization: Bearer $ADMIN_TOKEN" \
  http://localhost:8080/api/v1/ingestion/federal-register/2026-14439
# The timeline. Use either import's "ingestion": {"policyId": …}.
curl -s -H "Authorization: Bearer $TOKEN" http://localhost:8080/api/v1/policies/POLICY_ID/timeline
```

Then:

- Import `2026-14439` again: the response says `"alreadyIndexed":true`, and there is no new
  alert and no new step.
- Import `2020-20845`: it has the same title but its own docket and RIN, so it starts its own
  rule, whose timeline has one step.
- Move your own date with the test clock, under profile settings or with
  `PUT /api/v1/test-clock/me` and `{"date":"2026-09-01"}`: the timeline's `currentStage` is
  `FINAL`. From `2026-09-15` on it is `EFFECTIVE`, until the court order below is recorded.
- Import `2023-23381`, `2024-01770` and `2024-29354` in that order. The H-1B rule moves from
  proposed to final, effective 4 Mar 2024, with one "Rule update" alert. The second final rule
  adds an `ADDITIONAL_FINAL_RULE` step with `"ownEffectiveDate": "2025-01-17"`, the rule keeps
  4 Mar 2024, and H-1B and F-1 applicants get its "New policy" alert.
- Import `2020-24259`, `2021-00183`, `2021-02665` and `2021-27714` in that order, the 2020 H-1B
  registration rulemaking (RIN 1615-AC61). H-1B applicants get "New policy …" for the proposal,
  then "moved from proposed to final, effective 9 Mar 2021", "effective date moved from 9 Mar
  2021 to 31 Dec 2021" (read from the delay notice's `DATES`) and "moved from final to
  withdrawn". The timeline ends `WITHDRAWN`, with no `TOOK_EFFECT` step.
- To see a dispute from news, import the synthetic news feed the tests use. Its articles are
  made up, so do this only on a local database:

  ```bash
  curl -s -X POST -H "Authorization: Bearer $ADMIN_TOKEN" -H 'Content-Type: application/xml' \
    --data-binary @src/test/resources/newsrss/synthetic-rule-coverage-feed.xml \
    http://localhost:8080/api/v1/ingestion/news-rss/saved
  ```

  The injunction article links to `2026-14439` and to the regulations.gov docket, so the rule
  becomes `disputed`, its `primaryStage` stays `FINAL`, and applicants get the "status disputed"
  alert. The explainer article joins the rule without changing it, and the third article
  matches no rule.

The effective-date move is tested with the real delay notice `2021-02665` and, for the D/S
rule, which has no real one, a synthetic delay notice,
`src/test/resources/federal-register/synthetic-2026-99950-delay.txt`. Like a real one, it
gives its new date only in `DATES`; its stubbed `effective_on` is its own publication date.
`RuleLifecycleIntegrationTest` runs the whole sequence, the three H-1B documents, the RIN
1615-AC61 rulemaking and the court order below. It needs a database that does not already hold
these documents, as in CI, and is skipped otherwise.

### Record a court order

Court orders are not published in the Federal Register, and news about them often carries no
docket, RIN or Federal Register link, so the news linking can miss them. None of nine real
headlines about the order that postponed the D/S rule linked to it. An admin records such an
order with the existing `POST /api/v1/ingestion/documents` (ADMIN role) as a `SECONDARY` source:

- Put a link to the rule's Federal Register document, `https://www.federalregister.gov/d/<number>`,
  or its `https://www.regulations.gov/docket/<id>`, in `sourceReferences` (or `sourceUrl`). That
  links the source to the rule with `HIGH` confidence.
- Say what the court did in the `title` or `content`, in words such as "court postpones", "judge
  blocks", "enjoined" or "injunction" (see **Disputed** above). Without them the source joins the
  rule but does not mark it disputed.
- Use `sourceUrl` for where the order or a report of it can be read, `agency` for the court,
  `publicationDate` for the date of the order, and `visaType` and `status` as for any policy
  (`CHALLENGED` fits). `content` is a short summary in your own words.

The rule becomes disputed, `disputedBy` names the source, and applicants affected by the rule get
one "status disputed" alert. The source gets no "New policy" alert, and posting it again changes
nothing. This is also the only way to dispute a rule again once an admin has cleared its
dispute: news no longer can. The order must then be dated after the source that lifted the
block; an earlier one is held for review. The real order that postponed the D/S rule
(2026-14439) on 14 Sep 2026 is saved as
[`src/test/resources/court-orders/d-mass-1-26-cv-13799-2026-09-14.json`](src/test/resources/court-orders/d-mass-1-26-cv-13799-2026-09-14.json),
which `RuleLifecycleIntegrationTest` posts:

```json
{
  "externalId": "d-mass-1-26-cv-13799-2026-09-14",
  "title": "Federal court postpones the duration of status final rule",
  "agency": "U.S. District Court for the District of Massachusetts",
  "visaType": "F-1,J-1",
  "status": "CHALLENGED",
  "sourceType": "SECONDARY",
  "publicationDate": "2026-09-14",
  "sourceUrl": "https://www.bu.edu/isso/2026/09/15/federal-ruling-postpones-implementation-of-dhs-rule-to-eliminate-d-s/",
  "sourceReferences": [
    "https://www.federalregister.gov/d/2026-14439"
  ],
  "content": "On 14 September 2026 the U.S. District Court for the District of Massachusetts postponed, …"
}
```

```bash
curl -s -X POST -H "Authorization: Bearer $ADMIN_TOKEN" -H 'Content-Type: application/json' \
  --data-binary @src/test/resources/court-orders/d-mass-1-26-cv-13799-2026-09-14.json \
  http://localhost:8080/api/v1/ingestion/documents
```

The response is `201` with the new `policyId`, which also works as `{id}` for the timeline.
Import `2025-16554` and `2026-14439` first, or the source has no rule to join.

### Clear a dispute

Nothing clears a dispute by itself: news that a block was lifted reads much like news of the
block. When a court lifts the block, for example on appeal, an admin clears it with
`POST /api/v1/policies/{id}/dispute/clear` (ADMIN role; anyone else gets 403). `{id}` is any
document in the rule, as for the timeline. Do not record the lifting order with
`POST /api/v1/ingestion/documents` as well: its wording ("vacated the injunction") reads as
litigation, so it would be held for review, or, dated after the source you clear with, dispute
the rule again.

1. Check that the order really lifts the block on this rule, and find its date.
2. Send a `reason` in your own words and a `source` with the same fields as a court order:
   `externalId` (your id for it, such as the case number and date), `title`, `agency` (the
   court), `sourceUrl` (where it can be read, http or https) and `publicationDate` (the date of
   the order). The date cannot be after your test-clock date, or before the report that
   disputed the rule, because the timeline orders steps by date.
3. Read the timeline to check it.

```bash
curl -s -X POST -H "Authorization: Bearer $ADMIN_TOKEN" -H 'Content-Type: application/json' \
  -d '{"reason": "The First Circuit vacated the postponement; DHS says the rule now applies.",
       "source": {"externalId": "ca1-26-1702-2026-11-02",
                  "title": "Appeals court lifts the postponement of the duration of status rule",
                  "agency": "U.S. Court of Appeals for the First Circuit",
                  "sourceUrl": "https://www.ca1.uscourts.gov/opinions/26-1702",
                  "publicationDate": "2026-11-02"}}' \
  http://localhost:8080/api/v1/policies/POLICY_ID/dispute/clear
```

That order is made up, to show the request. The response is `200` with the rule's id, its
unchanged `primaryStage` and `effectiveDate`, `"disputed": false`, and `clearedBy`, `clearedAt`,
`reason` and `source` as recorded. `policy_rule_history` keeps the clearing as an append-only
`ADMIN` step with `actor_email`, `reason`, `recorded_at` and the source. Only the flag changes:
the stage and effective date stay, `EFFECTIVE` is worked out when the timeline is read, no alert
is sent and no other rule changes. A rule that is not disputed gives `409`; a missing or invalid
field, or a date out of range, gives `400`.

From then on, news never disputes the rule again. A report of litigation from the news feed,
including news of the lift itself, is held for review as described under **Disputed** above.
If a held report describes a new block, check it, then record the court order with
`POST /api/v1/ingestion/documents`, dated after the source you cleared with. That disputes the
rule again with the usual "status disputed" alert, and an admin can clear that too.

To see a held report locally, clear the D/S rule's dispute as above, then import the synthetic
feed the tests use. Its one article is made up, so do this only on a local database:

```bash
curl -s -X POST -H "Authorization: Bearer $ADMIN_TOKEN" -H 'Content-Type: application/xml' \
  --data-binary @src/test/resources/newsrss/synthetic-lift-feed.xml \
  http://localhost:8080/api/v1/ingestion/news-rss/saved
```

The article, dated 3 Nov 2026, links to `2026-14439` and says an appeals court lifted the
injunction. The rule stays not disputed, no alert is sent, and the timeline lists the article
under `documents` with `"needsReview": true` but not as a step.

### Known limits

- Court orders do not appear in the Federal Register, so a court-blocked rule shows as in
  effect until an admin records the order, or, for a rule no admin has cleared, until linked
  news reports it. Record orders as above.
- Once an admin has cleared a dispute, news can no longer dispute the rule. If the rule is
  blocked again and only the news reports it, the rule stays shown as in effect until an admin
  reviews the held report and records the court order. Nothing points admins to held reports
  yet: they are flagged, but there is no review list until CS203-90, so an admin finds them only
  in the rule's timeline (`documents` with `needsReview`) or in `policy_rule_documents`.
- Clearing sends no alert and starts no reassessment, so applicants are not told the rule is in
  effect again, and assessments saved while it was disputed are not refreshed.
- Whether a final document is about the date is read from its `ACTION:` line and title. A
  manually ingested primary document without an `ACTION:` line or a `; …` suffix counts as a
  separate final rule, not a date move.
- A new date is read from `DATES` only in the wordings above. A delay notice worded otherwise
  ("the effective date is now …") moves nothing and is flagged for review. A correction that
  changes the date in other words, such as "is corrected to read", moves nothing and is not
  flagged.
- If the second of two final rules is imported before the first, the rule's `effectiveDate` stays
  the second one's, because an older document arriving late changes nothing. The steps are still
  worked out in publication order, so they show the first final rule setting the date and the
  later one as `ADDITIONAL_FINAL_RULE`, and disagree with `effectiveDate`.

## Test policy change notifications

Importing a policy alerts every applicant whose visa category it affects. An alert is
additionally flagged **action required** when the policy touches profile information that
applicant has already filled in — currently the program end date, the academic level, the
upcoming travel date, and the employer or employment status.
That mapping is a deterministic keyword heuristic like the visa classifier, **not** an LLM
decision, and it is a prompt to review rather than a legal determination.

Three behaviours surprise people:

- Alerts are created **at ingestion time**. An account registered after a document was
  imported receives nothing for it.
- Importing the same document twice alerts nobody the second time; the response reports
  `"alreadyIndexed":true` and no new alert is generated.
- A document that moves a rule already stored, such as `2026-14439` after `2025-16554`, gets a
  "Rule update" alert saying what moved instead of "New policy". See
  [Rule stages and timeline](#rule-stages-and-timeline-cs203-86).

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
  "message": "New policy that may affect F-1: ... Action needed: review your program end date.",
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
finds are not about a supported visa type are skipped. Each new import downloads and
embeds the full text, then queues impact assessments for matching profiles in the
background. Expect embedding to take a minute. Imported policies stay in the database
after the clock moves.

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
docker compose ps
```

The health response should contain `"status":"UP"`, PostgreSQL should be healthy, and
`OPENAI_API_KEY` must be set.

### 2. Ask an F-1 question

Retrieval uses the signed-in account's saved immigration profile. The legacy `visaType`
request field is ignored. Use an account whose saved category is F-1 for this example;
use a J-1 account for step 3 and an H-1B account for step 4. Changing the JSON field
does not change the caller's retrieval scope.

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

Sign in with an H-1B profile and ask about this F-1/J-1 rule:

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

### Policy Q&A and saved conversations (CS203-55 through CS203-58)

- Retrieval filters use the authenticated caller's saved visa category. Policy-specific
  follow-ups additionally restrict retrieval to that policy. Profile fields inform generation;
  user identity is omitted from the model prompt.
  If vector search finds no qualifying chunk for a selected policy that matches the saved
  visa category, the assistant uses bounded passages from that policy's stored source.
  This fallback has LOW confidence, requires review, and still needs judge approval.
- `POST /api/v1/policies/{id}/questions` accepts `{"question":"What should I do next?"}`.
  Responses include `answer`, `citations`, `confidence`, `requiresReview`, and `evaluation`.
  Confidence describes evidence strength and uncertainty, not a calibrated legal probability.
- `GET /api/v1/policies/{id}/questions?page=0&size=20` returns only the signed-in user's
  saved exchanges for that policy, oldest first. The last five database exchanges provide
  follow-up context. Client-provided history is ignored.
- General questions use `POST /api/v1/rag/query` with `{"question":"What changed?"}`;
  their history is available at `GET /api/v1/rag/history?page=0&size=20`.
- Both paths store returned answers and review notices, including citations, confidence
  and judge metadata. Rejected raw model output is not stored. Account deletion removes history.
- The recommendation page restores saved conversations after refresh/sign-in and renders
  citation links and confidence. Generated answers still require independent judge approval.
- Restart Spring Boot to apply migration `V27__create_policy_question_history.sql`.

### Test commands

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
2. Split documents and embed their text with OpenAI `text-embedding-3-small` (1024 dimensions).
3. Store embeddings and source metadata in PostgreSQL/pgvector.
4. Embed the user's question and retrieve matching chunks filtered by visa type.
5. Pass the retrieved text and question to OpenAI `gpt-6.1-sol` and return an answer
   with citations. If nothing matches, return an insufficient-evidence answer.

Set `OPENAI_API_KEY`, and import a source before asking about it; an empty index cannot
provide a grounded answer.

The question is embedded to find evidence, and the answer model is called only after
evidence is retrieved; sending a question without retrieved evidence is not part of this
RAG flow. Visa classification stays a keyword rule set and never calls a model. Changing
the embedding model requires the same 1024 dimensions, or a matching change to
`spring.ai.vectorstore.pgvector.dimensions` and a rebuilt table, followed by
`POST /api/v1/ingestion/embeddings/rebuild`.

AI output evaluation (CS203-88): see [judge configuration, versioning, and limitations](docs/ai-output-evaluation.md).

Admin quality dashboard (CS203-88): see [metrics API, date boundaries, denominators, and limitations](docs/admin-quality-metrics.md).

## Canonical impact severity

Assessment code, prompts, storage and APIs use `HIGH / MEDIUM / LOW / NOT_APPLICABLE`.
See [definitions, availability and migration](docs/impact-severity.md) and
[aligned story descriptions](docs/impact-severity-stories.md). `UNAVAILABLE` is a
processing status with null urgency, not a severity.
