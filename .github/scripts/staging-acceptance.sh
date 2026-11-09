#!/usr/bin/env bash
set -euo pipefail

required=(APP_BASE_URL STAGING_ACCEPTANCE_EMAIL STAGING_ACCEPTANCE_PASSWORD)
for name in "${required[@]}"; do
  if [[ -z "${!name:-}" ]]; then
    echo "$name must be configured." >&2
    exit 1
  fi
done
if [[ "$APP_BASE_URL" != https://* ]]; then
  echo 'APP_BASE_URL must use HTTPS.' >&2
  exit 1
fi
APP_BASE_URL=${APP_BASE_URL%/}

tmpdir=$(mktemp -d)
trap 'rm -rf "$tmpdir"' EXIT

login_payload=$(jq -cn --arg email "$STAGING_ACCEPTANCE_EMAIL" \
  --arg password "$STAGING_ACCEPTANCE_PASSWORD" \
  '{userEmail: $email, userPassword: $password}')
login_status=$(curl --silent --show-error --max-time 20 \
  --output "$tmpdir/login.json" --write-out '%{http_code}' \
  --header 'Content-Type: application/json' --data "$login_payload" \
  "$APP_BASE_URL/api/v1/auth/login")

if [[ "$login_status" != '200' ]]; then
  if [[ "${VERIFY_PERSISTENCE_ONLY:-false}" == 'true' ]]; then
    echo 'Could not log in for persistence verification; account or credentials are incorrect.' >&2
    exit 1
  fi
  register_payload=$(jq -cn --arg email "$STAGING_ACCEPTANCE_EMAIL" \
    --arg password "$STAGING_ACCEPTANCE_PASSWORD" \
    '{userName:"Staging Acceptance", userEmail:$email, userPassword:$password,
      visaType:"F-1", academicLevel:"Undergraduate", programEndDate:"2028-05-31"}')
  register_status=$(curl --silent --show-error --max-time 20 \
    --output "$tmpdir/register.json" --write-out '%{http_code}' \
    --header 'Content-Type: application/json' --data "$register_payload" \
    "$APP_BASE_URL/api/v1/auth/register")
  if [[ "$register_status" != '200' ]]; then
    error_detail=$(jq -r '
      if (.validationErrors // {} | length) > 0 then
        "Request validation failed: " + ((.validationErrors | to_entries | map("\(.key) \(.value)") | join(", ")))
      else
        (.message // "Request rejected")
      end' "$tmpdir/register.json" 2>/dev/null || echo 'Request rejected')
    echo "Signup failed with HTTP $register_status: $error_detail" >&2
    exit 1
  fi
  cp "$tmpdir/register.json" "$tmpdir/login.json"
fi

token=$(jq -er '.token | select(type == "string" and length > 0)' "$tmpdir/login.json")
auth_header="Authorization: Bearer $token"
echo 'Signup/login passed.'

# The first run writes a known profile. After a restart/redeploy, run with
# VERIFY_PERSISTENCE_ONLY=true so the saved value is checked before any write.
profile_payload='{"visaType":"F-1","academicLevel":"Undergraduate","programEndDate":"2028-05-31","upcomingTravelDate":"2027-01-15","consentedFields":["VISA_TYPE","ACADEMIC_LEVEL","PROGRAM_END_DATE","UPCOMING_TRAVEL_DATE"]}'
if [[ "${VERIFY_PERSISTENCE_ONLY:-false}" != 'true' ]]; then
  echo 'The staging test profile will include visa type, academic level, program end date, and upcoming travel date.'
  echo 'The app uses these to identify relevant immigration rules, education-level eligibility, program deadlines, and travel-related changes.'
  read -r -p 'Do you consent to using these disposable test fields for this staging check? Type yes to continue: ' consent_answer
  if [[ "$consent_answer" != 'yes' ]]; then
    echo 'Profile check stopped; no profile fields were submitted.' >&2
    exit 1
  fi
  profile_save_status=$(curl --silent --show-error --max-time 20 \
    --output "$tmpdir/profile-save.json" --write-out '%{http_code}' \
    --header "$auth_header" --header 'Content-Type: application/json' \
    --data "$profile_payload" --request PUT "$APP_BASE_URL/api/v1/profiles/me")
  if [[ "$profile_save_status" != '200' ]]; then
    error_detail=$(jq -r '
      if (.validationErrors // {} | length) > 0 then
        "Request validation failed: " + ((.validationErrors | to_entries | map("\(.key) \(.value)") | join(", ")))
      else
        (.message // "Request rejected")
      end' "$tmpdir/profile-save.json" 2>/dev/null || echo 'Request rejected')
    echo "Profile save failed with HTTP $profile_save_status: $error_detail" >&2
    exit 1
  fi
fi
profile=$(curl --fail --silent --show-error --max-time 20 \
  --header "$auth_header" "$APP_BASE_URL/api/v1/profiles/me")
jq -e '.visaType == "F-1" and .academicLevel == "Undergraduate" and .programEndDate == "2028-05-31" and .upcomingTravelDate == "2027-01-15"' \
  <<< "$profile" >/dev/null
echo 'Profile save/read passed.'

# The staging database must have at least one indexed policy and the configured
# embedding and answer-generation providers must be reachable for this check.
rag_payload='{"question":"What does the indexed policy say about F-1 students?","visaType":"F-1"}'
rag=$(curl --fail --silent --show-error --max-time 90 \
  --header "$auth_header" --header 'Content-Type: application/json' \
  --data "$rag_payload" "$APP_BASE_URL/api/v1/rag/query")
if ! jq -e '(.answer | type == "string" and length > 0) and (.citations | type == "array" and length > 0) and (.retrievedEvidence | type == "array" and length > 0)' \
  <<< "$rag" >/dev/null; then
  echo 'RAG returned no grounded evidence. Index an F-1 policy in staging and retry.' >&2
  exit 1
fi
echo 'RAG query passed. Review its citations and warnings as part of the demo.'

notifications=$(curl --fail --silent --show-error --max-time 20 \
  --header "$auth_header" "$APP_BASE_URL/api/v1/notifications")
jq -e '(.content | type == "array")' <<< "$notifications" >/dev/null
unread=$(curl --fail --silent --show-error --max-time 20 \
  --header "$auth_header" "$APP_BASE_URL/api/v1/notifications/unread-count")
jq -e '(.unread | type == "number")' <<< "$unread" >/dev/null
echo 'Notification inbox and unread-count endpoints passed.'

echo 'Staging acceptance checks passed.'
