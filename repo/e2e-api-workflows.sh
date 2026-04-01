#!/usr/bin/env bash
set -euo pipefail

BASE_URL="${BASE_URL:-http://localhost:8081}"
CLIENT_KEY="${CLIENT_KEY:-demo-device}"
KEY_VERSION="${KEY_VERSION:-1}"
SHARED_SECRET="${SHARED_SECRET:-demo-device-shared-secret}"

if [[ "${SHARED_SECRET}" == "demo-device-shared-secret" ]]; then
  echo "ERROR: set SHARED_SECRET to a rotated device secret before running this workflow." >&2
  exit 1
fi

api_call() {
  local method="$1"
  local path="$2"
  local body="${3:-}"
  local ts nonce payload sig path_only query canonical_query body_hash
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

echo "[1/8] Content import preview API reachability"
api_call GET "/api/v1/content/summary" >/dev/null

echo "[2/8] Booking reserve"
BOOKING_JSON="$(api_call POST "/api/v1/bookings" '{"locationId":1,"createdBy":1,"customerName":"E2E User","customerPhone":"+1 (555) 0099","startAt":"2030-01-01T10:00:00","durationMinutes":60,"orderNote":"e2e-api"}')"
BOOKING_ID="$(printf "%s" "${BOOKING_JSON}" | jq -r '.bookingId')"

echo "[3/8] Booking transition"
api_call POST "/api/v1/bookings/${BOOKING_ID}/transition" '{"targetState":"CONFIRMED","actorUserId":1,"reason":"e2e confirm"}' >/dev/null

echo "[4/8] Payment tender"
TENDER_JSON="$(api_call POST "/api/v1/payments/tenders" "{\"bookingOrderId\":${BOOKING_ID},\"tenderType\":\"CARD_PRESENT\",\"amount\":25.00,\"currency\":\"USD\",\"terminalId\":\"E2E-T\",\"terminalTxnId\":\"E2E-${BOOKING_ID}\",\"createdBy\":1}")"
TENDER_ID="$(printf "%s" "${TENDER_JSON}" | jq -r '.tenderEntryId')"

echo "[5/8] Payment callback idempotency"
api_call POST "/api/v1/payments/callbacks" "{\"terminalId\":\"E2E-T\",\"terminalTxnId\":\"E2E-${BOOKING_ID}\",\"payload\":{\"approved\":true}}" >/dev/null
api_call POST "/api/v1/payments/callbacks" "{\"terminalId\":\"E2E-T\",\"terminalTxnId\":\"E2E-${BOOKING_ID}\",\"payload\":{\"approved\":true}}" >/dev/null

echo "[6/8] Refund request"
api_call POST "/api/v1/payments/refunds" "{\"tenderEntryId\":${TENDER_ID},\"amount\":5.00,\"currency\":\"USD\",\"reason\":\"e2e refund\",\"createdBy\":1}" >/dev/null

echo "[7/8] Leave summary API"
api_call GET "/api/v1/leave/summary" >/dev/null

echo "[8/8] Admin webhook registration"
api_call POST "/api/v1/admin/webhooks" '{"locationId":1,"name":"e2e-hook","callbackUrl":"http://127.0.0.1:8080/hook"}' >/dev/null

echo "E2E API workflows completed successfully"
