#!/usr/bin/env bash
# Usage: SCRIPT_URL=https://script.google.com/macros/s/.../exec SECRET=xxx ./apps-script/test.sh
# Appends ONE test row (source=test) to Purchases, then re-sends it to check dedupe,
# then sends a bad token. Delete the test row from the sheet afterwards.
set -euo pipefail
: "${SCRIPT_URL:?set SCRIPT_URL}" "${SECRET:?set SECRET}"
ID="test-$(date +%s)"
BODY=$(cat <<EOF
{"token":"$SECRET","receipt_id":"$ID","rows":[{"receipt_id":"$ID","date":"2026-10-03","time":"18:29",
"photo_timestamp":"$(date +%Y-%m-%dT%H:%M:%S%z)","store":"TEST STORE","item":"TEST ITEM (delete me)",
"qty":1,"unit_price":1.23,"line_total":1.23,"receipt_total":1.23,"added_by":"test","source":"test"}]}
EOF
)
post() { curl -sS -L -H 'Content-Type: application/json' --data "$1" "$SCRIPT_URL"; echo; }

echo "1) GET health:";      curl -sS -L "$SCRIPT_URL"; echo
echo "2) insert (expect inserted:1):"; post "$BODY"
echo "3) resend (expect duplicate:true):"; post "$BODY"
echo "4) bad token (expect unauthorized):"; post "${BODY/$SECRET/wrong}"
