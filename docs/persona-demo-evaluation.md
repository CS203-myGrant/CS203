# Repeatable persona demo and AI evaluation

## What the seed does

The seed is opt-in and runs only with the `dev` or `staging` Spring profile. It refuses a
`prod` or `production` profile, even if another profile is active. It creates three F-1
student accounts, two H-1B employee accounts linked to one manager, and a demo admin.
All accounts use `example.test` addresses. Passwords are read from environment variables,
BCrypt-hashed, and never stored in source. Re-running the seed reuses accounts, profile
consents, manager links, and policy document numbers. It does not reset existing passwords.

Put these values in the local ignored `.env` file at the repository root, or
export them in your shell. `application.yaml` imports `.env` from the current
working directory; if the file is elsewhere, source it before starting the app:

```text
DEMO_APPLICANT_PASSWORD=<choose-a-password>
DEMO_MANAGER_PASSWORD=<choose-a-different-password>
DEMO_ADMIN_PASSWORD=<choose-a-different-password>
OPENAI_API_KEY=<working-key-for-embeddings>
```

Use a disposable development or staging database. From the repository root, one command
loads the accounts and curated policy set through the normal ingestion pipeline:

```bash
SPRING_PROFILES_ACTIVE=dev MYGRANT_DEMO_ENABLED=true ./mvnw spring-boot:run
```

The application remains running so you can sign in as `demo.student.a@example.test`,
`demo.student.b@example.test`, `demo.student.c@example.test`,
`demo.manager@example.test`, or `demo.admin@example.test`. The manager is linked to
`demo.employee@example.test` and `demo.employee.two@example.test`. Check the policy
library and manager account view after startup. The seed uses the normal full-text
embedding pipeline, so it makes OpenAI embedding calls and incurs normal provider cost.

## Saved policies and provenance

| Document | Saved content | Official source |
|---|---|---|
| 2025-16554, F/J fixed admission proposal | First 18,000 characters of the saved Federal Register text, reused from `src/test/resources/federal-register/2025-16554.txt` | https://www.federalregister.gov/d/2025-16554 |
| 2025-10669, June 2025 Proclamation 10949 | Saved excerpt reused from `src/test/resources/federal-register/proclamation-10949.txt` | https://www.federalregister.gov/d/2025-10669 |
| 2025-23570, December 2025 Proclamation 10998 | Full GPO HTML text converted to plain text; NUL characters removed | https://www.govinfo.gov/content/pkg/FR-2025-12-19/html/2025-23570.htm |
| 2025-18601, September 2025 Proclamation 10973 | Saved excerpt reused from `src/test/resources/federal-register/proclamation-10973.txt` | https://www.federalregister.gov/d/2025-18601 |

These are historical source fixtures, not a statement of current law. The June and
September files and the F/J file are excerpts. A full-source claim must be checked
against the official document before a public demo. The December text is the full
official GPO publication. The sample profiles and expected urgency labels are team
judgments for demonstration, not legal advice.
The December fixture also records explicit source-linked rules for the two demo
citizenships (Iran and Somalia), because the assessment prompt's current 12,000-character
excerpt stops before the operative list. This is a **partial extraction** marked for
human review; it does not claim to cover every country in the proclamation.

## Offline scenarios

Run without network or model credentials:

```bash
./mvnw -Dtest=DemoScenarioTests test
```

The generator and independent judge are stubbed. The tests check the saved policy
content and consented profile fields reach the model prompt, and that the service returns
the expected fixed response. This verifies wiring and visibility, **not AI accuracy**.
Student C has separate F-1 and travel-policy cases; the combined assessment depends on
CS203-81. The employer case assesses the linked employee; a manager-wide view depends
on CS203-79.

| Scenario | Team-expected urgency |
|---|---|
| Student A: F-1 proposal, no near-term deadline | LOW |
| Student B: nationality restriction and booked travel | HIGH |
| Student C: F-1 proposal | MEDIUM |
| Student C: travel restriction | HIGH |
| Employer: linked H-1B employee | MEDIUM |

## Opt-in live-model evaluation

Add `ANTHROPIC_API_KEY` to your ignored `.env`, then start the same seed with evaluation
enabled:

```bash
SPRING_PROFILES_ACTIVE=dev MYGRANT_DEMO_ENABLED=true MYGRANT_DEMO_EVALUATE=true ./mvnw spring-boot:run
```

Each startup with the evaluation flag makes five real assessment calls and independent
judge calls. It writes `demo-reports/latest.md` (per-scenario results table),
`demo-reports/history.csv` (append-only run history), and
`demo-reports/history.svg` (agreement and review-rate visualization). The report directory
is ignored by Git and can be changed with `MYGRANT_DEMO_REPORT_DIRECTORY`.

Agreement means the returned urgency matches a team label **among results approved by the
independent judge**. Generation failures and judge-rejected results are counted separately,
not as correct predictions.
Review rate is the fraction of all cases marked for human review. Record model versions,
run date, fixture versions, mismatches and limitations in the final project document.
When the AI is wrong, its generated result should be reviewed or corrected through the
existing human-review workflow; do not treat this small curated set as a calibrated
accuracy estimate.

## Jira subtask descriptions

### 1. Seed demo accounts and personas (dev and staging only)

**Scope:** Add an explicit, repeatable seed for Students A, B and C, a manager with two
linked employee accounts, and an admin. Use the existing user, profile, consent and
membership models.

**Success criteria:** Only `dev` or `staging` can run the seed; production is rejected.
Credentials come from environment variables and are hashed. Re-running creates no duplicate
accounts or manager links. Document sign-in accounts and profile assumptions.

**Dependencies:** Reuse account/profile and manager-membership services. Employer-wide
presentation remains CS203-79.

### 2. Curated policy fixture set loadable with one command

**Scope:** Package saved official-source policy text for the F/J admission proposal, June
and December 2025 travel proclamations, and an H-1B employer case. Import using the
existing policy ingestion and embedding pipeline.

**Success criteria:** One documented command loads the fixture set without downloading live
Federal Register pages. Preserve document number, publication/effective dates, status,
source URL and excerpt/full-text labeling. Re-running avoids duplicate imports.

**Dependencies:** Reuse Federal Register fixtures and ingestion deduplication; a working
embedding key and development database are required.

### 3. Persona scenario tests and opt-in live-model evaluation report

**Scope:** Add offline persona scenarios with recorded/stubbed model and judge responses.
Provide a separate opt-in live run that compares model urgency with team labels and records
review rates over time.

**Success criteria:** Offline tests run in CI without provider calls. Live results include
scenario, expected/actual urgency, judge verdict, model, agreement denominator and review
flag. Keep historical CSV and a readable visual summary. Report unavailable results and
known dependencies explicitly; never call a small curated agreement rate legal accuracy.

**Dependencies:** Reuse CS203-13 assessment and CS203-88 independent review. Combined
Student C reasoning waits for CS203-81; manager-wide view waits for CS203-79.
