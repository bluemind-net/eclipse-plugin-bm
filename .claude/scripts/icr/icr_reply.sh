#!/usr/bin/env bash
set -euo pipefail
# Posts an icr_reply, reading the markdown body from a file so arbitrary quotes,
# backslashes, and newlines in the reply never touch shell quoting.
# Usage: icr_reply.sh <threadId> <bodyFile>

THREAD_ID="${1:?threadId required}"
BODY_FILE="${2:?body file required}"

# shellcheck source=/dev/null
source "$HOME/.claude/tmp/icr_env.sh"

jq -n --arg id "$THREAD_ID" --rawfile body "$BODY_FILE" \
  '{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"icr_reply","arguments":{"threadId":$id,"body":$body}}}' \
| curl -s -X POST "$ICR_URL" \
    -H "Authorization: Bearer $ICR_TOKEN" \
    -H "Content-Type: application/json" \
    --max-time 60 \
    -d @- \
| jq -r '.result.content[0].text'
