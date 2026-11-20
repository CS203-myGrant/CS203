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

## Database schema

49 application tables across 10 functional modules, 435 columns, 65 database foreign keys and
one logical pgvector link. Derived from Flyway migrations V1–V56.

[![Entity Relationship Diagram](docs/figures/erd.png)](docs/figures/erd.png)

| Module | Tables |
| --- | --- |
| 01 Accounts & profiles | `users`, `user_profiles`, `immigration_documents`, `profile_field_consents`, `manager_user_memberships` |
| 02 Authentication & support | `refresh_tokens`, `account_deletion_requests`, `account_deletion_audit`, `feedback_conversations`, `feedback_messages`, `visa_application_progress` |
| 03 Policy catalog & retrieval | `policy_documents`, `policy_chunks`, `policy_affected_countries`, `policy_restriction_categories`, `policy_source_references`, `policy_country_restrictions`, `policy_document_identifiers` |
| 04 Policy rules & anomalies | `policy_sources`, `policy_rules`, `policy_rule_documents`, `policy_rule_history`, `anomalies` |
| 05 Source operations | `policy_sources`, `policy_source_change_log`, `policy_source_sync_runs`, `sync_failures` |
| 06 AI recommendations & assessments | `policy_recommendations`, `impact_assessments`, `policy_question_history`, `ai_output_evaluations`, `stored_ai_outputs` |
| 07 Actions & human review | `human_reviews`, `action_items`, `impact_assessment_revisions`, `impact_archive_events`, `impact_review_decisions`, `recommendation_reports` |
| 08 Notifications & email | `notifications`, `email_preferences`, `email_deliveries`, `impact_email_alerts` |
| 09 Visa bulletin tracking | `visa_bulletin_entries`, `visa_bulletin_health`, `visa_bulletin_imports`, `visa_bulletin_subscriptions`, `visa_bulletin_alerts` |
| 10 Processing forecasts | `processing_time_snapshots`, `uscis_processing_time_health`, `forecast_series_selections`, `forecast_deadline_alerts` |

**Reading the diagram:**
- Underlined columns form the primary key; composite primary key parts are all underlined.
- FK labels and solid arrows show database-enforced references to the parent key.
- Dashed arrows show the `policy_chunks` metadata/`policy_id` link, which is not a database FK.
- Gray parent boxes repeat a referenced key from another panel; they are not extra tables.

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
