#!/usr/bin/env bash
set -euo pipefail

DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
OLLYCTL="${OLLYCTL:-ollyctl}"
CONFIG_SCRIPT="${OLLY_JANKYBORDERS_CONFIG:-$DIR/bordersrc}"
STATE_DIR="${XDG_STATE_HOME:-$HOME/.local/state}/olly"
STATE_FILE="${OLLY_JANKYBORDERS_STATE:-$STATE_DIR/jankyborders-focus}"

require_jq() {
  command -v jq >/dev/null 2>&1 || {
    printf '%s\n' "jq is required for olly_focus_borders.sh" >&2
    exit 127
  }
}

event_stream() {
  if [ "$#" -gt 0 ]; then
    cat "$@"
  elif [ -t 0 ]; then
    "$OLLYCTL" events --event-kind focus --json
  else
    cat
  fi
}

focus_key() {
  jq -er '
    select(.event.focus != null)
    | [
        (.event.focus.focusedWindowID // "none"),
        (.event.focus.displayID // "unknown"),
        (.event.focus.tagMask // "unknown")
      ]
    | @tsv
  '
}

mkdir -p "$STATE_DIR"
require_jq

event_stream "$@" | while IFS= read -r event_json; do
  key="$(printf '%s' "$event_json" | focus_key || true)"
  [ -n "$key" ] || continue
  previous="$(cat "$STATE_FILE" 2>/dev/null || true)"
  [ "$key" != "$previous" ] || continue
  printf '%s\n' "$key" >"$STATE_FILE"
  "$CONFIG_SCRIPT"
done
