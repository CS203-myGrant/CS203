# Impact feed and action checklist

How per-user impact assessments are published, reviewed and archived, and how their recommended actions become a checklist.

## Published impact feed

The applicant dashboard now shows published per-user policy assessments above the
notification inbox. Assessments reach the feed two ways:

- **Automatically after ingestion (CS203-153).** When an official (`PRIMARY`) policy
  is imported, every user whose profile matches it gets their own assessment in the
  background, published straight to their feed and checklist or held for review.
  News (`SECONDARY`) results are kept as processing records only and never reach
  the feed, in line with news never creating alerts.
- **On demand,** when the user opens a policy and selects **See how this impacts me**.
  If the background assessment already covers that user's current profile version,
  the page shows it without calling the model again.

Both paths use one review rule (`ImpactReviewPolicy`). An assessment is **held for
review** (saved as `PENDING_REVIEW`, hidden from its owner, listed in the reviewer
queue) when the model asked for review, its confidence is below
`IMPACT_REVIEW_CONFIDENCE_THRESHOLD` (default 60), or the independent judge did not
pass it or was unavailable. Otherwise it is **published**, whatever its urgency: a
confident, verified `HIGH` result goes straight to the user. Without
`ANTHROPIC_API_KEY` the judge is unavailable, so every new assessment is held.
Assessments published before this rule existed are left as they were.

While an assessment is held, its policy page says it is awaiting review
(`status: AWAITING_REVIEW`) and does not call the model again, even with
`refresh=true`. Temporary AI failures are not saved. Managers and admins can
approve (publish unchanged) or correct held assessments with
`/api/v1/review/impacts`; the queue shows why each AI draft was held
(`holdReasons`). Corrections retain previous versions and show a **Reviewed by a
human** marker, and no automated path ever changes a human-reviewed assessment.
`HIGH` assessment urgency is shown as `HIGH` severity so the dashboard presents it
as a warning.

`GET /api/v1/impacts` returns the signed-in user's published assessments, 20 per
page by default, sorted by severity then date. Use `sort=date,desc` or
`sort=severity,asc`, `severity=HIGH`, and `archived=true` for archived items.
`GET /api/v1/impacts/{id}` shows the analysis, source citation, action items and
disclaimer. `PATCH /api/v1/impacts/{id}` with `{"archived":true}` archives the
record; use `false` to unarchive it. A missing, pending, or another user's id
returns 404. Repeating the same archive request is safe. A changed profile or
policy gets a new assessment row, leaving an older archived row untouched.

Each card and detail view shows the assessment's checklist: its stored action
items, described in [Action item checklist](#action-item-checklist-cs203-4) below.
Every entry in `actionItems` keeps a `text` field with the full action, so clients
written before the checklist still work. The source citation links to the stored
policy document. There is no bulk archive or automatic unarchive.

To try the UI, start the app with PostgreSQL and valid `OPENAI_API_KEY` and
`ANTHROPIC_API_KEY`, sign in as an applicant with a complete profile, and have an
admin import a matching Federal Register policy. On the applicant dashboard,
open that policy and select **See how this impacts me**. If the independent
review approves it, return to **Impact dashboard** to see its impact card. Click
**Read details**, then **Archive this assessment**; switch to **Archived** to
find it and unarchive it. If the AI services are unavailable, run the offline
integration test below to verify this workflow with saved assessment data.

With an applicant access token in `$TOKEN`, these requests exercise the API:

```bash
curl -s -H "Authorization: Bearer $TOKEN" \
  'http://localhost:8080/api/v1/impacts?page=0&size=20&sort=severity,desc'
curl -s -H "Authorization: Bearer $TOKEN" \
  'http://localhost:8080/api/v1/impacts/IMPACT_ID'
curl -s -X PATCH -H "Authorization: Bearer $TOKEN" \
  -H 'Content-Type: application/json' -d '{"archived":true}' \
  'http://localhost:8080/api/v1/impacts/IMPACT_ID'
curl -s -H "Authorization: Bearer $TOKEN" \
  'http://localhost:8080/api/v1/impacts?archived=true'
```

To check the human-review marker, sign in as a manager or admin and use that
role's token in `$MANAGER_TOKEN`. The pending-list response supplies the id:

```bash
curl -s -H "Authorization: Bearer $MANAGER_TOKEN" \
  'http://localhost:8080/api/v1/review/impacts'
curl -s -X PATCH -H "Authorization: Bearer $MANAGER_TOKEN" \
  -H 'Content-Type: application/json' \
  -d '{"severity":"HIGH","confidence":85,"summary":"Corrected impact summary", "analysis":["Why the source applies"],"actionItems":["Check the official source"],"missingInformation":[]}' \
  'http://localhost:8080/api/v1/review/impacts/IMPACT_ID'
```

Refetch `/api/v1/impacts/IMPACT_ID` with the owner applicant's token; it will
show the corrected summary, `reviewedByHuman: true`, and a higher version.

For an offline check against a local PostgreSQL test database, run
`mvn -Dtest=ImpactFeedIntegrationTest test` with `DATABASE_URL` and `JWT_SECRET`
set as in the regular test setup. It covers sorting, filters, citations, archive
history, account isolation, pending-review visibility and manager corrections.
`mvn -Dtest=AutoPublishIntegrationTest test` covers automatic reports: one
ingestion giving two users different reports, low-confidence and unverified results
held for review and published on approval, direct publication of a confident
verified `HIGH`, reuse on the policy page with no model call, untouched
human-reviewed rows, and the manager dashboard counts.
`node --test src/test/js/impact-feed.test.cjs` checks the dashboard rendering and
archive requests without calling AI services.

## Action item checklist (CS203-4)

Every published impact assessment now comes with a checklist: one action item for
each step in its recommended actions, in the same order. Applicants work through
the items under **Your checklist** on the dashboard, below the impact feed, and in
each assessment's detail view. Items live in the `action_items` table, and each one
is linked to the assessment that produced it, so progress survives reloads and
later corrections.

How items are made:

- Making items needs no extra AI call. When an assessment is published, either
  by an approved **See how this impacts me** or by a manager's correction, each
  recommended action becomes an item. Blank actions are skipped. An assessment
  held for review gets no items until it is published, and one with no
  recommended actions gets none.
- The title is the action text with spaces trimmed from both ends. Text longer
  than 120 characters is cut to 117 characters and an ellipsis, and the full text
  becomes the description.
- The priority follows the assessment's severity: `HIGH` gives `HIGH`, `MEDIUM` gives `MEDIUM`, and `LOW` and
  `NOT_APPLICABLE` give `LOW`.
- The deadline is the policy's effective date when the item is made. A policy
  without one gives an item without a deadline.
- Each item has a key: the SHA-256 of its text, ignoring case and runs of spaces,
  tabs and line breaks. The key is how a revised assessment recognises an action it
  already had.

When a manager corrects an assessment, or the same assessment is generated again,
each action with a new key gets a new item. An item still `PENDING` is removed if
its action is no longer listed. Items that are `IN_PROGRESS`, `COMPLETED` or
`DISMISSED` are always kept, and an action that already has an item keeps that
item unchanged. So a dismissed item never comes back, and a kept item keeps its
first priority and deadline.

An item is `PENDING`, `IN_PROGRESS`, `COMPLETED` or `DISMISSED`, and can move from
any status to any other. Completing it records `completedAt` and dismissing it
records `dismissedAt`; leaving either status clears its time. Sending the status an
item already has changes nothing. Completed and dismissed items leave the active
list but are never deleted: the **Completed** and **Dismissed** tabs list them,
and **Reopen** returns them to `PENDING`. An item still to do is `urgent` when its
deadline is at most seven days away, or has passed, and `overdue` once the deadline
has passed. The server works these out from the applicant's date on the test clock,
so moving the clock changes them.

Items follow the impact feed's visibility rule: they can be read or changed only
while their assessment is published to its owner. Items of an archived assessment
are kept but left out of the default list. `GET /api/v1/actions` returns only the
caller's own items. `PATCH` returns 404 for an id that does not exist or whose
assessment is hidden, and **403** for another user's item. The impact feed answers
404 for another user's assessment; the checklist answers 403 on purpose, because
the CS203-4 acceptance criteria require it.

`GET /api/v1/actions` returns a page of 50 items by default, and at most 100.
Without `status` it lists the active items (`PENDING` and `IN_PROGRESS`) of
unarchived assessments. `status` and `priority` can each be repeated, and an unknown
value returns 400. Items are ordered by deadline with undated items last, then by
priority (`HIGH` first), then by assessment and position; a `sort` parameter is
ignored. `PATCH /api/v1/actions/{id}` takes `{"status":"COMPLETED"}`. The impact
feed's `actionItems` are the same items, in every status, ordered by position.

With an applicant access token in `$TOKEN`:

```bash
curl -s -H "Authorization: Bearer $TOKEN" \
  'http://localhost:8080/api/v1/actions'
curl -s -H "Authorization: Bearer $TOKEN" \
  'http://localhost:8080/api/v1/actions?status=COMPLETED&status=DISMISSED&priority=HIGH'
curl -s -X PATCH -H "Authorization: Bearer $TOKEN" \
  -H 'Content-Type: application/json' -d '{"status":"IN_PROGRESS"}' \
  'http://localhost:8080/api/v1/actions/ITEM_ID'
curl -s -X PATCH -H "Authorization: Bearer $TOKEN" \
  -H 'Content-Type: application/json' -d '{"status":"COMPLETED"}' \
  'http://localhost:8080/api/v1/actions/ITEM_ID'
```

Migration `V30__create_action_items.sql` creates the table and backfills items for
every assessment its owner could already see, using the same rules written in SQL.
Restart Spring Boot to apply it.

To try it in the UI, publish an assessment as described under
[Published impact feed](#published-impact-feed), either through **See how this
impacts me** or by having a manager correct a held draft. On the applicant
dashboard, **Your checklist** lists its steps with their priority, deadline and
policy, and **Urgent** or **Overdue** tags where they apply. Use **Start**,
**Complete** and **Dismiss**, then open the **Completed** or **Dismissed** tab and
**Reopen** an item. **Read details** on the impact card shows every item of that
assessment with its status and the same buttons, and changes made there also appear
on the dashboard. To watch an item become urgent, use **Profile → Test clock** to
move your date to within a week of the policy's effective date.

To run the checks offline, set `DATABASE_URL` and `JWT_SECRET` as for the other
tests:

```bash
./mvnw test -Dtest='ActionItem*Test,ImpactFeedIntegrationTest,AccountPurgeServiceTests'
node --test src/test/js/action-checklist.test.cjs src/test/js/impact-feed.test.cjs
```

`ActionItemIntegrationTest` covers generation on publish, held and empty
assessments, owner isolation with 403 and 404, filters and ordering, every status
change, urgency against the test clock, archived assessments and manager
corrections. `ActionItemBackfillParityTest` runs the V30 backfill statement against
the Java rules for awkward text, and `ActionItemTextTest` covers the title and key
rules. The Node tests check the checklist's rendering, tabs, buttons and refetching
without a server.

Known limits:

- There are no recurring items and no dependencies between items.
- Deadlines come from the policy's effective date when an item is made. They are not
  recomputed if the rule is later amended. Likewise, a kept item keeps its priority
  when a correction changes the severity.
- A dismissed item is not brought back, even if the assessment is revised.
- The key lower-cases text with Java's rules in the app and with PostgreSQL's
  `lower()` in the V30 backfill. The two agree for the Latin, Cyrillic and other text
  the parity test checks, but not for a Greek final sigma or the Turkish dotted İ.
  A backfilled item containing those characters counts as a new action the next time
  its assessment is revised.

