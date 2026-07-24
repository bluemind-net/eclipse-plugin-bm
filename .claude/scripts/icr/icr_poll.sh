#!/usr/bin/env bash
set -uo pipefail
# Long-polls icr_next until a thread arrives, the session is stopped, or the Eclipse
# endpoint becomes unreachable (Eclipse closed/restarted — treated the same as a
# graceful stop), then prints the payload and exits. Meant to be launched with
# run_in_background: true.

# shellcheck source=/dev/null
source "$HOME/.claude/tmp/icr_env.sh"

while :; do
  RESPONSE=$(curl -s -X POST "$ICR_URL" \
    -H "Authorization: Bearer $ICR_TOKEN" -H "Content-Type: application/json" --max-time 60 \
    -d '{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"icr_next","arguments":{}}}')
  CURL_EXIT=$?

  if [ "$CURL_EXIT" -ne 0 ]; then
    echo '{"status":"inactive","reason":"unreachable"}'
    break
  fi

  RESP=$(echo "$RESPONSE" | jq -c '.result.content[0].text | fromjson')
  STATUS=$(echo "$RESP" | jq -r '.status // "idle"')
  if [ "$STATUS" != "idle" ]; then
    echo "$RESP"
    break
  fi
done
