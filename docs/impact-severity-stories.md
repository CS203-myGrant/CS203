# Stories aligned to the canonical impact scale

These repository copies update the story descriptions supplied for CS203-13 and
CS203-87. They are ready to copy into the issue tracker; this file does not update
the external tracker. Only severity terminology is standardised here. Other story
requirements and historical limits below remain as supplied, rather than being
re-scoped by the severity change.

See [canonical definitions and migration mapping](impact-severity.md).

## CS203-13 — As a user, I want to get my user information and input analyzed for personalization

### What this covers

The step that turns a generic policy feed into a personalised one: match an ingested
policy against each user's profile and produce an assessment of what it means for
that specific person.

- The deterministic visa classifier pre-filters, so only profiles whose visa type
  matches reach the LLM. This keeps the existing deterministic-before-LLM convention
  and avoids sending every policy to the model.
- Each assessment records severity, a plain-English summary, the analysis behind
  it, a confidence and the signals that matched.
- The same document can be HIGH for one user and LOW for another.

### Acceptance criteria

- On ingestion, the classifier pre-filters and only matching profiles enter the
  assessment pipeline.
- For each matched profile, an assessment is created with severity
  (`HIGH`, `MEDIUM`, `LOW`, `NOT_APPLICABLE`), summary, detailed analysis, confidence
  and matched signals.
- The assessment records the profile version it was generated from, so a later
  profile edit does not silently rewrite history.
- Output is structured JSON matching the assessment fields; a malformed model
  response is retried and then flagged rather than persisted as-is.
- An assessment below the confidence threshold is stored with `requires_review = true`.
- Summaries are written in plain English, referring to the user's own dates and
  status rather than to regulation numbers.

### Subtasks

- CS203-59: add impact_assessments table and migration.
- CS203-60: profile matcher to select affected users after ingestion.
- CS203-61: impact assessment prompt and structured JSON parsing using the canonical scale.
- CS203-62: severity scoring using the canonical scale and separate review threshold.

### Validate against the vision doc scenarios

- Student A (program ends May 2027, no travel): LOW, with a dated reminder rather
  than a warning.
- Student B (on OPT, travel booked): HIGH on the same document.
- Employer view of the same rule: aggregate rather than individual.

### Known limits

- Matching currently keys off visa type and a small number of profile fields, so
  most policies cannot be tied to a specific detail of a user.
- No cross-policy reconciliation: the Student C case, where two unrelated rules
  intersect, produces two separate assessments rather than one combined read.
- Assessment quality is bounded by the 6,000-character indexing limit on source
  documents. (Historical story wording; current ingestion/prompt limits are
  documented in [sources-and-ingestion.md](sources-and-ingestion.md).)

### Follow-ups

- CS203-4 turns assessments into an actionable checklist.
- CS203-6 governs the confidence labelling and review routing this story sets.

## CS203-87 — As a user, I want a dashboard feed of how each policy change affects me, ordered by urgency, which I can archive once handled

### What this covers

CS203-13 creates impact assessments, but nothing lets the user see them. This story
is the user-facing side of the Impact Assessment CRUD entity from the Project
Vision (Read and Delete/archive): a dashboard feed of "what this means for you",
sorted so the most urgent item is first.

- Each card shows severity, the plain-English summary, confidence label, the policy
  it came from and its linked checklist items.
- Output matches urgency: HIGH reads as a warning, lower severities as a checklist
  or reminder. Severity values are HIGH / MEDIUM / LOW / NOT_APPLICABLE.
- The user can archive an assessment once it is dealt with, keeping the dashboard
  clean without deleting the record.

### Acceptance criteria

- GET /api/v1/impacts returns only the caller's published assessments (not those
  still in review), paginated, sortable by severity and date, filterable by the
  canonical severity values and archived state.
- GET /api/v1/impacts/{id} returns the full assessment with detailed analysis,
  citations and linked action items.
- PATCH /api/v1/impacts/{id} archives or unarchives; another user's assessment returns 404.
- Frontend ImpactFeed on the dashboard: severity-coloured cards, HIGH pinned to
  the top, click-through to detail.
- An assessment corrected by a manager shows the corrected version and a
  "reviewed by a human" marker.
- Empty state explains why there is nothing yet (no matching policies, or profile incomplete).

### Known limits

- No bulk archive.
- Archived assessments are not un-archived automatically if the underlying rule
  changes; CS203-86 creates a new assessment instead.

### Follow-ups

- Depends on CS203-13 (assessments) and CS203-6 (review status and confidence).
- Checklist items inside each card come from CS203-4.

## Alignment for related stories and the persistence/async subtasks

- **CS203-4:** Assessment severity is HIGH / MEDIUM / LOW / NOT_APPLICABLE.
  Checklist priority remains a separate HIGH / MEDIUM / LOW field and does not
  replace action deadlines or completion state.
- **CS203-42:** Review and correction forms accept only the four canonical impact
  levels. Review status and requires_review remain separate from severity;
  UNAVAILABLE is processing state, not a correction severity.
- **CS203-79:** Manager severity counts and ordering use HIGH first, followed by
  MEDIUM, LOW and NOT_APPLICABLE. Review queues remain governed by review state.
- **CS203-153 / storage subtask:** Persist the canonical urgency enum; use
  status = UNAVAILABLE with null urgency for provider failures.
- **Async matching-profile assessment subtask:** The generator schema and parser
  accept only canonical levels. Configured confidence and model review flags keep
  their existing behaviour; processing failures do not become severity values.
- **Review-threshold/visibility subtask:** Eligibility for display depends on
  processing/review/publication state, not the spelling or ordering of severity.
