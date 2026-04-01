#!/usr/bin/env bash
set -euo pipefail

BASE_URL="${BASE_URL:-http://localhost:8081}"
CLIENT_KEY="${CLIENT_KEY:-demo-device}"
KEY_VERSION="${KEY_VERSION:-1}"
SHARED_SECRET="${SHARED_SECRET:-demo-device-shared-secret}"

if [[ "${SHARED_SECRET}" == "demo-device-shared-secret" ]]; then
  echo "ERROR: set SHARED_SECRET to an active device secret" >&2
  exit 1
fi

sign_request() {
  local method="$1"
  local path="$2"
  local body="${3:-}"
  local ts nonce path_only query canonical_query body_hash payload sig

  ts="$(date +%s)"
  nonce="$(openssl rand -hex 12)"
  path_only="${path%%\?*}"
  if [[ "${path}" == *"?"* ]]; then
    query="${path#*\?}"
  else
    query=""
  fi
  if [[ -n "${query}" ]]; then
    canonical_query="$(printf "%s\n" "${query//&/$'\n'}" | LC_ALL=C sort | paste -sd'&' -)"
  else
    canonical_query=""
  fi
  body_hash="$(printf "%s" "${body}" | openssl dgst -sha256 -binary | xxd -p -c 256)"
  payload="${method}|${path_only}|${canonical_query}|${body_hash}|${ts}|${nonce}"
  sig="$(printf "%s" "${payload}" | openssl dgst -sha256 -hmac "${SHARED_SECRET}" -binary | xxd -p -c 256)"

  printf "%s\n%s\n%s\n" "${ts}" "${nonce}" "${sig}"
}

api_call() {
  local method="$1"
  local path="$2"
  local body="${3:-}"
  local sign ts nonce sig
  sign="$(sign_request "${method}" "${path}" "${body}")"
  ts="$(printf "%s" "${sign}" | awk 'NR==1')"
  nonce="$(printf "%s" "${sign}" | awk 'NR==2')"
  sig="$(printf "%s" "${sign}" | awk 'NR==3')"

  if [[ -n "${body}" ]]; then
    curl -fsS -X "${method}" "${BASE_URL}${path}" \
      -H "Content-Type: application/json" \
      -H "X-Client-Key: ${CLIENT_KEY}" \
      -H "X-Key-Version: ${KEY_VERSION}" \
      -H "X-Timestamp: ${ts}" \
      -H "X-Nonce: ${nonce}" \
      -H "X-Signature: ${sig}" \
      -d "${body}"
  else
    curl -fsS -X "${method}" "${BASE_URL}${path}" \
      -H "X-Client-Key: ${CLIENT_KEY}" \
      -H "X-Key-Version: ${KEY_VERSION}" \
      -H "X-Timestamp: ${ts}" \
      -H "X-Nonce: ${nonce}" \
      -H "X-Signature: ${sig}"
  fi
}

echo "[1/3] Health check"
curl -fsS "${BASE_URL}/actuator/health" | grep -q '"status"'

echo "[2/3] Authenticated API summary check"
api_call GET "/api/v1/content/summary" >/dev/null

echo "[3/3] DB-backed workflow write check"
txn_id="SMOKE-$(date +%s)"
api_call POST "/api/v1/payments/callbacks" "{\"terminalId\":\"SMOKE-T\",\"terminalTxnId\":\"${txn_id}\",\"payload\":{\"approved\":true}}" >/dev/null

echo "Local smoke checks passed"
