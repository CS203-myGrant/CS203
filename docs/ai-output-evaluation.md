# CS203-88: AI output evaluation

The existing `ImpactJudgeService` evaluates CS203-13 impact assessments and
CS203-12 RAG / policy follow-up answers. Existing recommendation revisions and
question history retain the returned `JudgeEvaluation`; no second generation or
citation schema was introduced. This implementation follows the supplied
CS203-88 acceptance criteria for citation validation, evaluation scoring,
versioned persistence and deterministic testing.

## Judge configuration and rubric

Prompt version: `grounded-output-v2-chunks`. The full system prompt and JSON schema live
in `ImpactJudgeService`. Configuration reuses `anthropic.api-key`,
`anthropic.judge-model` (default `claude-haiku-5-5`), and `anthropic.base-url`.
Requests use `/v1/messages`, `max_tokens=1024`, `effort=low`, and a structured
JSON schema requiring verdict, reason, faithfulness and relevance. No sampling
temperature is overridden. Actual configured model and prompt version are saved.

- Faithfulness (0–1): proportion of material claims and actions supported by
  supplied evidence. 1 = all, 0.5 = partial, 0 = none.
- Relevance (0–1): coverage of the current question, or assessment's policy/profile
  task. 1 = full, 0.5 = partial, 0 = unrelated.
- Approval requires the model's YES verdict, both scores >= 0.8, and valid
  citations for answers. A safety rejection remains NO even with high scores.
- Missing/non-numeric/out-of-range scores, incomplete responses, missing API key,
  parsing errors or provider errors produce UNAVAILABLE, with null scores.
  An invalid citation produces NO before a model request; scores remain null.

Source text, profile, candidate and conversation are untrusted data. The judge
checks dates, policy status, uncertainty, profile fit and unsupported actions;
prior assistant messages/recommendations are not authoritative evidence.

## Citation validation

Answers extend the existing CS203-41 `CitationResponse` schema with `chunkId`.
`[n]` selects the nth exact evidence chunk supplied by the application. General
RAG and policy follow-ups preserve each vector-store `Document` ID; two chunks
from one source have separate citation numbers. Duplicate hits for one chunk
share a number. `externalId` remains the source document ID for provenance and
review ownership. `retrievedEvidence` includes the same chunk IDs and numbers.

Policy follow-ups no longer merge retrieved chunks and additional source text
under one source citation. If vector retrieval misses a visa-matching selected
policy, the existing fallback explicitly selects bounded passages and gives
that exact excerpt a deterministic `selected:` ID derived from its source and
text. This identifies application-selected evidence, not a vector-index hit.

Each numeric bracket reference is checked against the actual ordered chunk
list, never IDs extracted from questions, conversation, or model output. Zero,
out-of-range and overflow references, missing chunk IDs and blank external IDs
fail deterministically. Source-only legacy snapshots stay readable but fail
chunk validation on re-evaluation and go to review; IDs are never fabricated
for legacy evidence. Human verdicts still take precedence. Whether the exact
cited chunk supports a claim, or an answer needs a missing citation, remains
a judge task; chunk membership alone is not proof of factual accuracy.
Assessments have no bracket-citation contract; citationsValid is null for them.

## Persistence and version identity

Flyway V38 adds append-only `ai_output_evaluations`. Each attempt gets a UUID,
UTC timestamp, verdict, nullable scores/citation result, model, prompt version,
and output type. `outputVersion` is SHA-256 of the exact UTF-8 Jackson JSON of
the candidate *before* evaluation metadata is attached. Changed candidates have
different versions; repeat evaluations of identical candidates have the same
version and different attempt IDs. Existing saved answer/assessment JSON embeds
these identifiers and scores, so an evaluation remains tied to its candidate
rather than a mutable latest record. Legacy JSON remains readable with null new
fields; old verdict-only results are not backfilled with fabricated scores.

Rejected candidates are evaluated and audited before their replacement notice
is returned. The notice carries the rejected candidate's evaluation; the notice
itself was not scored. Raw rejected output, profile, question and judge rationale
are not stored in the append-only audit table. V39 separately retains the candidate,
exact evidence context, ordered citations, and latest evaluation in `stored_ai_outputs`
for publication review and admin re-evaluation. This is an intentional increase in
retention: snapshots can include consented profile facts and question context. Attached
snapshots cascade when their owning recommendation/history is deleted (including account
purge), or the user/policy is hard-deleted. Replacing an assessment draft removes its old
snapshot but retains the immutable evaluation audit. Unattached attempts (for example,
a request interrupted before history is saved) cannot be re-evaluated through the API.
Persistence failures propagate instead of returning a new approved output.
Responses without a generated candidate (e.g. empty retrieval) have no evaluation.

## Verification and limitations

`ImpactJudgeServiceTests` mocks HTTP responses for grounded, unsupported and
invalid-citation examples, score validation, disabled/unavailable judges and
persistence failure. `EvaluationPersistenceIntegrationTest` checks persisted
scores, verdicts, candidate versions and JSON metadata against PostgreSQL using
a mocked judge. Existing answer/assessment tests cover approval gates.

The scores are model judgments, not calibrated probabilities or proof of factual
accuracy. A valid citation ID does not establish entailment. Missing, stale,
incorrect or truncated retrieved evidence limits what can be evaluated. Numeric
bracket syntax is the supported citation contract; other citation styles are not
validated deterministically. Honest abstentions can pass when relevant. Models
may share biases or fail to resist prompt injection; fixed mocks test application
behavior, not real-world judge reliability. No automatic retries, correction loop,
historical backfill or human-review replacement is added. Legacy outputs without a
candidate snapshot cannot be re-evaluated through the new endpoint. Prompt/rubric changes
must increment PROMPT_VERSION; measure quality on a labeled dataset before
interpreting scores as a reliability metric.


## Publication gate and admin re-evaluation

The existing judge applies the shared >=0.8 score/citation rules to every generated
assessment and answer before it is exposed. `NO` and `UNAVAILABLE` both hold the
candidate for the existing CS203-6/42 review workflow; applicants receive a notice,
never the raw rejected draft. Generator failures without a candidate remain retryable.
A database/persistence failure aborts the request rather than publishing unchecked output.
The Federal Register import, per-policy assessments, and rule-triggered reassessments
use `PolicyRecommendationStore`; both Q&A paths bind their evaluated candidates when
saving history. CS203-87/91 can reuse these same generation and publication paths.

Admin endpoint (CS203-77 roles):

```http
POST /api/v1/evaluate
Authorization: Bearer <admin-token>
Content-Type: application/json

{"outputId":"<original evaluationId>"}
```

`outputId` is the initial evaluation UUID for the retained candidate, also exposed on
pending review entries. The response contains `outputId`, the new `evaluation`, effective
`publicationStatus`, `reviewedByHuman`, and `reviewId`. A re-evaluation's new audit UUID
is an attempt ID; continue using the original `outputId` for subsequent requests. Missing,
unknown, unowned, or pre-V39 outputs return 404; malformed requests return 400. Applicants
and managers receive 403; anonymous callers receive 401.

Re-evaluation uses the stored candidate and original evidence, never client-supplied text
or a regenerated answer. An output lock and the existing review-row lock serialize state
changes. Repeated evaluations append audit attempts but reuse the same pending-review row.
A failed re-evaluation holds a previously automatically published output; held Q&A history
is redacted until it passes or receives a human correction. A later pass can republish it.

Answer drafts reuse the existing review ownership, membership scope, publication states,
revision audit and correction endpoint. `output_type = ANSWER` keeps them out of impact
feeds and action checklists. Their `reviewType` is `Policy Q&A`; a review correction's
`summary` supplies the corrected answer in saved Q&A history. It does not rewrite an
already delivered HTTP response.

A recorded `reviewed_by_human` decision always takes precedence: automated checks still
record their new evaluation, but cannot change the corrected text, publication status,
reviewer attribution, or action progress. This includes a recorded human hold. The existing
review endpoint publishes corrections; this change adds no separate human-rejection API.

## Additional validation (2026-11-09)

`PublicationGateIntegrationTest` checks evaluator outage routing, repeated re-evaluation
without duplicate review entries, human correction preservation, recorded human holds,
invalid-citation answer review, and redaction after a previously passing answer fails.
`EvaluationControllerSecurityTest` covers admin access, applicant/manager/anonymous denial,
and request validation. All 86 targeted unit, PostgreSQL integration, security and API
contract checks passed against `cs203_88_evaluation_test`, including V39 migration and
review/feed/action/manager/ingestion regressions. Tests mock AI HTTP responses; no live
model calls were made.

## Full-suite validation (2026-11-09)

The complete Maven test suite passed against the isolated
`cs203_88_evaluation_test` PostgreSQL database: **555 tests across 98 test
classes, zero failures, zero errors, and zero skipped tests**.

The chunk-level update adds tests for separate citations to two chunks from one
source, rejection of an unseen third chunk, policy follow-up chunk numbering,
legacy source-only rejection, and persistence/re-evaluation of chunk identity.

```sh
./mvnw -q \
  -Dspring.datasource.url=jdbc:postgresql://localhost:5432/cs203_88_evaluation_test \
  -Djwt.secret=dGVzdC10ZXN0LXRlc3QtdGVzdC10ZXN0LXRlc3QtdGVzdC10ZXN0 \
  test
```

The implementation has been checked against the four supplied success criteria.
The separate parent story was not found in the repository and has not yet been
verified; its acceptance criteria still need to be provided or accessed through
the issue tracker.

## Earlier local validation (2026-11-08)

35 targeted unit tests and 3 PostgreSQL integration tests passed. All 38 Flyway
migrations applied successfully to the isolated `cs203_88_evaluation_test`
database. The existing local `policy_impact` database has a pre-existing V34
checksum mismatch; its migration history was not repaired or changed. The
project's PostgreSQL container was started and remains running.

Reproduce the unit checks:

```sh
./mvnw -q -Dtest=ImpactJudgeServiceTests,ProfileImpactAssessmentServiceTests,RagServiceTests,PolicyChatServiceTests,PolicyRecommendationStoreTest test
```

With an isolated PostgreSQL database and a dummy test-only JWT key:

```sh
./mvnw -q \
  -Dspring.datasource.url=jdbc:postgresql://localhost:5432/cs203_88_evaluation_test \
  -Djwt.secret=dGVzdC10ZXN0LXRlc3QtdGVzdC10ZXN0LXRlc3QtdGVzdC10ZXN0 \
  -Dtest=EvaluationPersistenceIntegrationTest,PolicyQaIntegrationTest test
```

No live AI-provider calls were made by these tests.
