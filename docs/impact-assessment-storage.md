# Impact assessment storage

V46 introduces `impact_assessments` and removes the three V7 impact columns from
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

## One review rule (CS203-153)

`ImpactReviewPolicy` decides, for every completed AI assessment, whether it is
published to its owner or held for the reviewer queue. Background assessment,
the policy page, rule-change reassessment and admin re-evaluation all use it.
An assessment is held when any of these is true:

| Reason (`holdReasons` in the queue) | Condition |
| --- | --- |
| Independent check did not pass | The judge's verdict is not `YES`, including `UNAVAILABLE` or no verdict |
| Low confidence (n%) | Confidence is strictly below `IMPACT_REVIEW_CONFIDENCE_THRESHOLD` (default 60, range 0–100) |
| Model asked for review | The model set `requiresHumanReview` |

Urgency plays no part. The old rule that every `HIGH` result needs review is gone,
from both the code and the model prompt, so a confident, verified `HIGH` result is
published directly. The policy page used to publish unverified results with a
"not independently verified" flag; it now holds them like everything else. Rows
published before this change were not migrated.

## Publishing to the feed (CS203-153)

`impact_assessments` stays the processing record: one row per user, policy and
captured profile version, written whether the provider succeeds or not. What users
and managers see lives in `policy_recommendations`, which the feed, checklist,
reviewer queue, recommendation reports, revisions and stored AI outputs all key on.
Background assessment therefore publishes into that table through
`PolicyRecommendationStore.save`, the same write path as the policy page:

1. In the read transaction, the worker captures each matching profile and computes
   its fingerprint (`input_hash`) exactly as the policy page does.
2. If `policy_recommendations` already holds a row for that user, policy and
   fingerprint (for example, the user opened the page first), the worker reuses it
   and does not call the model.
3. Otherwise it calls the model with no transaction open, then, in one transaction,
   saves the processing row and stores the result (published or held). An existing
   row for the fingerprint is never replaced by background work, and a
   human-reviewed row is never replaced by anything automated.
4. V54 adds `impact_assessments.recommendation_id`, the feed row the result was
   published as. When a reviewer approves a held result, the processing row's
   `review_status` becomes `APPROVED`.

Only official (`PRIMARY`) policies are published this way. News results remain
processing records. `UNAVAILABLE` results are not published.

Opening the policy page reuses the stored result for as long as the fingerprint
is unchanged, that is, until the profile version or the policy changes. The
fingerprint serialises the policy's country and category sets, which used to be
`Set.copyOf` copies whose iteration order changes on every JVM start, so a redeploy
silently invalidated the saved result of every multi-country policy. They are now
sorted.

Saved owner-scoped reads at `/api/v1/impact-assessments` exclude pending, rejected
and unavailable records. The dashboard and policy detail read these saved records
without generation. Legacy reports, review decisions, action items and severity
emails retain their recommendation IDs. V56 synchronizes linked saved records when
the recommendation is corrected, approved, rejected or re-evaluated, so both views
show the same content and visibility. See [review visibility](assessment-review-visibility.md).
Each saved record, and each page of them, also carries `sourceFreshness`, so the app can
warn when the sources behind an assessment have stopped updating (CS203-175; see
[resilience](resilience.md#source-freshness-cs203-175)).

Provider failures persist as `UNAVAILABLE` without interrupting other profiles. Each AI
call has connect and read timeouts and a bounded retry first (CS203-174), so one slow
provider cannot hold the background queue indefinitely.
Reprocessing skips existing rows, including unavailable results; retry execution
remains follow-up work (CS203-186). Profile changes do not automatically generate
new results. The background queue is in memory, not a durable job queue; restart
recovery is not implemented.

Notification email buckets now use `action_required`; they no longer depend on
the removed, previously unpopulated notification severity column.

Run the migration and persistence tests against disposable PostgreSQL with Docker:

```sh
./mvnw -Dtest=ImpactAssessmentPersistenceTest test
```
