# CS203-88: AI output evaluation

The existing `ImpactJudgeService` evaluates CS203-13 impact assessments and
CS203-12 RAG / policy follow-up answers. Existing recommendation revisions and
question history retain the returned `JudgeEvaluation`; no second generation or
citation schema was introduced. This implementation follows the supplied
CS203-88 acceptance criteria for citation validation, evaluation scoring,
versioned persistence and deterministic testing.

## Judge configuration and rubric

Prompt version: `grounded-output-v1`. The full system prompt and JSON schema live
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

Answers use the existing CS203-41 `CitationResponse` schema. `[n]` refers to the
1-based ordered source list supplied by the application, deduplicated by
externalId for general RAG. Policy follow-ups supply their selected source as
`[1]`, including the existing same-source fallback passages. Every numeric
bracket reference is checked against this actual list, never IDs extracted from
question text, conversation, or model output. Zero, out-of-range, overflow and
blank external IDs fail deterministically. Whether a valid source actually
supports a claim, or an answer needs a missing citation, remains a judge task.
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
are not stored in the new table. This limits retained personal content; it also
means a rejected candidate cannot be reconstructed from this audit alone.
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
historical backfill or human-review replacement is added. Prompt/rubric changes
must increment PROMPT_VERSION; measure quality on a labeled dataset before
interpreting scores as a reliability metric.

## Local validation (2026-11-08)

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
