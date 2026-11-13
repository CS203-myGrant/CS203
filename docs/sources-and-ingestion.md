# Policy sources and ingestion

How policies get into myGRANT: the connector contract, the Federal Register, Visa Bulletin, USCIS and news sources, visa classification and background impact assessment.

## Source connector contract

Source collectors implement `DataConnector<Request, Fetched, Transformed, Result>`
in `mygrant.ingestion.connector`. The contract separates fetching, transforming,
deduplicated persistence, and health. `SourceIdentity` gives every connector a
stable source ID and `PRIMARY` or `SECONDARY` type. `ConnectorFetch` records the
source URL, retrieval time, and SHA-256 content hash; `ConnectorHealth` reports the last attempt,
success, and error. A failed import throws rather than silently storing data.

`FederalRegisterClient` remains the HTTP adapter; `FederalRegisterIngestionService`
implements `DataConnector` because it owns classification and persistence. It
reuses the existing visa classifier and `PolicyIngestionService` for filtering
and content-hash deduplication. Visa Bulletin reuses its parser and upsert store,
and tracks the SHA-256 hash of each month's imported HTML so an unchanged page
does not rewrite its entries. Its persisted health record includes the failure stage.
Their HTTP endpoints and source-specific responses remain unchanged. The USCIS
saved-result and news RSS connectors use the same stages; news reports `SECONDARY`.
Admins can inspect Federal Register's in-memory import health at
`GET /api/v1/ingestion/federal-register/health`; it resets when the app restarts.
`GET /api/v1/ingestion/status` returns health for every installed connector to admins.

### Scheduled Federal Register sync and failed documents

`SourceSyncScheduler` syncs each enabled policy source on its own interval, and an admin can run
one with `POST /api/v1/ingestion/sync`. A Federal Register run searches from the date of the last
completed run to today and imports each document found. One document failing no longer fails the
run and holds back every later import:

- The failure is logged with the document number, the source and the error, recorded in
  `sync_failures`, and the run carries on with the rest. The run's summary line in the log, its
  `error` in the sync response and its `lastError` in `GET /api/v1/ingestion/status` name the
  documents that failed.
- The run still completes, so the search window moves past the failed document. It is not lost:
  every later run retries the `RETRYING` documents in `sync_failures`, whether or not the search
  still finds them. One that imports becomes `RESOLVED`. After 5 failures in a row
  (`mygrant.ingestion.max-document-attempts`) it becomes `PERMANENTLY_FAILED`, and later runs
  skip it even when the search finds it again.
- A search result that the classifier finds is about no supported visa type is not a failure:
  it is counted in the summary, not recorded and not retried.
- If the search itself fails, the whole run fails as before and the window does not move.

Admins list failed documents, newest failure first, with `GET /api/v1/ingestion/sync-failures`;
filter with `?status=RETRYING`, `PERMANENTLY_FAILED` or `RESOLVED`. Once the cause of a
permanent failure is fixed, import the document with
`POST /api/v1/ingestion/federal-register/{documentNumber}`. Its `sync_failures` entry stays
`PERMANENTLY_FAILED` as the record of what the sync tried.

### USCIS processing-time observations

The admin's existing “USCIS processing-time data” row in the prototype is a fixed
demo value. The actual connector accepts a saved, admin-reviewed result at
`POST /api/v1/ingestion/uscis-processing-times/saved` and reports persisted health
at `GET /api/v1/ingestion/uscis-processing-times/health` and the shared status route.
Use the JSON shape in `src/test/resources/uscis/saved-i765-result.json`: provide the
official `https://egov.uscis.gov/processing-times/` URL, the date the result was
observed, the selected form category, and copied result-card text containing the
form, office, and “80% of cases are completed within …” figure. The form and office
are extracted from that text; the category is supplied by the reviewing admin.
The checked-in fixture is a representative transcription for offline tests, not a
downloaded official response.

The public current figure is an **80th percentile over completed cases in the
previous six months**, not a median or a prediction for an individual case.
`processing_time_snapshots` stores its number and original `DAYS` or `MONTHS` unit;
`median_value` remains `NULL`. Its observation date, official URL, copied excerpt,
content hash, and import time preserve provenance. A repeat capture returns the existing row;
a different saved result on the same date adds a new row instead of changing
history. For CS203-89/105, treat missing median as missing,
do not turn months into exact days without an explicit conversion rule, and do not
combine this office-specific 80th percentile with USCIS's separate national,
fiscal-year median series as if they were the same measure.

The official processing-time page returned HTTP 403 during implementation and
USCIS's public developer catalog did not list a processing-time API. Therefore
this connector does not schedule live downloads or claim its saved observations
were fetched automatically. An admin must verify the copied result and category
against USCIS before importing it. The shared health endpoint describes imports,
not the freshness of a live feed; scheduling and health UI belong to CS203-78.

### USCIS processing forecast (CS203-89)

Applicants can save `i765FilingDate` or `i539FilingDate` in their existing profile.
The signed-in user's `GET /api/v1/forecast/processing-times?formType=I-765`
returns a decision-window projection from up to 12 latest distinct observation dates
for one exact form, category and office. If multiple categories or offices exist,
the endpoint returns `UNAVAILABLE` with `AMBIGUOUS_SERIES` and `availableSeries`.
The user can choose the matching series on the dashboard; the app saves it with
`PUT /api/v1/forecast/processing-times/selection` and reuses it for later
forecasts and alerts. Callers can also supply exact `formCategory` and `office`
query parameters. It never averages unlike series. I-140 uses the existing
`i140FilingDate` if matching snapshots exist.

The method converts months to days with 30.44 days per month. It takes the median
of the selected *published 80th-percentile durations* as a central source figure.
Its uncertainty margin is 7 days plus twice the median absolute deviation of those
durations, half any rise from oldest to newest, and 7 days for each observation
below five. The projected upper date is filing date plus the central source figure
and margin; the start of the displayed range subtracts the margin from the
published median if present, or from the 80th-percentile figure otherwise. At
least two dated snapshots are required. This band is **uncalibrated**: it is not
an 80% probability interval. `medianDecisionDate` stays null unless at least two
snapshots contain an actual published case median. The response includes the
snapshot dates, method, and reasons the margin widened. It returns `UNAVAILABLE`
and null dates for missing or future filing dates, unsupported forms, no history,
insufficient history, or ambiguous series. Individual case decisions can differ.

Only I-765 is compared with `optStartDate`; only I-539 is compared with
`visaExpiryDate`. If the projected upper date is later, an in-app timing alert is
saved in `forecast_deadline_alerts`, returned only to its owner by
`GET /api/v1/forecast/deadline-alerts`, and shown in the dashboard timeline and
Notifications section. The key
`(user_id, form_type, deadline_type)` prevents duplicates; a changed forecast
updates that row, and a safe or unavailable forecast clears it. Evaluation runs
after a profile save, series selection, new USCIS snapshot import, and daily at 02:15 server
time. Loading or refreshing the dashboard only reads the forecast and cannot
create alerts. Account purge removes these alerts.

For a **synthetic local demo**, sign in as an admin and import
`src/test/resources/uscis/saved-i765-result.json` through the saved-result API.
Copy that file and change only `observedOn` from `2026-09-15` to `2026-09-22`,
then import the copy. Sign in as an applicant, save `i765FilingDate` and an
`optStartDate` earlier than the projected upper date in the profile, then call:

```bash
curl -H "Authorization: Bearer $USER_TOKEN" \
  'http://localhost:8080/api/v1/forecast/processing-times?formType=I-765'
```

Open **Impact dashboard → USCIS processing timeline** to see the same window and
timing warning. This demo data is a saved sample; it is not a live USCIS forecast.

### Secondary news RSS

Admins can pull the selected [Guardian US immigration RSS feed](https://www.theguardian.com/us-news/usimmigration/rss)
with `POST /api/v1/ingestion/news-rss/pull`, or import saved RSS XML with
`POST /api/v1/ingestion/news-rss/saved` (`Content-Type: application/xml`).
`GET /api/v1/ingestion/news-rss/health` and the shared `/status` route show its
last success or fetch, parse, or persistence failure. The pull has five-second
connection and ten-second response timeouts; HTTP 429 fails only this import.
There is no scheduled pull in this story.

The connector accepts only Guardian article URLs and selects articles whose
title or summary matches the existing F-1, J-1, or H-1B classifier. It stores
the RSS **summary**, URL, publisher, and publication date in `policy_documents`
with `source_type=SECONDARY` and status `UNKNOWN`; the summary is not the full
article or an official policy statement. Canonical article URLs give repeat
imports a stable ID, and the shared content-hash path prevents reindexing.
Changed summaries at an existing URL are left as duplicates rather than
rewriting previously indexed evidence. Secondary documents remain searchable,
but do not send official policy-change notifications, and the evidence ranker
keeps primary sources above secondary ones. The saved XML fixture is a
representative RSS sample for offline tests; it contains synthetic article text.

## Import and classify a Federal Register document

The Federal Register API does not require an API key. The import endpoint downloads a
document's metadata and complete raw text, detects supported visa categories from policy
language, and passes the result through the normal PostgreSQL and pgvector ingestion path.
The complete source text is retained in PostgreSQL and indexed in pgvector, including
passages after the first 6,000 characters. Chunks are embedded in batches of 50 to
keep embedding requests manageable. A long rule can take longer and use more embedding
tokens than a short one.

After deploying this change, an admin can repair Federal Register documents already
stored with the old 6,000-character limit. This uses the saved full text and does not
download it again, recreate a rule, or send policy alerts:

```bash
curl -s -X POST http://localhost:8080/api/v1/ingestion/federal-register/reindex \
  -H "Authorization: Bearer $ADMIN_TOKEN" | jq
```

The response gives `documentsReindexed` and `chunksIndexed`. Run it once with a working
OpenAI embedding key. It processes each document in its own transaction and can be
rerun safely if it stops partway through. This repairs search and citations only.
`ProfileImpactAssessmentService` still sends at most the first 12,000 characters to
the assessment model; choosing relevant chunks for that prompt remains separate work.

Restart Spring Boot after pulling new code, then import a document by its Federal Register
document number:

```bash
curl -s -X POST \
  http://localhost:8080/api/v1/ingestion/federal-register/2026-14439 \
  -H "Authorization: Bearer $ADMIN_TOKEN" | jq
```

The response includes classification flags and the signals that caused each match:

```json
{
  "documentNumber": "2026-14439",
  "affectsF1": true,
  "affectsJ1": true,
  "affectsH1b": false,
  "detectedVisaTypes": ["F-1", "J-1"],
  "matchedSignals": ["F-1:academic student", "J-1:exchange visitor"],
  "ingestion": { "chunksIndexed": 6, "alreadyIndexed": false },
  "impactAssessment": null
}
```

### Enable profile-specific urgency classification

Add OpenAI and Anthropic API keys only to the ignored `.env` file:

```env
OPENAI_API_KEY=...
OPENAI_MODEL=gpt-6.1-sol
ANTHROPIC_API_KEY=...
ANTHROPIC_JUDGE_MODEL=claude-haiku-5-5
```

Restart Spring Boot after changing `.env`. After a new Federal Register or manual
policy import commits, assessments run in the background for every profile whose
visa type matches the stored visa tags. Federal Register tags come from the
classifier; manual tags come from the ingestion request. The importing admin's
identity does not determine who is assessed. The import response keeps the legacy
`impactAssessment` field as `null` and no longer waits for model generation.

Only populated, consented profile facts go into the prompt. Names, emails and user
IDs are omitted; an employer name is represented only by `employerProvided: true`.
The prompt includes policy metadata, visa matches, structured restriction metadata
and at most the first 12,000 characters of source text. OpenAI returns structured
`HIGH`, `MEDIUM`, `LOW` or `NOT_APPLICABLE` urgency, confidence, reasons, actions
and a review flag. Claude independently evaluates the candidate.

Results are saved in `impact_assessments` with the captured profile version,
provider and model. A model review request, an unverified candidate or confidence
below `IMPACT_REVIEW_CONFIDENCE_THRESHOLD` (default `60`, integer percent) produces
`PENDING_REVIEW`. A score equal to the threshold does not by itself require review.
The visibility-filtered repository query returns only completed assessments that
do not need review or have been approved; pending/rejected results are excluded.
Since CS203-153, a visible assessment is also published to the applicant's feed; see
[Impact feed and action checklist](impact-feed.md).

An OpenAI failure saves `status = UNAVAILABLE` with null urgency and confidence,
without undoing ingestion or stopping other profiles. Reprocessing skips existing
rows, including failures; retry execution is separate work. Unchanged re-imports
create neither new assessments nor new vectors. Profile edits do not trigger
regeneration. Concurrent processing can make duplicate model calls, but the
unique user/policy/profile-version constraint allows only one saved result.

Set `IMPACT_ASSESSMENTS_ENABLED=false` to disable background assessments. Work is
queued in the application's in-memory executor after commit; a process restart
can lose queued work, and submission failures are logged without failing ingestion.
A durable job queue and recovery sweep are not implemented here.

Run the focused tests with Docker available:

```bash
./mvnw '-Dtest=IngestionAssessmentIntegrationTest,IngestionAssessmentListenerTests,ImpactAssessmentPersistenceTest,ProfileImpactAssessmentServiceTests,FederalRegisterConnectorTest,PolicyIngestionServiceTests' test
```

These tests mock AI providers; they do not verify live credentials or model quality.
Confidence is model-reported, not a calibrated probability, and a passing independent
judge is not proof of legal accuracy.

The chunk count can vary. Importing the same unchanged document again returns
`"alreadyIndexed":true` and `"chunksIndexed":0`; this means the existing vectors were
reused, not that no vectors exist.

Confirm the metadata was stored:

```bash
curl -s http://localhost:8080/api/v1/policies \
  -H "Authorization: Bearer $TOKEN" | jq '.content[] | {id, externalId, title}'
```

Then confirm that its vector chunks are retrievable for F-1:

```bash
curl -s -X POST http://localhost:8080/api/v1/rag/query \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
    "question": "What does the fixed period of admission policy change for F-1 students?",
    "visaType": "F-1"
  }' | jq
```

### How visa classification works

Classification is a deterministic keyword heuristic, **not** an LLM or RAG decision.
It checks the document title first. If the title names a supported category, only its
title matches are used; this avoids classifying a rule as H-1B merely because H-1B
appears in background comparisons. If the title has no supported signal, it scans the
full text. It reports the matched signals and the booleans `affectsF1`, `affectsJ1`,
and `affectsH1b`. Supported categories are F-1, J-1, and H-1B. An unmatched document
returns HTTP 400 and is not ingested. A match is a routing hint, **not a legal
determination**; review ambiguous policies before sending personalised alerts.

For document `2026-14439`, the title signals `academic student` and `exchange visitor`
produce F-1 and J-1 matches, while `affectsH1b` is false. The saved policy stores the
full source text, and all of it is searchable in pgvector. RAG retrieves those chunks
after classification.


### Import a Visa Bulletin

Sign in as an admin, then open **Admin → Visa Bulletin**. The panel shows
import health and saved cutoff entries for a publication month. Open the official page in a
normal browser, save its complete HTML, and use **Import saved HTML**. Applicants and managers cannot
access the panel or its API. Every `/api/v1/ingestion` request needs an admin JWT; an
applicant's `$TOKEN` gets 403. Sign in as the [admin account](../README.md#admin-account) with
`.env` loaded:

```bash
ADMIN_TOKEN=$(curl -s -X POST http://localhost:8080/api/v1/auth/login \
  -H "Content-Type: application/json" \
  -d "{\"userEmail\": \"$ADMIN_EMAIL\", \"userPassword\": \"$ADMIN_PASSWORD\"}" \
  | python3 -c "import sys,json; print(json.load(sys.stdin)['token'])")
```

The server's direct HTML and PDF downloads currently receive HTTP 403 from the Department
of State, so the scheduled source sync and bulk sync skip Visa Bulletin. The old direct
fetch API remains available for a future permitted access route but is not used by the
admin interface. Import the saved official page when each new monthly bulletin is
published; publication is not necessarily on the first day of the month. The import detects the publication
month from the page heading and rejects unrelated or incomplete HTML. This
checks content and layout; it cannot prove where an uploaded file originated.
The equivalent API request is:

```bash
curl -X POST http://localhost:8080/api/v1/ingestion/visa-bulletin/saved \
  -H "Authorization: Bearer $ADMIN_TOKEN" -H "Content-Type: text/html" \
  --data-binary @saved-bulletin.html
```

Check the import and saved rows (`YYYY-MM` is the publication month):

```bash
curl http://localhost:8080/api/v1/ingestion/visa-bulletin/health \
  -H "Authorization: Bearer $ADMIN_TOKEN"
curl "http://localhost:8080/api/v1/ingestion/visa-bulletin/entries/2026-10?size=200" \
  -H "Authorization: Bearer $ADMIN_TOKEN"
```

The entries come back as a [page](api.md#pagination), 20 at a time unless you pass `size`; a
month has about 150.

The importer saves the four family/employment cutoff charts in
`visa_bulletin_entries`. A repeated import updates the same monthly keys. If
the page is missing a chart or its layout changes, no partial chart is saved
and health becomes `FAILED`; the last successful import time remains visible.
This is immigrant preference data. The bulletin's family `F1` category is
different from an `F-1` student visa. A student's F-1 status alone does not
enable tracking.

Applicants may optionally turn on **Visa Bulletin updates** in Your profile after saving
an immigrant preference category (such as `EB-2`), an exact chargeability chart column,
and a priority date, with consent to use all three fields. This is separate from F-1,
OPT and H-1B status. The applicant dashboard shows the latest saved chart cutoffs and,
after a later monthly import changes their row, an in-app alert. The first imported month
is a baseline; importing an older month does not announce a historical change. Re-imports
update the same alert rather than duplicating it. Turning tracking off deletes its alerts.
These are published cutoff changes, not eligibility or filing advice. The user must check
the official bulletin and USCIS chart selection. `GET` and `PUT`
`/api/v1/visa-bulletin/tracking` are authenticated applicant-only endpoints; `PUT` accepts
`{"enabled":true}` or `{"enabled":false}`.


### Moving from Ollama to OpenAI embeddings

Policy chunks used to be embedded with Ollama `mxbai-embed-large`. They are now embedded
with OpenAI `text-embedding-3-small`, asked for the same 1024 dimensions, so the
`policy_chunks` table is unchanged. The two models' vectors cannot be compared, so if
your database has policies imported before the switch, re-embed them once as an admin:

```bash
curl -X POST http://localhost:8080/api/v1/ingestion/embeddings/rebuild \
  -H "Authorization: Bearer $ADMIN_TOKEN"
```

The response reports `chunksReembedded`. Policies, alerts and citations are untouched;
only the vectors are replaced. Until you run it, RAG answers match against the old
vectors and are unreliable. Ollama is no longer needed.
