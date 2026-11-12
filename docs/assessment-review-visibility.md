# Assessment review and owner visibility

`AssessmentReviewPolicy` is shared by async ingestion, on-demand recommendations
and independent reevaluation. Configure the integer percentage threshold with
`IMPACT_REVIEW_CONFIDENCE_THRESHOLD` (default 60; valid range 0–100).

A model review request or confidence strictly below the threshold sets the saved
review flag and `PENDING_REVIEW`. Confidence equal to the threshold does not itself
require review. Unavailable processing and a failed/unavailable independent judge
also remain withheld. Human approval is authoritative, including when confidence
is below the model threshold.

## Saved ingestion assessments

The `impact_assessments` table already has explicit review fields. Its owner-facing
queries require COMPLETED processing and either NOT_REQUIRED with no review flag,
or APPROVED. Pending, rejected and unavailable results are excluded from both the
page and ID lookup. Ownership is resolved from the authenticated principal, never
from a request-supplied user ID:

- `GET /api/v1/impact-assessments`: paginated visible assessments for the caller.
- `GET /api/v1/impact-assessments/{id}`: a visible result belonging to the caller;
  missing, hidden and foreign IDs all return 404.

Both endpoints return the same response shape, including `id`, `policyId`,
`policyTitle`, `sourceUrl`, `urgency`, `summary`, percentage `confidence`, `reasons`,
`recommendedActions`, `createdAt`, and `reviewedByHuman` (true only for APPROVED).
Existing assessment metadata such as profile version and review status is retained.
Policy text and identifying profile fields are not included.

The list accepts zero-based `page` (default 0), `size` (default 20, maximum 100),
and optional `urgency=HIGH|MEDIUM|LOW|NOT_APPLICABLE`. Unsupported urgency values
return 400. Ordering is fixed: HIGH, MEDIUM, LOW, NOT_APPLICABLE, then creation date
descending and assessment ID descending for stable ties. A `sort` parameter cannot
override that ordering. Filtering and ownership apply before pagination and counts.
For example: `GET /api/v1/impact-assessments?urgency=HIGH&page=0&size=10`.
Reading either endpoint never triggers generation.

These endpoints expose saved records and their recommended actions. The dashboard
reads these saved records directly, preserving the API ordering and
using its urgency filter and pagination. Confidence labels use HIGH at 80% or above,
MEDIUM at 60% or above, and LOW otherwise, matching the existing feed convention.
Card navigation reads the saved-assessment detail endpoint without generating AI
output. Refresh fetches the latest available page after asynchronous processing.
These views do not add approval actions or attach saved records to the legacy
checklist/archive machinery; those remain separate tasks. Reviewer roles do not
bypass these owner-facing gates.

## Existing recommendation/feed/checklist paths

V45 adds explicit requires_review and review_status to `policy_recommendations`.
It preserves existing published human approvals, and routes older model-review
flags, held rows and results below the historical default threshold (60%) to
pending review. Publication changes create a revision without deleting existing
action-item progress. A later threshold setting applies to newly assessed results;
it does not retroactively withdraw human approvals or regenerate old results.

All user-facing feed, consolidated feed, detail and action-item queries share
`ImpactVisibility.VISIBLE`. Publication alone is insufficient: pending or rejected
review status, unavailable processing, or an unapproved model review flag keeps
content hidden. Batch action-item lookup applies the same gate as paginated reads.

The on-demand recommendation endpoint saves review-bound candidates, but returns
a generic awaiting-review notice with no candidate reasons, urgency or actions.
Repeated requests do not regenerate pending or rejected candidates. When a saved
ingestion result exists for the current profile version, recommendations and chat
context use it through the owner visibility gate, rather than generating a second
candidate in the legacy store. Chat context uses only visible assessments. The safe notice uses the existing unavailable
response shape; the saved candidate remains COMPLETED with PENDING_REVIEW.

Existing authorised human correction marks the legacy record APPROVED and makes
its checklist eligible for display. Subsequent AI reevaluation cannot overwrite
that human decision. A passing AI check by itself cannot clear a model review flag
or the configured low-confidence threshold.

## Tests

Real PostgreSQL tests cover non-default threshold boundaries, explicit model
review, pending/approved/rejected visibility, safe recommendation responses,
owner isolation, saved ingestion profile dates and unchanged re-ingestion, migration
backfill, checklist withholding and human approval precedence. The review actions
in canonical-table tests are represented by direct database transitions, pending
CS203-42; no new reviewer permissions or interface are introduced here.
