# Manual testing

Checks to run by hand against a local app. For the automated suites, see the README.

## Test RAG from the terminal

Open a second WSL terminal while Spring Boot is still running. `jq` formats the JSON;
remove `| jq` if it is not installed. The queries need `$TOKEN` from
[Authentication and JWT](api.md#authentication-and-jwt), for an account that has finished
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

AI output evaluation (CS203-88): see [judge configuration, versioning, and limitations](ai-output-evaluation.md).

Admin quality dashboard (CS203-88): see [metrics API, date boundaries, denominators, and limitations](admin-quality-metrics.md).

