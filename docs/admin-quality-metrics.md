# CS203-88 admin quality metrics

The existing admin page now includes an AI output quality section with UTC date
filters, rates, score averages, review backlog and manager decisions. It calls
`GET /api/v1/admin/metrics?from=2032-04-05&to=2032-04-06`.
The existing CS203-77 ADMIN role is required by both URL security and controller
method security. The UI also guards navigation, rendering and data loading.
Managers and applicants cannot access this endpoint. The response is aggregate
data and contains no candidate text or profile information.

## Time boundaries

`from` and `to` are ISO calendar dates, inclusive in UTC. SQL uses midnight on
`from` inclusive and midnight following `to` exclusive. For the example above,
the interval is `[2032-04-05T00:00:00Z, 2032-04-07T00:00:00Z)`. A timestamp at
the exclusive boundary belongs to the next report. Omitting `to` uses today's
real UTC date; omitting `from` uses 29 days before `to`, for a default 30-day
window. The application's simulated test clock does not change audit reporting.
Malformed/reversed dates return 400. The response includes both exact boundaries
and the timezone, so clients need not infer them from their local timezone.

The service reads evaluations and reviews in one repeatable-read transaction.
Queue state is reconstructed as of the range end, rather than using today's
mutable publication state. Data retention limitations below still apply.

## Units, numerators and denominators

All rates are JSON fractions between 0 and 1. The frontend displays percentages.
No denominator means `null`, displayed as **No data**. Counts remain zero and
missing score averages are `null`, never fabricated zero scores.

| Response field | Definition |
| --- | --- |
| `countingUnit` | `OUTPUT_CONTENT_VERSION`: unique `(output_type, output_version)` from CS203-88's existing candidate SHA-256 audit. |
| `evaluatedVersions` | Distinct content versions with at least one evaluation timestamp inside the range. |
| `passedVersions`, `failedVersions`, `unavailableVersions` | Verdict of the latest in-range evaluation of each version. Equal timestamps use descending evaluation UUID as a deterministic tiebreaker. |
| `passRate` | `passedVersions / evaluatedVersions`. Unavailable checks stay in the denominator because no pass was established. |
| `averageFaithfulness`, `averageRelevance` | Arithmetic mean of non-null scores on those selected evaluations. |
| `faithfulnessSampleSize`, `relevanceSampleSize` | Count of the corresponding non-null scores; each average has its own denominator. |
| `pendingReviewAtEnd` | All retained review records whose latest revision strictly before the range end is PENDING_REVIEW, including older backlog. The start date does not discard older pending records. |
| `reviewQueueEntries` | Distinct retained review IDs entering PENDING_REVIEW during the range. Multiple failed checks or queue re-entries of one record count once. |
| `managerReviewedOutputs` | Distinct review IDs with a human decision in range attributed to a user whose current role is MANAGER. The latest such decision per ID counts. Admin decisions are excluded. |
| `comparableManagerReviews` | Those manager-reviewed IDs whose preceding machine revision has a YES or NO verdict. Missing and UNAVAILABLE verdicts are excluded. |
| `managerOverrides` | Comparable manager decisions that disagree with the preceding automated verdict. Published means human YES; a recorded hold means human NO. Changing text while agreeing with the machine is not an override. |
| `managerOverrideRate` | `managerOverrides / comparableManagerReviews`. |

Review metrics reuse CS203-42's recommendation/revision state, including answer
drafts; extraction-review entries count in queue totals too. Human decisions are
identified by the existing `evaluation.model = Human` and recorded reviewer ID.
An answer re-evaluation now records the new automated evaluation in its existing
review revision, so a later human correction compares against that new verdict.
Repeated human corrections compare with the latest earlier machine revision,
not another human correction. Existing human-verdict precedence remains intact.

## Limitations

- The counting unit is content versions, **not unique users, generation requests
  or assessment IDs**. Identical answers from different users/questions collapse
  to one content version, even if their evidence differs. Their latest selected
  evaluation supplies the score. Changed candidate text creates a new version;
  ANSWER and ASSESSMENT are always separate units. This follows the identifiers
  already available in the audit rather than inventing historical lineage.
- Selecting the latest evaluation *inside* the range means a later evaluation
  outside it does not rewrite the earlier report. An old output re-evaluated in
  the range participates; a version with no in-range evaluation does not.
- Queue and override metrics rely on retained review revisions. Account purge,
  policy deletion, or extraction approval that removes a draft can remove that
  history. Reports are not an immutable compliance or billing ledger.
- Reviewer roles are not historically snapshotted in the existing review audit.
  Changing a reviewer's role can change historical manager attribution. Deleted
  reviewer IDs and legacy decisions without attribution are excluded.
- The existing correction endpoint publishes approvals; it has no separate human
  rejection API. Recorded holds are supported by the calculation, but the metric
  reflects the decisions the current workflow actually records.
- Scores are model judgments, not calibrated probabilities or proof of legal or
  factual accuracy. Missing scores are not imputed. Fixtures and mocked AI HTTP
  responses verify application behavior, not live evaluator accuracy.
- No schema, duplicate review subsystem, or second admin page is added. Large
  audit histories may eventually need date-oriented indexes or materialized
  aggregates; this implementation computes from the existing audit tables.

## Verification and CI

`QualityMetricsIntegrationTest` uses known PostgreSQL evaluation/review fixtures
to check exact denominators, averages, queue transitions, manager decisions,
empty ranges and UTC boundaries. `QualityMetricsControllerSecurityTest` checks
ADMIN access, applicant/manager/anonymous denial, and invalid dates.
`admin-metrics.test.cjs` verifies dashboard values, empty/error states, date
submission, request races, logout isolation and non-admin UI restrictions.

The existing CS203-82 PR workflow runs `mvn verify`, which discovers evaluator
persistence, publication-gate and metrics integration tests along with the other
Maven suites. Existing Testcontainers and JaCoCo checks are preserved; there is no
duplicate evaluator run. Node 22 runs all dashboard tests, and Maven reports are
uploaded even after a failure.
No live AI-provider credentials are needed by these integration tests.

### Validation result

Verified with upstream main `bd32f74` (including its User-profile changes,
Testcontainers tests and JaCoCo configuration): `mvn verify` passed 595 tests
across 111 classes with zero failures, errors or skips. Docker-backed tests ran
and the coverage report was generated. Node 22 passed all 52 dashboard tests,
including the seven metrics UI fixtures. Browser visual verification was not
performed; frontend behavior was checked with the repository's Node/vm tests.
