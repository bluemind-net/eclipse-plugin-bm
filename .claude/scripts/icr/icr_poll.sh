#!/usr/bin/env bash
set -uo pipefail
# Long-polls icr_next until a thread arrives, the session is stopped, or the Eclipse
# endpoint becomes unreachable (Eclipse closed/restarted — treated the same as a
# graceful stop), then prints the payload and exits. Meant to be launched with
# run_in_background: true.
#
# icr_next is edge-triggered: if a second thread is created while the first is
# still being handled, a plain icr_next loop can drop it silently (the caller
# only ever sees one delivery). After the first thread arrives, drain any other
# already-pending threads via icr_start (which lists full thread state, not
# just new arrivals) and print one JSON line per thread so the caller can
# dispatch all of them before re-arming the poller.

# shellcheck source=/dev/null
source "$HOME/.claude/tmp/icr_env.sh"

while :; do
  RESPONSE=$(curl -s -X POST "$ICR_URL" \
    -H "Authorization: Bearer $ICR_TOKEN" -H "Content-Type: application/json" --max-time 60 \
    -d '{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"icr_next","arguments":{}}}')
  CURL_EXIT=$?

  if [ "$CURL_EXIT" -ne 0 ]; then
    echo '{"status":"inactive","reason":"unreachable"}'
    exit 0
  fi

  RESP=$(echo "$RESPONSE" | jq -c '.result.content[0].text | fromjson')
  STATUS=$(echo "$RESP" | jq -r '.status // "idle"')
  if [ "$STATUS" != "idle" ]; then
    echo "$RESP"
    break
  fi
done

if [ "$STATUS" = "thread" ]; then
  DELIVERED_ID=$(echo "$RESP" | jq -r '.thread.id')

  START_RESPONSE=$(curl -s -X POST "$ICR_URL" \
    -H "Authorization: Bearer $ICR_TOKEN" -H "Content-Type: application/json" --max-time 10 \
    -d '{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"icr_start","arguments":{}}}')

  if [ $? -eq 0 ]; then
    echo "$START_RESPONSE" | jq -c --arg id "$DELIVERED_ID" \
      '.result.content[0].text | fromjson | .threads[]
        | select(.status == "pending" and .id != $id)
        | {status: "thread", thread: .}'
  fi
fi
