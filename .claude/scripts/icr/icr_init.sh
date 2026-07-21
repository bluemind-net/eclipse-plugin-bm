#!/usr/bin/env bash
set -euo pipefail
# Resolve the Eclipse MCP endpoint and write ~/.claude/tmp/icr_env.sh (ICR_URL, ICR_TOKEN).
# Usage: icr_init.sh [workspace-filter]
#   workspace-filter: optional substring matched against a config's "workspace" field,
#   used to pick the right Eclipse instance when several are running. Falls back to the
#   first config found if no filter is given or no match is found.

mkdir -p "$HOME/.claude/tmp"

FILTER="${1:-}"
CFG=""

if [ -n "$FILTER" ]; then
  for f in "$HOME"/.config/bluemind/mcp/eclipse-*.json; do
    [ -e "$f" ] || continue
    if jq -e --arg f "$FILTER" '.workspace // "" | contains($f)' "$f" >/dev/null 2>&1; then
      CFG="$f"
      break
    fi
  done
fi

if [ -z "$CFG" ]; then
  CFG=$(ls "$HOME"/.config/bluemind/mcp/eclipse-*.json 2>/dev/null | head -1)
fi

if [ -z "$CFG" ]; then
  echo "NO_CONFIG"
  exit 1
fi

jq -r '"ICR_URL=" + .url, "ICR_TOKEN=" + .token' "$CFG" > "$HOME/.claude/tmp/icr_env.sh"
echo "$CFG"
