# myGRANT

myGRANT tracks US immigration policy changes and tells each applicant how a change affects
them personally. It watches official sources, matches every new policy to the applicants whose
visa category it touches, writes each of them a personalised impact assessment with an action
checklist, and alerts them in the app and by email.

Supported visa categories: **F-1**, **J-1** and **H-1B**, plus optional Visa Bulletin tracking
for immigrant preference categories.

## Features

- **Policy monitoring.** Imports from the Federal Register on a schedule. Admins import
  Visa Bulletin pages, USCIS processing times and news (RSS).
- **Personalised impact feed.** OpenAI writes a per-user assessment (`HIGH`, `MEDIUM`, `LOW`
  or `NOT_APPLICABLE`), and Anthropic Claude checks it independently. Assessments with low
  confidence or that fail the check wait in a human review queue.
- **Action checklist.** Each recommended action becomes a trackable item with a priority and
  deadline.
- **Policy Q&A (RAG).** Applicants can ask questions about a policy. Answers are grounded in
  the stored source text and cite it.
- **Rule timeline.** Groups proposed, final, delay and withdrawal documents into one rule and
  tracks court disputes.
- **Alerts.** In-app notifications with live updates, plus emails sent immediately or in a
  daily digest, based on the severity of the assessment.
- **Review and reports.** Applicants can report an assessment. Managers and admins review it,
  correct it and reply.
- **Processing forecast.** Estimates a decision window from USCIS processing-time snapshots.

Roles: **Applicant** (their own case), **Manager** (team dashboard, review queue, feedback inbox)
and **Admin** (everything, including sources, imports and user roles).

## Tech stack

| Layer | Technology |
| --- | --- |
| Backend | Java 21, Spring Boot 3.5, Spring Security (JWT), Flyway |
| Database | PostgreSQL 16 with pgvector (Docker) |
| AI | OpenAI (`text-embedding-3-small` embeddings, `gpt-6.1-sol` answers), Anthropic (`claude-haiku-5-5` judge) |
| Frontend | Single-page HTML/CSS/JavaScript in `app/`, served by Spring Boot |
| Sign-in and email | Email and password, or Google through WorkOS; Resend for email |
| Hosting | Docker image on Render, deployed by GitHub Actions |

## Run locally

**You need:** a JDK (21 or newer), Docker Desktop and an OpenAI API key. Run every command from
the `CS203` folder.

1. **Create your `.env`** from the template, then fill in the values below. `.env` is
   ignored by Git; never commit it.

   ```bash
   cp .env.example .env
   ```

   | Variable | Value |
   | --- | --- |
   | `DATABASE_PASSWORD`, `POSTGRES_PASSWORD` | Any local password, the same in both |
   | `JWT_SECRET` | Output of `openssl rand -base64 32` |
   | `OPENAI_API_KEY` | Your OpenAI key. Without it, imports and questions fail with HTTP 401 |
   | `ANTHROPIC_API_KEY` | Optional. Without it, every new assessment waits for human review |
   | `ADMIN_EMAIL`, `ADMIN_PASSWORD` | Optional. Creates the [admin account](#admin-account) |

2. **Start the database:**

   ```bash
   docker compose up -d
   ```

3. **Start the app:**

   ```bash
   ./mvnw spring-boot:run        # Windows PowerShell: .\mvnw.cmd spring-boot:run
   ```

   Wait for `Started MyGrantApplication`, then open **http://localhost:8080**. Use this URL, not
   VS Code Live Server. Java changes need a restart (`Ctrl+C`, then run the command again).

Useful addresses:

- App: http://localhost:8080
- Swagger UI: http://localhost:8080/swagger-ui.html
- Health check: http://localhost:8080/actuator/health

### Admin account

If `ADMIN_EMAIL` and `ADMIN_PASSWORD` are both set in `.env`, the app creates that admin, or
promotes the account if it already exists, on startup. Sign in with those values. Admins can
change other users' roles. A user whose role changed must sign out and sign in again.

### Try it out

1. **Register** an applicant at http://localhost:8080 and complete the profile (for example,
   F-1 with a program end date).
2. **Sign in as admin**, open **RAG inspector** and import a Federal Register document, such as
   `2026-14439` (F-1 and J-1).
3. **Back as the applicant**, the alert appears under **Notifications**. The personalised
   assessment appears on the **Impact dashboard** once it is generated and approved.

Alerts are created when a policy is imported. An account registered after an import gets
nothing for that policy. To replay alerts without deleting data, use the **test clock**
(**Profile → Test clock**, or **Admin → Monitored sources → Test clock**). It only replays
policies already in your database, so import one first. See
[Notifications and email](docs/notifications-and-email.md#test-clock-temporary).

## Run the tests

Start Docker first: some tests use the local database, and others start their own container.

```bash
./mvnw test                       # all Java tests
./mvnw test -Dtest=ClassNameTest  # one test class
node --test src/test/js/*.cjs     # frontend tests
```

CI runs the same Maven tests, plus the API contract check (`OpenApiContractTest`), on every
pull request to `main`.

## Troubleshooting

| Problem | Fix |
| --- | --- |
| Flyway error such as `checksum mismatch` or `Detected applied migration not resolved locally` after pulling | Someone renamed or edited a migration. Run `./mvnw clean` first. If that fails, reset the local database with `docker compose down -v` and then `docker compose up -d`. This **deletes all local data**, including accounts, so register again afterwards |
| Two migrations with the same `V` number | Rename the newer file to the next free number before merging. Never edit or renumber a migration that is already on `main` |
| A new endpoint returns 404 | The running app is an old build. Stop it and run `spring-boot:run` again |
| The inbox or the test clock shows nothing | The local database has no policies yet. Import one as admin |
| `Invalid credentials` after a database reset | The reset deleted every account. Register again; the admin account is recreated from `.env` on startup |

## Project layout

```text
app/                     Frontend (mygrant-prototype.html, config.js)
src/main/java/mygrant/   Backend, one package per feature
                         (auth, impact, ingestion, rag, rules, notifications, email, ...)
src/main/resources/      application.yaml and Flyway migrations (db/migration)
src/test/                Java tests (same packages) and frontend tests (src/test/js)
docs/                    Detailed documentation
loadtest/                k6 load test
```

How a policy flows through the app:

```text
Import policy -> store and split into chunks -> OpenAI embeddings in pgvector
             -> link to its rule (stage and effective date) -> alert matching applicants
             -> per-user AI assessment -> judge -> publish to feed, or hold for review
```

## Documentation

| Topic | Document |
| --- | --- |
| Hosting, environment variables, CI/CD, rollback | [docs/deployment.md](docs/deployment.md) |
| API conventions, authentication, errors, pagination, Q&A | [docs/api.md](docs/api.md) |
| Policy sources, imports, visa classification, background assessment | [docs/sources-and-ingestion.md](docs/sources-and-ingestion.md) |
| Impact feed, human review and action checklist | [docs/impact-feed.md](docs/impact-feed.md) |
| Rule stages, timeline, court disputes | [docs/rule-timeline.md](docs/rule-timeline.md) |
| In-app alerts, test clock, email alerts | [docs/notifications-and-email.md](docs/notifications-and-email.md) |
| Manual RAG and support chat checks | [docs/testing.md](docs/testing.md) |
| Timeouts, retries, rate limits, stale sources | [docs/resilience.md](docs/resilience.md) |
| Impact severity levels | [docs/impact-severity.md](docs/impact-severity.md) |
| AI output evaluation and quality metrics | [docs/ai-output-evaluation.md](docs/ai-output-evaluation.md), [docs/admin-quality-metrics.md](docs/admin-quality-metrics.md) |
| Human oversight | [docs/human-oversight.md](docs/human-oversight.md) |
| Load test results | [docs/load-test-results.md](docs/load-test-results.md) |
