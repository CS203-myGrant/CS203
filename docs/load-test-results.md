# Load test results (CS203-177)

How to run the test: [loadtest/README.md](../loadtest/README.md). The script is
`loadtest/read-paths.k6.js`. It makes the requests an applicant's dashboard makes on load, and
calls no AI endpoint.

## Runs

| Date | Target | Paths | Load | Requests | Failed | p95 all | p95 policies | p95 impact feed | p95 actions | p95 notifications | p95 unread count | Thresholds |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 2026-11-12 | **Local**, not staging | Dashboard after PR #82 (below) | 5 VUs, 3 min (30 s up, 2 min steady, 30 s down) | 1,801 (9.9/s) | 0 (0.00%) | 51.8 ms | 61.8 ms | 9.6 ms | 7.8 ms | 6.6 ms | 5.4 ms | All passed |
| 2026-11-12 | **Local**, not staging | Before PR #82: `/api/v1/impacts`, every list at 20 per page | 5 VUs, 3 min | 1,821 (10.1/s) | 0 (0.00%) | 35.3 ms | 42.5 ms | 12.9 ms | 10.5 ms | 8.0 ms | 6.2 ms | All passed |

The thresholds are: under 1% failed requests, and a p95 under 800 ms for every endpoint.

## 2026-11-12, local, after PR #82

**Staging was not tested.** No staging URL or test account was available, so the test ran
against a local instance:

- branch `feature/CS203-157-resilience` at `42ceff9`, merged with main after PR #82;
- an Apple M4 with 16 GB;
- a pgvector 16 container on the same machine, through Docker Desktop 29.7.2;
- the app started as in the README, with AI assessments, email jobs and the scheduler switched off;
- data seeded with `loadtest/seed-local.sql`: 40 policies, 20 published assessments (in both
  `policy_recommendations` and `impact_assessments`), 60 checklist items and 40 alerts for one account.

The paths are the ones the dashboard requests on load, at its page sizes:

| Endpoint tag | Request |
| --- | --- |
| `policies` | `GET /api/v1/policies?page=0&size=100` |
| `impacts` | `GET /api/v1/impact-assessments?page=0&size=20` (the saved-assessment feed) |
| `actions` | `GET /api/v1/actions?page=0&size=50` |
| `notifications` | `GET /api/v1/notifications?size=50` |
| `unread_count` | `GET /api/v1/notifications/unread-count` |

Local numbers have no network latency and no Render resource limits, so they are a lower bound,
not a prediction for staging.

Full summary (k6 0.54.0):

```
checks.........................: 100.00% 1800 out of 1800
http_req_duration..............: avg=12.79ms  min=1.07ms  med=4.78ms   p(90)=47.3ms   p(95)=51.78ms max=119ms
  { endpoint:actions }.........: avg=4.83ms   min=1.94ms  med=4.57ms   p(90)=6.75ms   p(95)=7.75ms  max=14.79ms
  { endpoint:impacts }.........: avg=5.98ms   min=2.38ms  med=5.59ms   p(90)=8.83ms   p(95)=9.56ms  max=16.19ms
  { endpoint:notifications }...: avg=3.94ms   min=1.44ms  med=3.59ms   p(90)=5.88ms   p(95)=6.6ms   max=17.13ms
  { endpoint:policies }........: avg=45.91ms  min=20.37ms med=47.3ms   p(90)=55.95ms  p(95)=61.76ms max=119ms
  { endpoint:unread_count }....: avg=3.1ms    min=1.07ms  med=2.79ms   p(90)=4.83ms   p(95)=5.36ms  max=8.55ms
http_req_failed................: 0.00%   0 out of 1801
http_reqs......................: 1801    9.943254/s
iterations.....................: 360     1.987547/s
data_received..................: 87 MB   480 kB/s
```

### Observations

- **The policy list is the slowest and largest response.** It is about 200 KB for the 40
  seeded policies, because every policy in the list includes its full source text, and the
  dashboard asks for 100 per page. Its p95 is about 6 times the other endpoints'. The gap will
  grow over a real network and with a real policy count.
  - Leaving the text out of the list would be the first thing to change if staging is slow.
    The detail endpoint already returns it.
- **The saved-assessment feed** (`/api/v1/impact-assessments`) adds the source-freshness lookup
  (CS203-175). That is one read of the enabled sources per page, and its p95 stayed under 10 ms.
- **Every response stayed under 120 ms, with no errors.** The rest of the dashboard's read path
  is not a concern at this load.

## 2026-11-12, local, before PR #82

This was the first run, on the same machine and data, before PR #82 moved the dashboard to the
saved-assessment feed:

- it requested `GET /api/v1/impacts` for the feed;
- it used 20 per page for the policies, actions and notifications;
- its seed had no `impact_assessments` rows.

It is kept for comparison. Its policy list p95 was lower because it fetched 20 policies, not 100.

## To do: staging

Run the same command against staging with a dedicated test account, and add a row above. Keep
5 VUs unless the staging owner agrees to more.
