# Load test (CS203-177)

`read-paths.k6.js` is a gentle k6 load test of the read endpoints an applicant's dashboard calls:

- `GET /api/v1/policies` (100 per page, as the dashboard asks)
- `GET /api/v1/impact-assessments`, the saved-assessment feed
- `GET /api/v1/actions` (50 per page)
- `GET /api/v1/notifications` (50 per page)
- `GET /api/v1/notifications/unread-count`

It calls **no AI endpoint**: no assessments, questions or evaluations. So it costs nothing in
provider fees and never touches the rate limits. It signs in once with a test account, then
each virtual user loads the dashboard and pauses 1–3 seconds, like a reader.

Its load is 5 virtual users by default (`LOADTEST_VUS`): a 30 s ramp-up, 2 min steady
(`LOADTEST_STEADY`), then a 30 s ramp-down. That's about 10 requests per second, which is safe
for the Render staging instance. It fails if more than 1% of requests fail, or if any endpoint's
p95 is over 800 ms.

## Run it

k6 runs from its Docker image; no install is needed.

```sh
mkdir -p target
docker run --rm -v "$PWD/loadtest:/scripts:ro" -v "$PWD/target:/out" \
  -e LOADTEST_BASE_URL=https://<staging-host> \
  -e LOADTEST_EMAIL=<test account email> -e LOADTEST_PASSWORD=<its password> \
  grafana/k6:0.54.0 run --summary-export=/out/k6-summary.json /scripts/read-paths.k6.js
```

Use a dedicated test account, not a real applicant's. It must use a password sign-in (WorkOS
accounts have no password) and have a completed profile. Keep the credentials in your shell,
never in the repository.

## Run it locally

1. Start a throwaway database and the app:

   ```sh
   docker run -d --rm --name cs203-loaddb -e POSTGRES_DB=policy_impact -e POSTGRES_USER=policy_user \
     -e POSTGRES_PASSWORD=policy_password -p 55433:5432 pgvector/pgvector:pg16
   ./mvnw -q package -DskipTests
   DATABASE_URL=jdbc:postgresql://localhost:55433/policy_impact DATABASE_USERNAME=policy_user \
     DATABASE_PASSWORD=policy_password JWT_SECRET=<base64 secret> OPENAI_API_KEY=not-configured \
     PORT=8090 EMAIL_JOBS_ENABLED=false RULES_BACKFILL_ON_STARTUP=false IMPACT_ASSESSMENTS_ENABLED=false \
     MYGRANT_INGESTION_SCHEDULER_INTERVAL=86400000 java -jar target/policy-impact-0.0.1-SNAPSHOT.jar
   ```

2. Register the test account, then seed it with `seed-local.sql`. **Local only: never run it
   against staging.** It adds 40 policies, 20 published assessments with checklists, and
   40 alerts.

   ```sh
   curl -X POST http://localhost:8090/api/v1/auth/register -H 'Content-Type: application/json' \
     -d '{"userName":"Load Test","userEmail":"loadtest@example.org","userPassword":"<password>","visaType":"F-1"}'
   docker exec -i cs203-loaddb psql -U policy_user -d policy_impact -v email=loadtest@example.org \
     < loadtest/seed-local.sql
   ```

3. Run k6 as above with `LOADTEST_BASE_URL=http://host.docker.internal:8090`.

Record each run in [docs/load-test-results.md](../docs/load-test-results.md).
