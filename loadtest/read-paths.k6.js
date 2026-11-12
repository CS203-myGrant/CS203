// CS203-177: gentle load test of the read paths an applicant's dashboard calls.
// It calls no AI endpoint (no assessments, questions or evaluations), so it costs nothing in
// provider fees and leaves the rate limits alone. See loadtest/README.md and
// docs/load-test-results.md.
//
// Required: LOADTEST_BASE_URL, LOADTEST_EMAIL, LOADTEST_PASSWORD (a password-login test account).
// Optional: LOADTEST_VUS (default 5), LOADTEST_STEADY (default 2m).
import http from 'k6/http';
import { check, fail, group, sleep } from 'k6';

const BASE_URL = (__ENV.LOADTEST_BASE_URL || '').replace(/\/$/, '');
const VUS = Number(__ENV.LOADTEST_VUS || 5);
const STEADY = __ENV.LOADTEST_STEADY || '2m';

export const options = {
  scenarios: {
    dashboard_readers: {
      executor: 'ramping-vus',
      startVUs: 0,
      stages: [
        { duration: '30s', target: VUS },
        { duration: STEADY, target: VUS },
        { duration: '30s', target: 0 },
      ],
      gracefulRampDown: '10s',
    },
  },
  thresholds: {
    http_req_failed: ['rate<0.01'],
    http_req_duration: ['p(95)<800'],
    'http_req_duration{endpoint:policies}': ['p(95)<800'],
    'http_req_duration{endpoint:impacts}': ['p(95)<800'],
    'http_req_duration{endpoint:actions}': ['p(95)<800'],
    'http_req_duration{endpoint:notifications}': ['p(95)<800'],
    'http_req_duration{endpoint:unread_count}': ['p(95)<800'],
  },
  summaryTrendStats: ['avg', 'min', 'med', 'p(90)', 'p(95)', 'max'],
};

/** Signs in once; every virtual user shares the token (it lasts 15 minutes by default). */
export function setup() {
  if (!BASE_URL || !__ENV.LOADTEST_EMAIL || !__ENV.LOADTEST_PASSWORD) {
    fail('Set LOADTEST_BASE_URL, LOADTEST_EMAIL and LOADTEST_PASSWORD');
  }
  const response = http.post(`${BASE_URL}/api/v1/auth/login`,
    JSON.stringify({ userEmail: __ENV.LOADTEST_EMAIL, userPassword: __ENV.LOADTEST_PASSWORD }),
    { headers: { 'Content-Type': 'application/json' }, tags: { endpoint: 'login' } });
  if (response.status !== 200 || !response.json('token')) {
    fail(`Login failed with HTTP ${response.status}`);
  }
  return { token: response.json('token') };
}

// The requests the dashboard makes on load (app/mygrant-prototype.html), with the same sizes.
const PATHS = [
  ['policies', '/api/v1/policies?page=0&size=100'],
  ['impacts', '/api/v1/impact-assessments?page=0&size=20'],
  ['actions', '/api/v1/actions?page=0&size=50'],
  ['notifications', '/api/v1/notifications?size=50'],
  ['unread_count', '/api/v1/notifications/unread-count'],
];

/** One dashboard load, then a 1-3 s pause, as a reader would. */
export default function (data) {
  group('dashboard', () => {
    for (const [endpoint, path] of PATHS) {
      const response = http.get(`${BASE_URL}${path}`, {
        headers: { Authorization: `Bearer ${data.token}` },
        tags: { endpoint },
      });
      check(response, { [`${endpoint} is 200`]: r => r.status === 200 });
    }
  });
  sleep(1 + Math.random() * 2);
}
