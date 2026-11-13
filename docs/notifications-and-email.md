# Notifications and email

In-app policy alerts, the test clock, alert emails and the Google welcome email.

## Test policy change notifications

Importing a policy alerts every applicant whose visa category it affects. An alert is
additionally flagged **action required** when the policy touches profile information that
applicant has already filled in — currently the program end date, the academic level, the
upcoming travel date, and the employer or employment status.
That mapping is a deterministic keyword heuristic like the visa classifier, **not** an LLM
decision, and it is a prompt to review rather than a legal determination.

Three behaviours surprise people:

- Alerts are created **at ingestion time**. An account registered after a document was
  imported receives nothing for it.
- Importing the same document twice alerts nobody the second time; the response reports
  `"alreadyIndexed":true` and no new alert is generated.
- A document that moves a rule already stored, such as `2026-14439` after `2025-16554`, gets a
  "Rule update" alert saying what moved instead of "New policy". See
  [Rule stages and timeline](rule-timeline.md).

### 1. Sign in as an applicant whose profile has dates

Use the account registered under [Authentication and JWT](api.md#authentication-and-jwt) above.
It is F-1 with both an academic level and a program end date, which is what the
action-required flag needs: the flag only fires when the field the policy touches is
already populated. An account registered without those fields still receives the alert,
but it arrives unflagged.

Registering an email that already exists returns HTTP 400 with a generic registration
error that does not disclose whether the email is registered. Invalid JSON or dates return HTTP 400, and invalid login
credentials return HTTP 401 without revealing whether the email exists.

### 2. Import a document that affects that category

Sign in at `http://localhost:8080/` as the [admin account](../README.md#admin-account), open
**RAG inspector** in the top navigation, and import one of these Federal Register document numbers:

- `2025-16554` — F-1 and J-1; flags both the academic level and the program end date, so
  one alert names two fields.
- `2026-18631` — H-1B only; flags the employer and employment status for an H-1B account
  that has either saved under **Your profile**. F-1 and J-1 accounts receive nothing: the
  title names no visa type, and F-1/J-1 appear only in a footnote comparison.
- `2020-20845` — F-1 and J-1; also flags both fields.
- `2025-20932` — F-1 and J-1; an information collection notice about school forms, so the
  alert arrives with no flag.

The unread badge on the **Notifications** link updates as soon as the import succeeds.

### 3. Read the inbox

Click **Notifications** in the sidebar, or read the same data from the terminal:

```bash
curl -s http://localhost:8080/api/v1/notifications \
  -H "Authorization: Bearer $TOKEN" | jq '.content'
```

Each entry reports whether it needs attention and which profile fields it refers to:

```json
{
  "id": 7,
  "policyId": 5,
  "message": "New policy that may affect F-1: ... Action needed: review your program end date.",
  "read": false,
  "actionRequired": true,
  "affectedFields": ["program end date"]
}
```

### 4. Mark an alert as read

```bash
curl -s -X POST http://localhost:8080/api/v1/notifications/7/read \
  -H "Authorization: Bearer $TOKEN" | jq
curl -s http://localhost:8080/api/v1/notifications/unread-count \
  -H "Authorization: Bearer $TOKEN"
```

Read state is stored in PostgreSQL, so it survives a reload. Each account can only read
or mark its own alerts; another account's notification ID returns HTTP 404.

### If the inbox is empty

This is usually correct behaviour rather than a fault:

- **A fresh clone has an empty inbox.** PostgreSQL runs locally per developer, so alerts
  exist only for documents imported on your own machine.
- **The account registered after the import.** Register first, then import.
- **The document was already imported.** Use a different document number.
- **The visa category does not match**, or the account is not an applicant. Only
  `APPLICANT` users are alerted.

To re-run the demo with a document you already imported, delete it and import it again.
The alerts cascade with the policy; the vector chunks are removed separately because the
`policy_chunks` table has no foreign key to `policy_documents`: 

```bash
docker exec -i policy-impact-postgres psql -U policy_user -d policy_impact <<'SQL'
DELETE FROM policy_chunks WHERE metadata->>'externalId' = '2025-16554';
DELETE FROM policy_documents WHERE external_id = '2025-16554';
SQL
```

### Test clock (temporary)

The test clock replays alerts without deleting anything by hand. It changes the date the
app treats as today, and a policy counts as published only once that date reaches its
publication date:

- **Profile → Test clock** moves only your own date and re-matches only your inbox.
- **Admin → Monitored sources → Test clock** moves every applicant's date, replacing any
  date they set themselves, and re-matches every applicant's inbox.

When the date moves, each affected inbox is brought in line with it. Alerts for policies
published after the new date are withdrawn. Every official policy published by then for
the applicant's **current** visa category (F-1, J-1 or H-1B) that they have no alert for
is alerted now and pushed to the live inbox. To watch one arrive, click **Day before** next
to a policy, then **Publish day**. After you change your visa category, setting the clock
alerts you about the policies for the new category. **Back to the real date** returns to
normal and restores alerts withdrawn earlier.

The clock only replays policies that are in the database. To test a date nobody has
imported for, leave **Also import Federal Register policies** ticked when the admin sets
the date. It searches the Federal Register for rules, proposed rules and notices from
DHS, State and Labor that mention F-1, J-1 or H-1B and were published in the 30 days up
to the date. It imports up to 20 of them before updating inboxes. Documents the classifier
finds are not about a supported visa type are skipped. Each new import downloads and
embeds the full text, then queues impact assessments for matching profiles in the
background. Expect embedding to take a minute. Imported policies stay in the database
after the clock moves.

Dates are kept in memory, so a restart returns everyone to the real date. Alerts created
this way are real rows, so they are also emailed when email alerts are configured. Turn
the feature off with `TEST_CLOCK_ENABLED=false`, which removes the
`/api/v1/test-clock/me` and `/api/v1/admin/test-clock` endpoints and hides both panels.

## Email alerts

Alerts can also arrive by email. Each applicant sets two modes under **Email alerts** on
**Your profile**: one for critical alerts and one for everything else. Each mode is
**Email me right away**, **Include in my daily digest** or **Do not email me**. The
defaults are right away for critical alerts and the digest for the rest. In-app alerts are
never affected.

Since CS203-91, how an alert is emailed follows the user's own AI impact assessment,
not the keyword rules behind the in-app alert (those still decide the in-app
"action needed" flag). The rule lives in `AlertBucket` and `ImpactEmailPlanner`:

| Assessment the user can see | Email setting used | With the default settings |
| --- | --- | --- |
| `HIGH` | Critical alerts | Emailed right away |
| `MEDIUM` or `LOW` | Other alerts | In the daily digest |
| `NOT_APPLICABLE` | None | Never emailed |
| None yet, or the AI was unavailable | Other alerts, digest only | In the digest as "We couldn't assess this one yet" |
| Held for review (not visible yet) | None until a reviewer approves or corrects it | Then as above |

- An assessment counts once its owner can see it: published straight away, or approved
  or corrected by a reviewer. A held one sends nothing until then.
- Only changes that also have an in-app alert are emailed, so news never emails anyone
  and the test clock still applies.
- **At most one email per user and policy, plus one more** if a later visible version is
  `HIGH` after the first email went out below `HIGH` (for example after a correction, or
  after a retry assesses a change that went in the digest unassessed). The database
  enforces this with a unique (user, policy, kind) key on `impact_email_alerts` (V54). An
  item not yet in an email follows the latest visible assessment instead, so a correction
  before the digest replaces it rather than adding an email.
- **Right away**: within about 30 seconds, one email per item. It holds the policy title,
  the assessment's plain-English summary, its date and a **View in myGRANT** link that
  opens the assessment (`#impact=<id>`) after sign-in, WorkOS included. A change we could
  not assess yet links to its in-app alert instead.
- **Daily digest**: at 08:00 America/New_York, one email listing every new item from the
  buckets set to the digest. A user with nothing new gets no email.
- Emails never contain the full analysis, only the summary and the link.
- Every email has **Manage email settings** and **Unsubscribe** links, plus
  `List-Unsubscribe` and `List-Unsubscribe-Post: List-Unsubscribe=One-Click` headers.
  Unsubscribing turns both modes off. Opening the link only shows a confirm button,
  because mail scanners open links; the button (or a mail client's one-click POST) does
  the change.
- Each item goes out in at most one email. An unassessed change is emailed only if its
  in-app alert is still unread when the digest is built.
- Only recent changes are emailed: assessments that became visible in the last 24 hours
  (48 for the digest), and unassessed alerts from the last 48 hours. So the first deploy,
  or turning emails on, does not mail anyone their old inbox, and a digest missed while
  the app was down rolls into the next one. Emails sent before CS203-91 carried the
  in-app alerts themselves; `notifications.email_delivery_id` keeps that history.

Endpoints: `GET`/`PUT /api/v1/profiles/me/email-preferences`, the public
`GET`/`POST /api/v1/email/unsubscribe?token=...` page, `GET /api/v1/notifications/{id}`,
and the ADMIN-only `POST /api/v1/admin/email/digest/run`.

### Configuration

| Variable | Default | Purpose |
|---|---|---|
| `RESEND_API_KEY`, `RESEND_FROM_EMAIL` | none | Shared with the welcome email. Without both, the email jobs log one warning and do nothing (this is the case in CI). |
| `APP_BASE_URL` | `http://localhost:8080` | Base of every link in an email. Use the public HTTPS address in a deployment; mail clients only offer one-click unsubscribe for HTTPS links. |
| `EMAIL_JOBS_ENABLED` | `true` | `false` runs no email jobs at all. The test suite sets this. |
| `EMAIL_DISPATCH_INTERVAL` | `30s` | How often immediate alerts are queued and due emails sent. |
| `EMAIL_DIGEST_CRON` | `0 0 8 * * *` | Spring cron for the digest: second, minute, hour, day, month, weekday. |
| `EMAIL_DIGEST_ZONE` | `America/New_York` | Zone for the cron, the digest date and the times shown in emails. |
| `EMAIL_MAX_ATTEMPTS` | `4` | Attempts per email, counting the first. |
| `EMAIL_RETRY_BACKOFF` | `1m,5m,15m` | Wait before each retry; the last value repeats. |
| `EMAIL_IMMEDIATE_LOOKBACK` | `24h` | Oldest alert emailed right away. |
| `EMAIL_DIGEST_LOOKBACK` | `48h` | Oldest alert a digest collects. |
| `EMAIL_STUCK_AFTER` | `10m` | A send claimed this long ago is treated as interrupted and retried. |
| `EMAIL_BATCH_SIZE` | `50` | Most emails one dispatch run sends. |
| `EMAIL_ALLOW_UNVERIFIED_RECIPIENTS` | `false` | Local demos only; see below. |

### Who receives email

Only verified addresses receive alerts, but there is no email-verified flag yet
(CS203-25). Until there is, an account counts as verified once it has signed in with
WorkOS (**Continue with Google**), because WorkOS rejects unverified addresses. Accounts
registered with a password are unverified, and their settings page says so. Signing in
once with **Continue with Google** using the same address links the account and verifies
it. Inactive and deleted accounts never receive email.

`EMAIL_ALLOW_UNVERIFIED_RECIPIENTS=true` also emails password accounts, for local demos.
Never enable it on a shared deployment: anyone can register with someone else's address.

### Try it locally

1. Add `RESEND_API_KEY` and `RESEND_FROM_EMAIL` to `.env`. With Resend's shared test sender
   (`onboarding@resend.dev`) and no verified domain, Resend only delivers to your own
   account's address and rejects anything else with HTTP 403. The address
   `delivered@resend.dev` always reports delivery, so it is the safest recipient for
   demos. Accounts at `@example.com` addresses would bounce.
2. Register an applicant with `delivered@resend.dev` (or your own address). Then either
   sign in with **Continue with Google**, or set `EMAIL_ALLOW_UNVERIFIED_RECIPIENTS=true`
   to use a password account.
3. Start Spring Boot and import a document as in
   [Test policy change notifications](#test-policy-change-notifications). Once the
   applicant's assessment of it is visible (see
   [Published impact feed](impact-feed.md#published-impact-feed)), a `HIGH` one is emailed within
   `EMAIL_DISPATCH_INTERVAL`; check **Emails** in the Resend dashboard.
4. Build the digest without waiting for 08:00, signed in as the
   [admin account](../README.md#admin-account):

   ```bash
   ADMIN_TOKEN=$(curl -s -X POST http://localhost:8080/api/v1/auth/login \
     -H "Content-Type: application/json" \
     -d "{\"userEmail\": \"$ADMIN_EMAIL\", \"userPassword\": \"$ADMIN_PASSWORD\"}" \
     | python3 -c "import sys,json; print(json.load(sys.stdin)['token'])")
   curl -s -X POST http://localhost:8080/api/v1/admin/email/digest/run \
     -H "Authorization: Bearer $ADMIN_TOKEN" | jq
   ```

   ```json
   {"digestDate": "2026-10-24", "digestsCreated": 1, "emailsSent": 1,
    "emailsRetrying": 0, "emailsFailed": 0, "emailsSkipped": 0}
   ```

   Each user gets at most one digest per date, so a second run on the same day only
   reaches users who had none yet. To run the demo again, pass an earlier date with
   `?date=YYYY-MM-DD`. Future dates are rejected, because they would take that day's slot
   and suppress the real digest.
5. Inspect the outbox:

   ```bash
   docker exec -i policy-impact-postgres psql -U policy_user -d policy_impact -c \
     "SELECT kind, digest_date, status, attempts, last_error, sent_at FROM email_deliveries ORDER BY created_at DESC LIMIT 10;"
   ```

The automated tests use a fake provider and never call Resend. The `mygrant.email`
integration tests run against the local PostgreSQL and delete the rows they create.

### Retries and failures

- Creating an alert never calls email code. A Resend outage cannot delay or fail an
  alert, its live update in open pages, or an import.
- Each email is claimed with a conditional update that also counts the attempt. It is
  sent with no database transaction or lock held, and its outcome is recorded afterwards.
  An overlapping run finds nothing to claim.
- Rate limits (429), server errors, timeouts and `409 concurrent_idempotent_requests` are
  retried after 1, 5 and 15 minutes; after 4 attempts the email is `FAILED`. Other 4xx
  errors, such as a bad key or an unverified sender, are not retried.
  `409 invalid_idempotent_request` is logged at error level.
- The delivery's UUID is sent as Resend's `Idempotency-Key`, so a retry after a timeout
  cannot deliver twice. Resend keeps keys for 24 hours, so keep the whole retry schedule
  well inside that.
- Before its first attempt, an email may still drop alerts the user has since switched
  off. After any attempt its contents are frozen: a retry sends exactly the same alerts,
  or the whole email is skipped if the user opted out or can no longer receive email.
- A `FAILED` immediate email keeps its alert, which is then never emailed again. It does
  not fall back into the digest, because Resend may have delivered it.
- An email left `SENDING` for `EMAIL_STUCK_AFTER`, for example after a crash, is retried
  with the same key, or marked `FAILED` if it has no attempts left.
- `last_error` stores only the HTTP status and Resend's error name, never the recipient,
  the body or the key.

### Single-instance assumption

The jobs are built for one running instance. Spring's single scheduler thread runs them
one at a time. A second instance would not double-send, because claims are conditional,
digests are unique per user and date, and Resend drops repeated idempotency keys. It would
still scan the same tables on every run and log its own warnings, so add a scheduler lock
(for example ShedLock) before running several instances. All users share one digest time
and zone; there is no per-user time zone.


## Google account welcome emails

WorkOS creates the account and emits a signed `user.created` webhook. The backend
verifies that webhook and sends one welcome email through Resend. Add these values
to the local `.env` file:

```env
WORKOS_WEBHOOK_SECRET=whsec_...
RESEND_API_KEY=re_...
RESEND_FROM_EMAIL=MyGrant <onboarding@your-verified-domain.example>
```

Configure this public HTTPS endpoint in the WorkOS Dashboard and subscribe it to
the `user.created` event:

```text
https://your-public-host.example/api/v1/webhooks/workos
```

WorkOS cannot deliver webhooks to `localhost`. For local testing, expose port 8080
with a temporary HTTPS tunnel such as ngrok and use the generated HTTPS URL in the
WorkOS Dashboard. The Resend sender must be a verified sending domain or address.

