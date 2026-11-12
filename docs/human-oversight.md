# Human oversight of AI outputs

## What was already implemented

An applicant can report a published impact from its detail view. The report is tied to
the assessment ID and version, includes a category, and appears in the linked
manager's or admin's review queue. A reviewer can publish a real correction with a
note, or verify that a *reported published assessment* is correct and dismiss the
report with a note. The applicant sees the outcome in report history and updates.
General `/api/v1/feedback` messages are support conversations and remain separate.

## What this change adds

The older policy-detail **Report recommendation** form now submits the same structured
report to the review queue. It first locates the caller's newest published assessment
for that policy and checks that its summary and urgency match the recommendation on
screen. If there is no matching published assessment, the page offers Contact support
instead of claiming a report was queued.

For a pending AI draft, a linked manager or admin can choose:

- **Verify and publish:** accept the unchanged completed draft after checking its source.
  The applicant feed and checklist then show the human-reviewed result.
- **Reject draft:** retain its content and review history for audit but keep it out of
  the applicant feed and review queue. A Policy Q&A answer remains withheld.
- **Publish correction:** use the existing editor when the draft needs changes.

`POST /api/v1/review/impacts/{id}/decision` accepts `decision` (`VERIFY` or `REJECT`),
the current `version`, and a required note of at most 1,000 characters. The endpoint
checks reviewer role, manager membership, pending status, and version under a row lock.
It rejects stale or repeated decisions. The note and reviewer are stored in
`impact_review_decisions`; the existing revision trigger records the status change.
Re-evaluation cannot overturn a human decision. A Policy Q&A draft with invalid or
unknown citation references cannot be verified unchanged; the reviewer must correct
or reject it. Policy extraction checks still require their dedicated correction flow.

For a *reported published assessment*, use the existing **Verify as correct and close
report** action or publish a correction. A report never silently hides a published
urgent assessment.

## How to test

Run `./mvnw -Dtest=ImpactFeedIntegrationTest,PublicationGateIntegrationTest,RecommendationReportIntegrationTest test`
with the normal CI test environment, then `node --test src/test/js/*.test.cjs`.
The Java tests cover reviewer scope, applicant isolation, version conflicts, audit
rows, visibility, safe Policy Q&A handling, and reports reaching the queue. The
dashboard tests check the policy-detail report target and draft decision request.

For a manual demo, use a dev/staging account with a pending draft in **Review queue**.
Open it, compare the source and profile, write a note, and choose Verify and publish
or Reject draft. Verify shows a human-reviewed marker in the applicant feed; reject
keeps it hidden. To show applicant reporting, open a published assessment from either
the dashboard or the policy detail, submit **Report recommendation**, then return to
the manager/admin review queue. Correct it or verify it as correct with a note, and
check the applicant's report updates.
