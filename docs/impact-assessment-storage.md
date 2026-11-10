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
store used by the impact feed. Generation, retry processing, review operations,
visibility filtering and integrating those consumers remain follow-up work.
Profile changes do not automatically generate new results.

Notification email buckets now use `action_required`; they no longer depend on
the removed, previously unpopulated notification severity column.

Run the migration and persistence tests against disposable PostgreSQL with Docker:

```sh
./mvnw -Dtest=ImpactAssessmentPersistenceTest test
```
