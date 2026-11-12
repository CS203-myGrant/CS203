# Impact assessment storage

V43 introduces `impact_assessments` and removes the three V7 impact columns from
`notifications`. Each row belongs to a user, policy and captured profile version;
the database enforces uniqueness on that combination and cascades deletion when
the user or policy is deleted.

`ImpactAssessment` maps reasons and recommended actions as JSON arrays of strings.
Confidence is an integer percentage (0–100). Completed results use the existing
`HIGH`, `MEDIUM`, `LOW`, `NOT_APPLICABLE` urgency scale. Provider failures use
`status = UNAVAILABLE` with null urgency and confidence, so failure is not a severity.
Review status is `NOT_REQUIRED`, `PENDING_REVIEW`, `APPROVED` or `REJECTED`.
The schema checks that review status agrees with `requires_review`.

This migration does not move or backfill the existing `policy_recommendations`
store used by the impact feed. New imports now generate assessments asynchronously
through `PolicyAssessmentRequested`, after the ingestion transaction commits.
`IngestionAssessmentService` captures matching profiles and their versions, performs
provider calls outside database transactions, then saves each result independently.
The unique key protects overlapping workers; duplicate provider calls are possible.

`IMPACT_REVIEW_CONFIDENCE_THRESHOLD` (default 60, range 0–100) controls review:
model review requests or confidence strictly below the threshold produce
`PENDING_REVIEW`. `ImpactAssessmentRepository.findVisibleByUserId` excludes pending,
rejected and unavailable results. Processing queries are deliberately unrestricted;
user-facing consumers must use the visibility-filtered query. Owner-scoped page and
detail reads are now available at `/api/v1/impact-assessments` (see
[review visibility](assessment-review-visibility.md)).

Provider failures persist as `UNAVAILABLE` without interrupting other profiles.
Reprocessing skips existing rows, including unavailable results; retry execution,
review actions and connecting existing feed consumers remain follow-up work.
Profile changes do not automatically generate new results. The background queue
is in memory, not a durable job queue; restart recovery is not implemented.

Notification email buckets now use `action_required`; they no longer depend on
the removed, previously unpopulated notification severity column.

Run the migration and persistence tests against disposable PostgreSQL with Docker:

```sh
./mvnw -Dtest=ImpactAssessmentPersistenceTest test
```
