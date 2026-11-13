# Deployment

How myGRANT is hosted on Render, the environment variables each environment needs, and the CI/CD release path.

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
  a Flyway migration enables pgvector,
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
alert appears in its inbox. The [notification demo](notifications-and-email.md#test-policy-change-notifications)
documents the expected behavior. Review RAG citations and warnings against the source;
a successful HTTP response alone does not establish answer accuracy. The script needs
an indexed F-1 policy and working AI services, so it will fail when those prerequisites
are unavailable.

To roll back, open **Actions → Promote known-good build to production**, select the
`main` branch, and enter the full SHA of the last known-good staging deployment.
The workflow verifies that SHA and deploys its existing GHCR image after production
approval. Render's Deploys history also records each deployment and supports its
dashboard rollback action. Keep previous SHA-tagged images available in GHCR.

