# Canonical impact severity

All profile-specific policy impacts use `HIGH / MEDIUM / LOW / NOT_APPLICABLE`.
The API fields `severity` and `urgency` express this same scale; neither has a fifth
failure or review level. `ImpactUrgency` is the backend source of supported values.

| Value | Meaning | Presentation |
| --- | --- | --- |
| `HIGH` | Direct status, work, travel, filing or deadline risk needing prompt action. | Warning, ordered first. |
| `MEDIUM` | Likely relevant without immediate urgency, or important facts remain uncertain. | Review/checklist item. |
| `LOW` | Relevant information or a reminder with no near-term action. | Information/reminder. |
| `NOT_APPLICABLE` | No material impact on the supplied profile. | No-impact explanation. |

The same policy may be `HIGH` for a user with imminent travel and `LOW` for a user
with a later program end date and no travel. Severity is relative to the individual
profile, not a global property of the policy.

## Availability and review

Provider failures use processing `status = UNAVAILABLE`, with `urgency = null`.
Successful assessment responses use `status = COMPLETED`. New-table unavailable
results also have null confidence; the legacy response DTO retains its existing
integer confidence field (zero when unavailable).

Review status, `requires_review` / `requiresHumanReview`, publication and action
item deadlines are separate fields. This change preserved the then-existing generator
rule requesting review for HIGH or material uncertainty, independent judge routing
and configured confidence threshold. It does not approve or reject any result.
A human-approved HIGH result can have `requiresHumanReview = false`. Since CS203-153,
urgency alone no longer requests review; see `docs/impact-assessment-storage.md`.

Action priorities remain HIGH / MEDIUM / LOW: HIGH impacts map to HIGH priority,
MEDIUM to MEDIUM, and LOW / NOT_APPLICABLE to LOW. That priority is a checklist
concept. Email's CRITICAL / OTHER buckets are the user's two delivery preferences.
Since CS203-91 the assessment's severity picks the bucket: HIGH uses the critical
setting; MEDIUM, LOW and not-yet-assessed changes use the other setting (unassessed
ones only ever in the digest); NOT_APPLICABLE is never emailed. In-app alerts keep
their keyword-based `actionRequired` flag. See [Email alerts](notifications-and-email.md#email-alerts).

## Migration and API contract

V44 converts legacy labels in `policy_recommendations` and assessment revision
payloads without incrementing versions or changing publication/review decisions:

| Historical label | Canonical value |
| --- | --- |
| CRITICAL, HIGH | HIGH |
| WARNING, MEDIUM | MEDIUM |
| INFORMATIONAL, LOW | LOW |
| NO_IMPACT, INFO, NOT_APPLICABLE | NOT_APPLICABLE |
| UNAVAILABLE | Null urgency/severity, processing status UNAVAILABLE |

The assessment JSON's urgency is authoritative when it and the legacy feed column
disagree. In particular, an extraction draft with MEDIUM urgency and an old INFO
feed label becomes MEDIUM. The new `impact_assessments` table already enforces the
canonical values, with processing status stored separately.

Original model-output snapshots in `stored_ai_outputs` and append-only evaluator
hashes remain unchanged as audit evidence. Successful snapshots generated before
this change already use the canonical urgency values; deserialization infers
COMPLETED when the older snapshot lacks a status field. Application assessment
records and API responses use the new representation.

Runtime model parsing, feed filters and reviewer correction requests reject legacy
aliases and unknown values. The migration is the only place accepting the old
labels. Frontend review options, badges and HIGH-first presentation use the same
four values. Existing API consumers must switch failure checks from
`urgency == UNAVAILABLE` to `status == UNAVAILABLE`.

See [aligned story descriptions](impact-severity-stories.md) for CS203-13,
CS203-87 and terminology guidance for CS203-4, CS203-42, CS203-79 and affected subtasks.
