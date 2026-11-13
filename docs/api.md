# API reference

Conventions shared by every endpoint, authentication, and the policy Q&A endpoints. Swagger UI at `/swagger-ui.html` lists every endpoint.

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
[Rule stages and timeline](rule-timeline.md)).

## Policy Q&A and saved conversations (CS203-55 to CS203-58)

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
