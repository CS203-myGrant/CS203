# Rule stages and timeline

A rule reaches the Federal Register in stages, and each stage is a separate document: the
proposed rule, the final rule, perhaps a notice that delays it. myGRANT groups those documents
into a **policy rule** and keeps the rule's stage, effective date and history. When the stage or
the effective date changes, affected applicants get one alert that says what moved, such as
"effective date moved from 15 Sep 2026 to 1 Nov 2026", instead of another "New policy" alert.
Litigation and court orders are tracked separately, as a **disputed** flag set by news or by a
court order an admin records, and cleared by an admin once a court lifts the block. After that,
only a court order an admin records can set it again.
Rules are in `policy_rules`. `policy_rule_documents` says which rule each document is in and
how it was matched, `policy_document_identifiers` holds the dockets and RINs, and
`policy_rule_history` holds every step with its source.

How documents are linked:

- **Identifiers.** A Federal Register import takes the API's `docket_ids` and
  `regulation_id_numbers`. Every document's header is read as well, so documents stored earlier
  and the saved fixtures link the same way offline: `[DHS Docket No. ICEB-2025-0001]` and
  `RIN 1653-AA95`. Only the text before `SUMMARY:` is read, because the body of a long rule cites
  other rulemakings. A docket is kept without its agency label, as `ICEB-2025-0001`. An agency
  code written apart from its number, `Docket No: USCIS 2020-0019` as in the delay notice
  `2021-02665`, is read as `USCIS-2020-0019`.
- **News and other secondary sources.** Articles rarely quote a docket, so the links in an
  article's description count: `federalregister.gov/d/<number>`,
  `federalregister.gov/documents/<date>/<number>/…` and `regulations.gov/docket/<id>`. A labelled
  docket or a `RIN` written in the text counts too. For a secondary source posted to
  `POST /api/v1/ingestion/documents`, the same links in `sourceUrl` and `sourceReferences` count,
  which is how an admin records a court order (see
  [Record a court order](#record-a-court-order)).
- **Matching.** A document joins the rule whose primary documents share any one of its
  identifiers. When its identifiers point to more than one rule, it joins the rule matched by
  docket (else RIN, else document number) with `MEDIUM` confidence, flagged for review with the
  other rules named.
- **Title fallback.** Used only when no identifier matches. Both titles are lower-cased, a
  trailing part that names the document's stage is dropped (`; Proposed Rule`, `; Final Rule`,
  `; Correction`, `; Delay of Effective Date` and the like), the rest is split into words
  (hyphenated words such as `f-1` stay whole), and these stopwords are removed: a, an, and,
  are, as, at, be, by, for, from, in, into, is, its, of, on, or, than, that, the, their, this,
  to, under, with, within. The score is the Jaccard similarity: the words both titles share
  divided by all the words either has. At **0.8** or above the rule is suggested with `LOW`
  confidence and always flagged for review. For the fixture rule's 17-word title, one replaced
  word scores 0.89 and still matches; two replaced words score 0.79 and do not.
- **Conflicting identifiers beat a similar title.** The fallback never suggests a rule with a
  docket or RIN that differs from the document's. That is why `2020-20845`, the 2020 proposal
  with the same title on docket ICEB-2019-0006, starts a rule of its own.
- A primary document that matches nothing starts a new rule. A news item never does; it stays
  unlinked.
- **Review flag.** `needs_review` and `review_reason` on `policy_rule_documents`, and a
  `RuleLinkFlaggedEvent`, mark a title-only match, identifiers pointing to several rules, a
  stage that would move backwards, litigation reported on a link that is not certain, and
  litigation held because an admin cleared the rule's dispute (see **Disputed** below).
  Nothing consumes the event yet, so a flag sends no alert: anomaly review (CS203-90) will
  consume it. There is no UI and no list of flagged documents. A flagged document shows only in
  its rule's timeline, under `documents` with `needsReview` and `reviewReason`, and in
  `policy_rule_documents`.

Stages:

- A Federal Register `Proposed Rule` is `PROPOSED` and a `Rule` (final, interim final, a delay
  or a correction) is `FINAL`; either is `WITHDRAWN` when its action mentions a withdrawal.
  Notices and anything else carry no stage: they can start a rule but never move one.
- The stage only moves forward: `PROPOSED`, then `FINAL`, then `WITHDRAWN`. A proposal published
  after the rule became final joins the rule but is not applied, and is flagged for review.
- Only a final rule's effective date is the rule's. A document without an effective date never
  changes or clears the stored one.
- On a rule that is already `FINAL`, a final document moves the effective date only when it is
  about the date: its `ACTION:` line or the `; …` stage suffix of its title says delay,
  postponement, effective date or correction, such as `ACTION: Final rule; delay of effective
  date.` or a `; Correction` title. The `ACTION:` line is read from the stored header, before
  `SUMMARY:`, like the identifiers. Any other final document there is a **separate final rule**
  in the same rulemaking. DHS finalised the 2023 H-1B proposal (`2023-23381`) that way:
  `2024-01770`, effective 4 Mar 2024, covered registration, and `2024-29354`, effective 17 Jan
  2025, the rest. The second is recorded as its own `ADDITIONAL_FINAL_RULE` step with its own
  date, the rule keeps 4 Mar 2024, and applicants get its usual "New policy" alert, not a date
  move.
- A document about the date gives its **new date in its `DATES:` paragraph**, after `SUMMARY:`,
  and that is where it is read. The Federal Register's `effective_on` is never used for it: for
  the real delay notice `2021-02665` it is 8 Jan 2021, the date the delayed rule was published,
  while `DATES` says "the effective date of the final rule published January 8, 2021 … is
  delayed to December 31, 2021". The wordings read are "delayed to" or "until", "postponed to" or
  "until", and "new effective date is", "will be" or "of", followed by a date such as
  `December 31, 2021`, across line breaks. The noun is not read, because the same paragraph says
  "comments on this delay until March 10, 2021". A paragraph with no such date, or with two
  different ones, gives none.
- When a delay, postponement or change-of-date notice gives no new date that can be read, its
  step is recorded, the rule's date stays, no date alert is sent, and the document is flagged
  for review with its `DATES` text as the reason. A correction that gives none leaves the date
  alone without a flag, because most corrections fix text, not the date. The generic "New
  policy" alert never states `effective_on` for a document about the date.
- A document published before the one behind the current state, such as the proposal imported
  after the final rule, is recorded in the history in date order but changes nothing.
- `EFFECTIVE` is never stored. The timeline reports it once the rule is `FINAL`, not disputed,
  and the reader's date on the test clock (their own, else the admin's, else today) has reached
  the effective date. Reaching the date sends no alert: readers were told the date when it was
  set. A disputed rule is never shown as `EFFECTIVE`, because a court may have blocked it.
- **Disputed** is a separate flag. It is set when a secondary source linked by an identifier
  (`HIGH` confidence), a news item or a court order an admin recorded, reports litigation or a
  stay, and it never changes the primary stage. The litigation phrases include lawsuit, sued,
  sues, suing, injunction, enjoined, restraining order, vacated and blocked by a judge or court;
  the full list is `LitigationSignal.PHRASES`. "Court" or "judge" followed, within the next three
  words, by blocks, halts, pauses, postpones, stays or enjoins (or blocked, halted, paused,
  postponed, stayed, enjoined) counts too, so "Court Temporarily Blocks …" and "Federal Court
  Postpones …" match but "DHS postpones the effective date" and "Supreme Court stays out of
  student visa fight" do not. Matching ignores case, so
  "sue" is not on the list: it would hit the name Sue. A stay otherwise counts only as "stay of
  the rule", "stayed the rule" or "court stay", because "stay" alone is everywhere in immigration
  text ("length of stay"). A report on a `MEDIUM` or `LOW` link is flagged for review and not
  applied. Once set, disputed stays set until an admin clears it because a court lifted the
  block (see [Clear a dispute](#clear-a-dispute)). The `CHALLENGED` status is not used for
  rules.
- **After an admin clears a dispute, news never disputes the rule again.** News of the lift
  itself ("Appeals court lifts injunction on …") reads as litigation, and nothing tells it apart
  from news of a new block. So a report of litigation from the news feed, whatever its date, is
  held for review:
  - the rule stays not disputed;
  - its link has `needs_review` set, with a `review_reason` saying an admin cleared the dispute
    and giving the lift date;
  - `policy_rule_history` records a step with `applied = false`, the rule's state unchanged
    before and after, and the same text in `reason`;
  - it sends no alert and starts no reassessment, like any other document held for review.

  Only a court order an admin records with `POST /api/v1/ingestion/documents` (see
  [Record a court order](#record-a-court-order)) disputes the rule again, with the usual "status
  disputed" alert. It must be dated after the source that lifted the block: one dated on or
  before it is about the block that was lifted, so it is held the same way. After a further
  clearing, news is held again. A rule no admin has cleared is unaffected: news still disputes
  it.

  News and admin court orders are both `SECONDARY` documents with the same fields, so neither
  the fields nor the wording tell them apart. The endpoint does: only a document posted to
  `POST /api/v1/ingestion/documents`, which is ADMIN only, counts as an admin's. The news feed,
  Federal Register imports and the backfill never do. This is not stored, so a court order
  posted while no rule matched it, and linked later by the backfill, counts as news.

A meaningful change raises one `PolicyChangedEvent`, handled after the change commits, with
the old and new stage, effective date and disputed flag and the source document. It is one of:

- a primary document moves a staged rule forward (`PROPOSED` to `FINAL`, or to `WITHDRAWN`);
- a primary document changes the effective date (on a rule already final, only one about the
  date), or gives a final rule its first one;
- a linked secondary source marks the rule disputed.

Nothing is raised for a rule's first document (its usual "New policy" alert covers it), for a
separate final rule in the same rulemaking (the same), for a document that changes nothing, for
an older document arriving late, for importing a document again, by the backfill, or when an
admin clears a dispute. Repeats
stay idempotent three ways: a document already in a rule is never tracked again, each history
row has a unique key made from its source and the state before and after, and the event is
raised only when that row is actually written, which marks it `alerted`.

Alerts and reassessment:

- A change alert goes to the same applicants as a "New policy" alert, through the same
  `notifications` rows, so the inbox, the live stream and the immediate and digest emails pick
  it up unchanged. It belongs to the document that caused the change: the new Federal Register
  document, or the news item or court order behind a dispute. That document gets no "New
  policy" alert, so each reader gets one alert per change within `UNIQUE (user_id, policy_id)`. A reader who
  already holds an alert for that document, because the event arrived twice, is skipped and
  logged; any other clash fails loudly.
- The wording, with the usual "Action needed: review your …" suffix when the rule touches a
  field the reader has filled in:
  - `Rule update for F-1, J-1: moved from proposed to final, effective 15 Sep 2026: <title>`
  - `Rule update for F-1, J-1: effective date moved from 15 Sep 2026 to 1 Nov 2026: <title>`
  - `Rule update for F-1, J-1: status disputed: litigation or a court stay reported by U.S. District Court for the District of Massachusetts. The rule is final, but its effective date of 15 Sep 2026 is in question: <title>`

  A dispute never says the rule is in effect. For a rule with no final effective date it ends
  "The official status is still proposed".
- After the change commits, a background thread reassesses the users that
  `RuleReassessmentScope` names, against the document that caused the change. By default that
  is everyone with a saved assessment of an earlier document of the same rule, whose advice is
  now stale. It goes through `PolicyRecommendationStore`, the path behind **See how this impacts
  me**, so a result is published only when the judge approves it, otherwise it waits in the
  review queue, and its checklist items follow. The import never waits for it, and the import's
  own preview assessment is unchanged. Separately, every new official document is assessed for every matching profile after ingestion (CS203-153).

Documents stored before this change are linked by a backfill that runs in the background once
the app has started. It takes unlinked documents, primary first and oldest first, applies the
same rules, marks its history `BACKFILL` and sends no alerts and no reassessments. Running it
again changes nothing, and a failure is logged without stopping the app. It also retries news
that matched no rule when it arrived. Set `RULES_BACKFILL_ON_STARTUP=false` to skip it.

### The timeline endpoint

`GET /api/v1/policies/{id}/timeline` returns the history of the rule that policy document `{id}`
is in. Any document in the rule works, news and court orders included. As with
`GET /api/v1/policies/{id}`, any signed-in user may call it. Steps are oldest first by source
publication date, each with its source, and each step's previous values are those of the step
before.

- `primaryStage` is the stored stage from primary sources.
- `currentStage` is `EFFECTIVE` once a `FINAL` rule's effective date is on or before `asOf`, the
  reader's date, and the rule is not disputed; a derived `TOOK_EFFECT` step shows when.
  Otherwise it is `primaryStage`.
- `disputed` is set by a secondary source that reported litigation or a stay, which
  `disputedBy` names. `effectiveDatePassedWhileDisputed` is `true` when a disputed rule's
  effective date is on or before `asOf`: the UI should say "scheduled for 15 Sep 2026; blocked
  or in dispute", not "in effect". A disputed rule has no `TOOK_EFFECT` step.
- When an admin clears the dispute, that is a `DISPUTED` step back to `false`, whose source is
  the court order or news item that lifted the block (`policyId` is `null`). `disputedBy` is
  `null` again and, once the date has come, `currentStage` is `EFFECTIVE`. The `TOOK_EFFECT`
  step then comes after the clearing step and carries its date, because the rule could not take
  effect while it was blocked. If the rule is disputed again, `disputedBy` names the newest report.
- A report held for review after a clearing is not a step, so it never shows as a dispute and
  the rule stays `EFFECTIVE` once its date has passed. Like every flagged document, it is listed
  under `documents` with `"needsReview": true` and its `reviewReason`.
- A separate final rule in the same rulemaking is an `ADDITIONAL_FINAL_RULE` step. Its own
  effective date is in `ownEffectiveDate`; the step's `effectiveDate` and `previousEffectiveDate`
  stay the rule's, so it never reads as a date move. `ownEffectiveDate` is `null` on every other
  step.

Errors use the usual `ApiError` body:

- 404 `Policy document 57 was not found` when no policy has the id;
- 404 `Policy document 57 is not part of a tracked rule` for a document in no rule, such as news
  that matched none;
- 400 for an id that is not a number, and 401 without a token.

An undisputed rule: the real H-1B proposal and its two final rules, read on 6 Nov 2026 (long
titles and URLs shortened):

```json
{
  "ruleId": 2,
  "title": "Modernizing H-1B Requirements, Providing Flexibility in the F-1 Program, and Program …",
  "primaryStage": "FINAL",
  "currentStage": "EFFECTIVE",
  "asOf": "2026-11-06",
  "effectiveDate": "2024-03-04",
  "disputed": false,
  "effectiveDatePassedWhileDisputed": false,
  "disputedBy": null,
  "identifiers": [{"kind": "DOCKET", "value": "USCIS-2023-0005"}, {"kind": "RIN", "value": "1615-AC70"}],
  "documents": [
    {"policyId": 3, "externalId": "2023-23381", "sourceType": "PRIMARY", "title": "Modernizing H-1B …",
     "publishedOn": "2023-10-23", "matchMethod": "NEW_RULE", "confidence": "HIGH",
     "matchedOn": "DOCKET USCIS-2023-0005; RIN 1615-AC70", "needsReview": false, "reviewReason": null},
    {"policyId": 4, "externalId": "2024-01770", "sourceType": "PRIMARY",
     "title": "Improving the H-1B Registration Selection Process and Program Integrity",
     "publishedOn": "2024-02-02", "matchMethod": "DOCKET_AND_RIN", "confidence": "HIGH",
     "matchedOn": "DOCKET USCIS-2023-0005; RIN 1615-AC70", "needsReview": false, "reviewReason": null},
    {"policyId": 5, "externalId": "2024-29354", "sourceType": "PRIMARY", "title": "Modernizing H-1B …",
     "publishedOn": "2024-12-18", "matchMethod": "DOCKET_AND_RIN", "confidence": "HIGH",
     "matchedOn": "DOCKET USCIS-2023-0005; RIN 1615-AC70", "needsReview": false, "reviewReason": null}
  ],
  "steps": [
    {"observedOn": "2023-10-23", "recordedAt": "2026-11-06T10:09:53.816824Z", "derived": false,
     "primaryStage": "PROPOSED", "previousPrimaryStage": null,
     "effectiveDate": null, "previousEffectiveDate": null, "ownEffectiveDate": null, "disputed": false,
     "changes": ["STAGE"],
     "source": {"policyId": 3, "externalId": "2023-23381", "sourceType": "PRIMARY",
                "url": "https://www.federalregister.gov/documents/2023/10/23/2023-23381/…",
                "title": "Modernizing H-1B …", "agency": "Homeland Security Department",
                "publishedOn": "2023-10-23"}},
    {"observedOn": "2024-02-02", "recordedAt": "2026-11-06T10:09:54.559147Z", "derived": false,
     "primaryStage": "FINAL", "previousPrimaryStage": "PROPOSED",
     "effectiveDate": "2024-03-04", "previousEffectiveDate": null, "ownEffectiveDate": null,
     "disputed": false, "changes": ["STAGE", "EFFECTIVE_DATE"],
     "source": {"policyId": 4, "externalId": "2024-01770", "sourceType": "PRIMARY",
                "url": "https://www.federalregister.gov/documents/2024/02/02/2024-01770/…",
                "title": "Improving the H-1B Registration Selection Process and Program Integrity",
                "agency": "Homeland Security Department", "publishedOn": "2024-02-02"}},
    {"observedOn": "2024-03-04", "recordedAt": null, "derived": true,
     "primaryStage": "FINAL", "previousPrimaryStage": "FINAL",
     "effectiveDate": "2024-03-04", "previousEffectiveDate": "2024-03-04", "ownEffectiveDate": null,
     "disputed": false, "changes": ["TOOK_EFFECT"],
     "source": {"policyId": 4, "externalId": "2024-01770", "…": "as in the step above"}},
    {"observedOn": "2024-12-18", "recordedAt": "2026-11-06T10:09:55.435901Z", "derived": false,
     "primaryStage": "FINAL", "previousPrimaryStage": "FINAL",
     "effectiveDate": "2024-03-04", "previousEffectiveDate": "2024-03-04", "ownEffectiveDate": "2025-01-17",
     "disputed": false, "changes": ["ADDITIONAL_FINAL_RULE"],
     "source": {"policyId": 5, "externalId": "2024-29354", "sourceType": "PRIMARY",
                "url": "https://www.federalregister.gov/documents/2024/12/18/2024-29354/…",
                "title": "Modernizing H-1B …", "agency": "Homeland Security Department",
                "publishedOn": "2024-12-18"}}
  ]
}
```

A disputed rule: the D/S proposal and final rule, with the court order that postponed the final
rule recorded as below, read on 6 Nov 2026. The effective date has passed, but `currentStage` is
`FINAL` and there is no `TOOK_EFFECT` step:

```json
{
  "ruleId": 3,
  "title": "Establishing a Fixed Time Period of Admission and an Extension of Stay Procedure for …",
  "primaryStage": "FINAL",
  "currentStage": "FINAL",
  "asOf": "2026-11-06",
  "effectiveDate": "2026-09-15",
  "disputed": true,
  "effectiveDatePassedWhileDisputed": true,
  "disputedBy": {"policyId": 10, "externalId": "d-mass-1-26-cv-13799-2026-09-14", "sourceType": "SECONDARY",
                 "url": "https://www.bu.edu/isso/2026/09/15/federal-ruling-postpones-implementation-of-dhs-rule-to-eliminate-d-s/",
                 "title": "Federal court postpones the duration of status final rule",
                 "agency": "U.S. District Court for the District of Massachusetts", "publishedOn": "2026-09-14"},
  "identifiers": [{"kind": "DOCKET", "value": "ICEB-2025-0001"}, {"kind": "RIN", "value": "1653-AA95"}],
  "documents": [
    {"policyId": 6, "externalId": "2025-16554", "sourceType": "PRIMARY", "title": "Establishing …",
     "publishedOn": "2025-08-28", "matchMethod": "NEW_RULE", "confidence": "HIGH",
     "matchedOn": "DOCKET ICEB-2025-0001; RIN 1653-AA95", "needsReview": false, "reviewReason": null},
    {"policyId": 9, "externalId": "2026-14439", "sourceType": "PRIMARY", "title": "Establishing …",
     "publishedOn": "2026-07-17", "matchMethod": "DOCKET_AND_RIN", "confidence": "HIGH",
     "matchedOn": "DOCKET ICEB-2025-0001; RIN 1653-AA95", "needsReview": false, "reviewReason": null},
    {"policyId": 10, "externalId": "d-mass-1-26-cv-13799-2026-09-14", "sourceType": "SECONDARY",
     "title": "Federal court postpones the duration of status final rule", "publishedOn": "2026-09-14",
     "matchMethod": "FR_DOCUMENT", "confidence": "HIGH", "matchedOn": "FR_DOCUMENT 2026-14439",
     "needsReview": false, "reviewReason": null}
  ],
  "steps": [
    {"observedOn": "2025-08-28", "…": "PROPOSED, from 2025-16554"},
    {"observedOn": "2026-07-17", "…": "PROPOSED to FINAL, effective 2026-09-15, from 2026-14439"},
    {"observedOn": "2026-09-14", "recordedAt": "2026-11-06T10:09:58.227275Z", "derived": false,
     "primaryStage": "FINAL", "previousPrimaryStage": "FINAL",
     "effectiveDate": "2026-09-15", "previousEffectiveDate": "2026-09-15", "ownEffectiveDate": null,
     "disputed": true, "changes": ["DISPUTED"],
     "source": {"policyId": 10, "externalId": "d-mass-1-26-cv-13799-2026-09-14", "sourceType": "SECONDARY",
                "url": "https://www.bu.edu/isso/2026/09/15/federal-ruling-postpones-implementation-of-dhs-rule-to-eliminate-d-s/",
                "title": "Federal court postpones the duration of status final rule",
                "agency": "U.S. District Court for the District of Massachusetts", "publishedOn": "2026-09-14"}}
  ]
}
```

Read on 1 Sep 2026 instead, the D/S rule has `"effectiveDatePassedWhileDisputed": false`; its
`currentStage` is `FINAL` either way. For a `NEW_RULE` document, `matchedOn` lists the
identifiers it started the rule with.

### Try the rule timeline locally

With the app running and `$ADMIN_TOKEN` and an F-1 applicant's `$TOKEN` set as in
[Run locally in WSL](../README.md#run-locally):

```bash
# The 2025 proposal starts a rule. F-1 and J-1 applicants get "New policy that may affect F-1, J-1: …".
curl -s -X POST -H "Authorization: Bearer $ADMIN_TOKEN" \
  http://localhost:8080/api/v1/ingestion/federal-register/2025-16554
# The 2026 final rule joins it by docket and RIN. The same applicants get one alert:
# "Rule update for F-1, J-1: moved from proposed to final, effective 15 Sep 2026: …".
curl -s -X POST -H "Authorization: Bearer $ADMIN_TOKEN" \
  http://localhost:8080/api/v1/ingestion/federal-register/2026-14439
# The timeline. Use either import's "ingestion": {"policyId": …}.
curl -s -H "Authorization: Bearer $TOKEN" http://localhost:8080/api/v1/policies/POLICY_ID/timeline
```

Then:

- Import `2026-14439` again: the response says `"alreadyIndexed":true`, and there is no new
  alert and no new step.
- Import `2020-20845`: it has the same title but its own docket and RIN, so it starts its own
  rule, whose timeline has one step.
- Move your own date with the test clock, under profile settings or with
  `PUT /api/v1/test-clock/me` and `{"date":"2026-09-01"}`: the timeline's `currentStage` is
  `FINAL`. From `2026-09-15` on it is `EFFECTIVE`, until the court order below is recorded.
- Import `2023-23381`, `2024-01770` and `2024-29354` in that order. The H-1B rule moves from
  proposed to final, effective 4 Mar 2024, with one "Rule update" alert. The second final rule
  adds an `ADDITIONAL_FINAL_RULE` step with `"ownEffectiveDate": "2025-01-17"`, the rule keeps
  4 Mar 2024, and H-1B and F-1 applicants get its "New policy" alert.
- Import `2020-24259`, `2021-00183`, `2021-02665` and `2021-27714` in that order, the 2020 H-1B
  registration rulemaking (RIN 1615-AC61). H-1B applicants get "New policy …" for the proposal,
  then "moved from proposed to final, effective 9 Mar 2021", "effective date moved from 9 Mar
  2021 to 31 Dec 2021" (read from the delay notice's `DATES`) and "moved from final to
  withdrawn". The timeline ends `WITHDRAWN`, with no `TOOK_EFFECT` step.
- To see a dispute from news, import the synthetic news feed the tests use. Its articles are
  made up, so do this only on a local database:

  ```bash
  curl -s -X POST -H "Authorization: Bearer $ADMIN_TOKEN" -H 'Content-Type: application/xml' \
    --data-binary @src/test/resources/newsrss/synthetic-rule-coverage-feed.xml \
    http://localhost:8080/api/v1/ingestion/news-rss/saved
  ```

  The injunction article links to `2026-14439` and to the regulations.gov docket, so the rule
  becomes `disputed`, its `primaryStage` stays `FINAL`, and applicants get the "status disputed"
  alert. The explainer article joins the rule without changing it, and the third article
  matches no rule.

The effective-date move is tested with the real delay notice `2021-02665` and, for the D/S
rule, which has no real one, a synthetic delay notice,
`src/test/resources/federal-register/synthetic-2026-99950-delay.txt`. Like a real one, it
gives its new date only in `DATES`; its stubbed `effective_on` is its own publication date.
`RuleLifecycleIntegrationTest` runs the whole sequence, the three H-1B documents, the RIN
1615-AC61 rulemaking and the court order below. It needs a database that does not already hold
these documents, as in CI, and is skipped otherwise.

### Record a court order

Court orders are not published in the Federal Register, and news about them often carries no
docket, RIN or Federal Register link, so the news linking can miss them. None of nine real
headlines about the order that postponed the D/S rule linked to it. An admin records such an
order with the existing `POST /api/v1/ingestion/documents` (ADMIN role) as a `SECONDARY` source:

- Put a link to the rule's Federal Register document, `https://www.federalregister.gov/d/<number>`,
  or its `https://www.regulations.gov/docket/<id>`, in `sourceReferences` (or `sourceUrl`). That
  links the source to the rule with `HIGH` confidence.
- Say what the court did in the `title` or `content`, in words such as "court postpones", "judge
  blocks", "enjoined" or "injunction" (see **Disputed** above). Without them the source joins the
  rule but does not mark it disputed.
- Use `sourceUrl` for where the order or a report of it can be read, `agency` for the court,
  `publicationDate` for the date of the order, and `visaType` and `status` as for any policy
  (`CHALLENGED` fits). `content` is a short summary in your own words.

The rule becomes disputed, `disputedBy` names the source, and applicants affected by the rule get
one "status disputed" alert. The source gets no "New policy" alert, and posting it again changes
nothing. This is also the only way to dispute a rule again once an admin has cleared its
dispute: news no longer can. The order must then be dated after the source that lifted the
block; an earlier one is held for review. The real order that postponed the D/S rule
(2026-14439) on 14 Sep 2026 is saved as
[`src/test/resources/court-orders/d-mass-1-26-cv-13799-2026-09-14.json`](../src/test/resources/court-orders/d-mass-1-26-cv-13799-2026-09-14.json),
which `RuleLifecycleIntegrationTest` posts:

```json
{
  "externalId": "d-mass-1-26-cv-13799-2026-09-14",
  "title": "Federal court postpones the duration of status final rule",
  "agency": "U.S. District Court for the District of Massachusetts",
  "visaType": "F-1,J-1",
  "status": "CHALLENGED",
  "sourceType": "SECONDARY",
  "publicationDate": "2026-09-14",
  "sourceUrl": "https://www.bu.edu/isso/2026/09/15/federal-ruling-postpones-implementation-of-dhs-rule-to-eliminate-d-s/",
  "sourceReferences": [
    "https://www.federalregister.gov/d/2026-14439"
  ],
  "content": "On 14 September 2026 the U.S. District Court for the District of Massachusetts postponed, …"
}
```

```bash
curl -s -X POST -H "Authorization: Bearer $ADMIN_TOKEN" -H 'Content-Type: application/json' \
  --data-binary @src/test/resources/court-orders/d-mass-1-26-cv-13799-2026-09-14.json \
  http://localhost:8080/api/v1/ingestion/documents
```

The response is `201` with the new `policyId`, which also works as `{id}` for the timeline.
Import `2025-16554` and `2026-14439` first, or the source has no rule to join.

### Clear a dispute

Nothing clears a dispute by itself: news that a block was lifted reads much like news of the
block. When a court lifts the block, for example on appeal, an admin clears it with
`POST /api/v1/policies/{id}/dispute/clear` (ADMIN role; anyone else gets 403). `{id}` is any
document in the rule, as for the timeline. Do not record the lifting order with
`POST /api/v1/ingestion/documents` as well: its wording ("vacated the injunction") reads as
litigation, so it would be held for review, or, dated after the source you clear with, dispute
the rule again.

1. Check that the order really lifts the block on this rule, and find its date.
2. Send a `reason` in your own words and a `source` with the same fields as a court order:
   `externalId` (your id for it, such as the case number and date), `title`, `agency` (the
   court), `sourceUrl` (where it can be read, http or https) and `publicationDate` (the date of
   the order). The date cannot be after your test-clock date, or before the report that
   disputed the rule, because the timeline orders steps by date.
3. Read the timeline to check it.

```bash
curl -s -X POST -H "Authorization: Bearer $ADMIN_TOKEN" -H 'Content-Type: application/json' \
  -d '{"reason": "The First Circuit vacated the postponement; DHS says the rule now applies.",
       "source": {"externalId": "ca1-26-1702-2026-11-02",
                  "title": "Appeals court lifts the postponement of the duration of status rule",
                  "agency": "U.S. Court of Appeals for the First Circuit",
                  "sourceUrl": "https://www.ca1.uscourts.gov/opinions/26-1702",
                  "publicationDate": "2026-11-02"}}' \
  http://localhost:8080/api/v1/policies/POLICY_ID/dispute/clear
```

That order is made up, to show the request. The response is `200` with the rule's id, its
unchanged `primaryStage` and `effectiveDate`, `"disputed": false`, and `clearedBy`, `clearedAt`,
`reason` and `source` as recorded. `policy_rule_history` keeps the clearing as an append-only
`ADMIN` step with `actor_email`, `reason`, `recorded_at` and the source. Only the flag changes:
the stage and effective date stay, `EFFECTIVE` is worked out when the timeline is read, no alert
is sent and no other rule changes. A rule that is not disputed gives `409`; a missing or invalid
field, or a date out of range, gives `400`.

From then on, news never disputes the rule again. A report of litigation from the news feed,
including news of the lift itself, is held for review as described under **Disputed** above.
If a held report describes a new block, check it, then record the court order with
`POST /api/v1/ingestion/documents`, dated after the source you cleared with. That disputes the
rule again with the usual "status disputed" alert, and an admin can clear that too.

To see a held report locally, clear the D/S rule's dispute as above, then import the synthetic
feed the tests use. Its one article is made up, so do this only on a local database:

```bash
curl -s -X POST -H "Authorization: Bearer $ADMIN_TOKEN" -H 'Content-Type: application/xml' \
  --data-binary @src/test/resources/newsrss/synthetic-lift-feed.xml \
  http://localhost:8080/api/v1/ingestion/news-rss/saved
```

The article, dated 3 Nov 2026, links to `2026-14439` and says an appeals court lifted the
injunction. The rule stays not disputed, no alert is sent, and the timeline lists the article
under `documents` with `"needsReview": true` but not as a step.

### Known limits

- Court orders do not appear in the Federal Register, so a court-blocked rule shows as in
  effect until an admin records the order, or, for a rule no admin has cleared, until linked
  news reports it. Record orders as above.
- Once an admin has cleared a dispute, news can no longer dispute the rule. If the rule is
  blocked again and only the news reports it, the rule stays shown as in effect until an admin
  reviews the held report and records the court order. Nothing points admins to held reports
  yet: they are flagged, but there is no review list until CS203-90, so an admin finds them only
  in the rule's timeline (`documents` with `needsReview`) or in `policy_rule_documents`.
- Clearing sends no alert and starts no reassessment, so applicants are not told the rule is in
  effect again, and assessments saved while it was disputed are not refreshed.
- Whether a final document is about the date is read from its `ACTION:` line and title. A
  manually ingested primary document without an `ACTION:` line or a `; …` suffix counts as a
  separate final rule, not a date move.
- A new date is read from `DATES` only in the wordings above. A delay notice worded otherwise
  ("the effective date is now …") moves nothing and is flagged for review. A correction that
  changes the date in other words, such as "is corrected to read", moves nothing and is not
  flagged.
- If the second of two final rules is imported before the first, the rule's `effectiveDate` stays
  the second one's, because an older document arriving late changes nothing. The steps are still
  worked out in publication order, so they show the first final rule setting the date and the
  later one as `ADDITIONAL_FINAL_RULE`, and disagree with `effectiveDate`.

