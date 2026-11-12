# Assessment review and owner visibility

`ImpactReviewPolicy` is shared by async ingestion, on-demand recommendations
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
and optional `urgency=HIGH|MEDIUM|LOW|NOT_APPLICABLE` and `policyId` filters. Unsupported urgency values
return 400. Ordering is fixed: HIGH, MEDIUM, LOW, NOT_APPLICABLE, then creation date
descending and assessment ID descending for stable ties. A `sort` parameter cannot
override that ordering. Filtering and ownership apply before pagination and counts.
For example: `GET /api/v1/impact-assessments?urgency=HIGH&page=0&size=10`.
Reading either endpoint never triggers generation.

Applicants who created a profile after an official policy was imported can explicitly
request an assessment with `POST /api/v1/impact-assessments/policy/{policyId}/request`.
The server uses the authenticated account and its current profile, requires a matching
visa category and a primary policy, and applies the recommendation rate limit. The
request is idempotent for the same policy and profile version. The result enters the
same review workflow as ingestion: pending, rejected and unavailable results remain
absent from the owner-facing GET endpoints. The dashboard offers this action under
matching imported policies; clicking it is the only dashboard path that may call
the assessment model, then opens that policy's detail page. The policy assistant
appears on that page even while the assessment awaits review. Its answers use
the policy source and only a visible saved recommendation; the pending assessment
is not supplied as chat context. Refreshing or opening saved assessments remains read-only.

These endpoints expose saved records and their recommended actions. The dashboard
reads these saved records directly, preserving the API ordering and
using its urgency filter and pagination. Confidence labels use HIGH at 80% or above,
MEDIUM at 60% or above, and LOW otherwise, matching the existing feed convention.
Card navigation reads the saved-assessment detail endpoint without generating AI
output. Refresh fetches the latest available page after asynchronous processing.
These views do not add approval actions or attach saved records to the legacy
checklist/archive machinery; those remain separate tasks. Reviewer roles do not
bypass these owner-facing gates.

## Saved assessment detail

Dashboard cards open the selected assessment ID through
`GET /api/v1/impact-assessments/{id}`. The detail view uses the same urgency and
confidence labels as the card and displays its policy title, summary, reasons,
recommended actions and assessment date. HTTP/HTTPS source URLs open the original
policy in a separate tab; missing source links and empty reasons/actions have
explicit placeholders.

Recommended actions are displayed as saved advice. Opening or refreshing a detail
only reads the assessment; it does not generate recommendations, create checklist
items, archive records or approve reviews. Missing and inaccessible records share
an unavailable message, and previous detail content is cleared before loading.
Late responses cannot replace a newer selection or populate another account.
Persistent checklist completion remains CS203-4; review actions remain CS203-42.

The existing policy-detail page also reads this saved store, using
`GET /api/v1/impact-assessments?policyId={id}&page=0&size=1`, then the shared saved
assessment renderer. When multiple profile versions exist, it selects the first
visible assessment in the same urgency/date order as the dashboard. It does not
call the legacy recommendation-generation endpoint. If no visible assessment
exists, the policy source remains readable alongside a no-assessment message.
The policy filter applies before pagination and counts, together with owner and
review visibility constraints. Dashboard cards link directly to the original
policy source, with an unavailable label when a valid source link is missing.

## Frontend states and visibility

Dashboard and saved-detail views show loading indicators while reading. Empty feeds
explain that assessments are not yet available; filtered empty pages describe the
selected filter. Request failures show an error and a retry button. Feed retries
repeat the requested page with the selected urgency; detail retries repeat the same
saved-assessment ID. Both use GET without generating or mutating AI output.

The server remains authoritative for authentication, ownership and review visibility.
The frontend additionally withholds incomplete processing, pending/rejected review,
and records missing explicit eligibility metadata. Only COMPLETED records that are
APPROVED or NOT_REQUIRED with no review flag render. APPROVED records show
“Reviewed by a human”; NOT_REQUIRED records do not. Rejected/hidden detail responses
clear the previous content and display the same unavailable message as missing IDs.

Frontend integration tests exercise the actual dashboard/detail render functions,
response parsing and button handlers with mocked HTTP responses. They cover loading,
empty states, retry URLs, review markers, hidden content, and the dashboard-to-detail
flow, asserting that feed and detail reads stay read-only and only the explicit
request button sends a POST. These tests do not
replace the PostgreSQL-backed server ownership/visibility tests or a browser check.

V56 derives legacy review metadata from the central publication decisions and
synchronizes linked saved-assessment content and review status. This preserves
main's human decision, reporting, automatic-publication and email workflows while
the saved read APIs remain the dashboard data source.

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
