#!/usr/bin/env bash
# Deployment smoke test: health, auth boundary, authenticated read, and the page.
set -euo pipefail

BASE_URL="${BASE_URL:-http://127.0.0.1:8080}"
TOKEN="${APP_API_TOKEN:-}"

if [ -z "$TOKEN" ]; then
  echo "smoke: APP_API_TOKEN is required" >&2
  exit 1
fi

fail() {
  echo "smoke: $1" >&2
  exit 1
}

health="$(curl -fsS "$BASE_URL/health")" || fail "health check failed"
[ "$health" = "ok" ] || fail "health returned '$health'"

# No token must be rejected before the body is read.
status="$(curl -s -o /dev/null -w '%{http_code}' "$BASE_URL/api/conversations")"
[ "$status" = "401" ] || fail "unauthenticated /api/conversations returned $status, expected 401"

# The token must unlock the read API (an empty list is still a JSON array).
body="$(curl -fsS -H "Authorization: Bearer $TOKEN" "$BASE_URL/api/conversations")" \
  || fail "authenticated conversations request failed"
case "$body" in
  \[*) ;;
  *) fail "conversations did not return a JSON array" ;;
esac

page="$(curl -fsS "$BASE_URL/")" || fail "index request failed"
printf '%s' "$page" | grep -qi '<html' || fail "index did not return HTML"

echo "smoke: ok"
