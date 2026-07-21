#!/usr/bin/env bash
set -uo pipefail
# Generic Eclipse ICR MCP tool call.
# Usage: icr_call.sh <tool_name> [json_arguments]
#   json_arguments must be valid JSON (default: {}). Requires icr_init.sh to have run first.
#   If the Eclipse endpoint can't be reached at all (Eclipse closed/restarted), prints
#   {"status":"unreachable"} instead of failing silently with a bare exit code.

TOOL="${1:?tool name required}"
if [ $# -ge 2 ]; then
  ARGS="$2"
else
  ARGS='{}'
fi

# shellcheck source=/dev/null
source "$HOME/.claude/tmp/icr_env.sh"

REQUEST=$(jq -n --arg name "$TOOL" --argjson args "$ARGS" \
  '{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":$name,"arguments":$args}}')

RESPONSE=$(curl -s -X POST "$ICR_URL" \
  -H "Authorization: Bearer $ICR_TOKEN" \
  -H "Content-Type: application/json" \
  --max-time 60 \
  -d "$REQUEST")
CURL_EXIT=$?

if [ "$CURL_EXIT" -ne 0 ]; then
  echo '{"status":"unreachable"}'
  exit 0
fi

echo "$RESPONSE" | jq -r '.result.content[0].text'
