#!/bin/bash
# CafeGuard fiscal-scan gateway — bash + curl only (no Java/Python install).
#
# Reads HID barcode lines from stdin and POSTs to CafeGuard.
# Config: same gateway.properties keys as the Java/Python agents.
#
#   ./scripts/fiscal_scan_gateway.sh config/gateway.properties
#
set -euo pipefail

CONFIG="${1:-config/gateway.properties}"
if [[ ! -f "$CONFIG" ]]; then
  echo "Missing config: $CONFIG" >&2
  exit 1
fi

prop() {
  local key="$1"
  local line
  line="$(grep -E "^[[:space:]]*${key}=" "$CONFIG" | tail -n1 || true)"
  echo "${line#*=}" | sed 's/^[[:space:]]*//;s/[[:space:]]*$//'
}

BASE_URL="$(prop cafeguard.baseUrl)"
EMAIL="$(prop cafeguard.email)"
PASSWORD="$(prop cafeguard.password)"
BASE_URL="${BASE_URL%/}"

if [[ -z "$BASE_URL" || -z "$EMAIL" || -z "$PASSWORD" ]]; then
  echo "config needs cafeguard.baseUrl, email, password" >&2
  exit 1
fi

TOKEN=""
TOKEN_EXPIRES=0

json_escape() {
  python3 -c 'import json,sys; print(json.dumps(sys.stdin.read()), end="")' 2>/dev/null \
    || printf '%s' "$1" | sed 's/\\/\\\\/g; s/"/\\"/g'
}

# Escape without requiring python (portable for macOS bash).
escape_json_string() {
  local s="$1"
  s="${s//\\/\\\\}"
  s="${s//\"/\\\"}"
  s="${s//$'\n'/\\n}"
  s="${s//$'\r'/\\r}"
  s="${s//$'\t'/\\t}"
  printf '%s' "$s"
}

login() {
  local now body
  now="$(date +%s)"
  if [[ -n "$TOKEN" && "$now" -lt "$TOKEN_EXPIRES" ]]; then
    return 0
  fi
  body="$(curl -sS -X POST "$BASE_URL/api/auth/login" \
    -H 'Content-Type: application/json' \
    -H 'Accept: application/json' \
    -H 'User-Agent: CafeGuard-ScannerGateway/1.0' \
    --data-binary "{\"email\":\"$(escape_json_string "$EMAIL")\",\"password\":\"$(escape_json_string "$PASSWORD")\"}")"

  TOKEN="$(printf '%s' "$body" | sed -n 's/.*"token"[[:space:]]*:[[:space:]]*"\([^"]*\)".*/\1/p' | head -n1)"
  if [[ -z "$TOKEN" ]]; then
    echo "Login failed: $body" >&2
    return 1
  fi
  TOKEN_EXPIRES=$((now + 2100))
  echo "Signed in as $EMAIL"
}

enqueue() {
  local text="$1"
  local body resp
  login || return 1
  body="{\"text\":\"$(escape_json_string "$text")\"}"
  resp="$(curl -sS -X POST "$BASE_URL/api/receipts/fiscal-scans" \
    -H 'Content-Type: application/json' \
    -H 'Accept: application/json' \
    -H 'User-Agent: CafeGuard-ScannerGateway/1.0' \
    -H "Authorization: Bearer $TOKEN" \
    --data-binary "$body")"
  echo "Queued: $resp"
}

echo "Listening for HID scans → $BASE_URL"
echo "Keep this Terminal focused, then scan a fiscal QR."

while IFS= read -r line; do
  payload="$(printf '%s' "$line" | sed 's/^[[:space:]]*//;s/[[:space:]]*$//')"
  [[ -z "$payload" ]] && continue
  echo "Scan detected (${#payload} chars)"
  if ! enqueue "$payload"; then
    echo "Send failed" >&2
  fi
done
