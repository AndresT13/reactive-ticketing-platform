#!/usr/bin/env bash
# Generates a local HS256 JWT for the API.
#   ./scripts/generate-token.sh <subject> "<space separated scopes>" [ttl-seconds]
#   ./scripts/generate-token.sh customer-1 "ticketing:purchase"
# The secret is read from TICKETING_SECURITY_LOCAL_JWT_SECRET or from the .env file.
set -euo pipefail

subject="${1:?usage: generate-token.sh <subject> \"<scopes>\" [ttl-seconds]}"
scopes="${2:-ticketing:purchase}"
ttl="${3:-3600}"

if [[ -z "${TICKETING_SECURITY_LOCAL_JWT_SECRET:-}" && -f .env ]]; then
  TICKETING_SECURITY_LOCAL_JWT_SECRET="$(grep -E '^TICKETING_SECURITY_LOCAL_JWT_SECRET=' .env | cut -d= -f2-)"
fi
secret="${TICKETING_SECURITY_LOCAL_JWT_SECRET:?Define TICKETING_SECURITY_LOCAL_JWT_SECRET or create .env}"

b64url() { openssl base64 -A | tr '+/' '-_' | tr -d '='; }

now=$(date +%s)
header=$(printf '{"alg":"HS256","typ":"JWT"}' | b64url)
payload=$(printf '{"sub":"%s","scope":"%s","iat":%d,"exp":%d}' "$subject" "$scopes" "$now" $((now + ttl)) | b64url)
signature=$(printf '%s.%s' "$header" "$payload" | openssl dgst -sha256 -hmac "$secret" -binary | b64url)

printf '%s.%s.%s\n' "$header" "$payload" "$signature"
