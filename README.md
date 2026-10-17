# Policy Impact

Java/Spring Boot backend for profile-aware immigration policy retrieval and impact assessment.

## Requirements

- Java 21+
- Docker Desktop (for PostgreSQL with pgvector)
- Ollama with the `mxbai-embed-large` and `llama3.2` models

Set `JAVA_HOME` to your JDK installation before using the Maven Wrapper.

## Run locally in WSL

From the cloned `CS203` directory (not the parent `Project` directory), start
PostgreSQL and install the Ollama models:

```bash
docker compose up -d
docker compose ps
ollama pull mxbai-embed-large
ollama pull llama3.2
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

The application uses JWT bearer tokens for authenticated requests. The signing
secret is read from the `JWT_SECRET` environment variable and must not be
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

Use the token to access the authenticated profile endpoint:

```bash
curl -s http://localhost:8080/api/v1/profiles/me \
  -H "Authorization: Bearer $TOKEN"
```

Wait for `Started MyGrantApplication` before testing. Keep this terminal open while
using the API or browser interface. After code changes, stop Spring with `Ctrl+C`
and run `./mvnw spring-boot:run` again; a running JVM does not reload Java changes.

The health endpoint is available at `http://localhost:8080/actuator/health`.
Swagger UI is available at `http://localhost:8080/swagger-ui.html`.
The myGRANT application is available at `http://localhost:8080/`. Create an account
there or sign in with an existing account. The browser stores the JWT in session storage
(it is cleared when that browser tab is closed), loads the signed-in profile and matching
policies from PostgreSQL, and sends the JWT with Federal Register import and RAG requests.
Do not use VS Code Live Server for normal testing; opening the Spring URL avoids sending
API requests to the static-file server.

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
POST /ingestion/documents
  -> save the original policy in policy_documents
  -> split the policy into chunks
  -> create Ollama embeddings
  -> store chunks and citation metadata in pgvector

POST /rag/query
  -> embed the question
  -> retrieve relevant chunks filtered by visa type
  -> rank primary/effective evidence first
  -> generate a grounded Ollama answer
  -> return citations, confidence and review status
```

## API

Ingest a recent policy document:

```http
POST /ingestion/documents
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
POST /rag/query
Content-Type: application/json

{
  "question": "I am an F-1 PhD student in a five-year program. How could the new rule affect me?",
  "visaType": "F-1"
}
```

Read stored policy records with `GET /policies` or `GET /policies/{id}`.

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
  http://localhost:8080/ingestion/federal-register/2026-14439 \
  -H "Authorization: Bearer $TOKEN" | jq
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
    "provider": "Groq",
    "model": "openai/gpt-oss-20b",
    "evaluation": { "verdict": "YES", "reason": "The assessment is supported.", "model": "qwen/qwen3.8-27b" },
    "attempts": 1
  }
}
```

### Enable profile-specific urgency classification

Create a Groq API key, then add it only to the ignored `.env` file:

```env
GROQ_API_KEY=gsk_...
GROQ_MODEL=openai/gpt-oss-20b
GROQ_JUDGE_MODEL=qwen/qwen3.8-27b
```

Restart Spring Boot after changing `.env`. During each authenticated Federal Register
import, the backend sends only relevant profile fields (not the user's name or email),
policy metadata, deterministic visa matches, and a bounded source excerpt to Groq. Groq
returns strict structured JSON with `HIGH`, `MEDIUM`, `LOW`, or `NOT_APPLICABLE` urgency,
confidence, reasons, actions, and a human-review flag. The RAG Inspector displays that
assessment immediately above the imported source. The same Groq key is used for the
Qwen judge; `GROQ_JUDGE_MODEL` is optional and defaults to the value above. Make sure
the model is available to your Groq account. Qwen is a preview model and may change.

Qwen independently checks the generated urgency and recommended next steps against the
same profile and policy excerpt. A `YES` verdict returns the assessment. A `NO` gives
the generator feedback and retries once; a second `NO`, invalid response, or unavailable
judge returns an `UNAVAILABLE` assessment requiring human review, without displaying
the unapproved recommendation. A successful judge verdict is not proof of legal accuracy.
High-urgency results always require human review. Confidence is model-reported and is not
a calibrated probability. This review is based on the first 12,000 characters, not the
whole document; the deterministic notification rules remain separate. Both prompts use
the stated visa type and instruct the models not to assume that an I-140 filing means a
different current immigration status. The judge can still make mistakes; consult the
official source and a qualified adviser when appropriate.

To inspect the judge result in the terminal, sign in using the instructions above and run:

```bash
curl -s -X POST http://localhost:8080/ingestion/federal-register/2026-14439 \
  -H "Authorization: Bearer $TOKEN" | jq '.impactAssessment'
```

Check `evaluation.verdict` (`YES`, `NO`, or `UNAVAILABLE`), `evaluation.reason`, and
`attempts`. Re-imports reuse vectors but run a fresh profile assessment and judgment.

If `GROQ_API_KEY` is absent or the provider call fails, ingestion and pgvector indexing
still complete. The response uses `UNAVAILABLE` instead of fabricating an assessment.
The generator reports HTTP 429 as a rate or quota limit and HTTP 401/403 as an
authentication or access failure; check Groq usage, your key, and model access as
appropriate. An unavailable independent review can also be caused by a judge API or
response error. Check the Spring Boot terminal for `Groq impact generator`,
`Groq impact judge`, or `Impact judge` warnings: they include an HTTP status or error
type without logging the API key, policy text, profile details, or provider response.
The displayed `attempts` counts generator attempts; a retained `NO` may be from an
earlier attempt if the retry fails.

To run the focused generator and judge tests in WSL:

```bash
./mvnw '-Dtest=ImpactJudgeServiceTests,ProfileImpactAssessmentServiceTests' test
```

These tests mock Groq; they do not verify live API credentials, model access, or model quality.

The chunk count can vary. Importing the same unchanged document again returns
`"alreadyIndexed":true` and `"chunksIndexed":0`; this means the existing vectors were
reused, not that no vectors exist.

Confirm the metadata was stored:

```bash
curl -s http://localhost:8080/policies | jq
```

Then confirm that its vector chunks are retrievable for F-1:

```bash
curl -s -X POST http://localhost:8080/rag/query \
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

Registering an email that already exists returns HTTP 409 with a clear duplicate-email
message; sign in instead. Invalid JSON or dates return HTTP 400, and invalid login
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
  -H "Authorization: Bearer $TOKEN" | jq
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

## Test RAG from the terminal

Open a second WSL terminal while Spring Boot is still running. `jq` formats the JSON;
remove `| jq` if it is not installed.

### 1. Check the services

```bash
curl -s http://localhost:8080/actuator/health
ollama list
docker compose ps
```

The health response should contain `"status":"UP"`; PostgreSQL should be healthy and
both Ollama models should be listed.

### 2. Ask an F-1 question

```bash
curl -s -X POST http://localhost:8080/rag/query \
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
curl -s -X POST http://localhost:8080/rag/query \
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
curl -s -X POST http://localhost:8080/rag/query \
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

The separate RAG inspector calls `/rag/query`. The existing RAG flow is:

1. Ingest policy documents using the RAG inspector (or `/ingestion/documents`).
2. Split documents and embed their text with Ollama `mxbai-embed-large`.
3. Store embeddings and source metadata in PostgreSQL/pgvector.
4. Embed the user's question and retrieve matching chunks filtered by visa type.
5. Pass the retrieved text and question to Ollama `llama3.2` and return an answer
   with citations. If nothing matches, return an insufficient-evidence answer.

Start Ollama and confirm both models are available with `ollama list`. If missing,
run `ollama pull mxbai-embed-large` and `ollama pull llama3.2`. Import a source before
asking about it; an empty index cannot provide a grounded answer.

A hosted AI API can replace the answer-generation model while keeping retrieval.
Add the chosen provider's Spring AI chat integration, keep its API key in server-side
configuration, and configure the `ChatClient` injected into `RagService` to use that
provider. Send retrieved evidence along with the question. A direct API call with only
the question is ordinary chat, without retrieval from your documents. Keeping the
current embedding model avoids rebuilding the existing 1024-dimensional vector index;
changing the embedding model requires re-embedding the documents and checking dimensions.
The existing Groq impact-assessment configuration is separate from this RAG chat client.
