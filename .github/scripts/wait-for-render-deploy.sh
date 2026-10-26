#!/usr/bin/env bash
set -euo pipefail

if [[ -z "${RENDER_API_KEY:-}" || -z "${RENDER_SERVICE_ID:-}" || -z "${DEPLOY_ID:-}" ]]; then
  echo 'Render deploy credentials or deploy ID are missing.' >&2
  exit 1
fi

for attempt in $(seq 1 90); do
  response=$(curl --fail --silent --show-error \
    "https://api.render.com/v1/services/${RENDER_SERVICE_ID}/deploys/${DEPLOY_ID}" \
    --header "Authorization: Bearer ${RENDER_API_KEY}")
  status=$(jq -er '.status' <<< "$response")
  echo "Render deploy status: $status"

  case "$status" in
    live) exit 0 ;;
    build_failed|pre_deploy_failed|update_failed|canceled|deactivated)
      echo "Render deploy ended with status '$status'." >&2
      exit 1
      ;;
    created|queued|build_in_progress|pre_deploy_in_progress|update_in_progress) sleep 10 ;;
    *) echo "Unexpected Render deploy status '$status'." >&2; exit 1 ;;
  esac
done

echo 'Timed out waiting for the Render deploy to become live.' >&2
exit 1
