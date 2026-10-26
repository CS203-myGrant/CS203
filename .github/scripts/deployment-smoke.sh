#!/usr/bin/env bash
set -euo pipefail

if [[ -z "${APP_BASE_URL:-}" || -z "${SMOKE_USER_EMAIL:-}" || -z "${SMOKE_USER_PASSWORD:-}" ]]; then
  echo 'APP_BASE_URL and smoke-account credentials must be configured.' >&2
  exit 1
fi
if [[ "$APP_BASE_URL" != https://* ]]; then
  echo 'APP_BASE_URL must use HTTPS.' >&2
  exit 1
fi
APP_BASE_URL=${APP_BASE_URL%/}

health=$(curl --fail --silent --show-error --retry 18 --retry-delay 10 --retry-all-errors \
  --max-time 10 "$APP_BASE_URL/actuator/health")
jq -e '.status == "UP"' <<< "$health" >/dev/null
echo 'Health endpoint is UP.'

login_payload=$(jq -cn --arg email "$SMOKE_USER_EMAIL" --arg password "$SMOKE_USER_PASSWORD" \
  '{userEmail: $email, userPassword: $password}')
login=$(curl --fail --silent --show-error --max-time 20 \
  --header 'Content-Type: application/json' \
  --data "$login_payload" "$APP_BASE_URL/api/v1/auth/login")
token=$(jq -er '.token | select(type == "string" and length > 0)' <<< "$login")

profile=$(curl --fail --silent --show-error --max-time 20 \
  --header "Authorization: Bearer $token" "$APP_BASE_URL/api/v1/users/me")
jq -e 'has("userId") and has("userEmail")' <<< "$profile" >/dev/null

unread=$(curl --fail --silent --show-error --max-time 20 \
  --header "Authorization: Bearer $token" "$APP_BASE_URL/api/v1/notifications/unread-count")
jq -e 'has("unread")' <<< "$unread" >/dev/null
echo 'Login and authenticated profile/notification API checks passed.'

headers_file=$(mktemp)
body_file=$(mktemp)
trap 'rm -f "$headers_file" "$body_file"' EXIT
set +e
curl --silent --show-error --no-buffer --max-time 5 \
  --dump-header "$headers_file" --output "$body_file" \
  --header "Authorization: Bearer $token" \
  --header 'Accept: text/event-stream' \
  "$APP_BASE_URL/api/v1/notifications/stream"
stream_exit=$?
set -e
http_status=$(awk 'NR == 1 {print $2}' "$headers_file")
if [[ "$http_status" != '200' || "$stream_exit" != '0' && "$stream_exit" != '28' ]] \
  || ! grep -q ':connected' "$body_file"; then
  echo 'Authenticated notification event-stream smoke check failed.' >&2
  cat "$headers_file" >&2
  exit 1
fi
echo 'Authenticated notification event stream is reachable.'
