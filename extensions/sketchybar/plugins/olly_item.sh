#!/usr/bin/env bash
set -euo pipefail

SKETCHYBAR="${SKETCHYBAR:-sketchybar}"

if [ "${SENDER:-}" = "mouse.clicked" ]; then
  "$SKETCHYBAR" --set "$NAME" popup.drawing=toggle
  exit 0
fi

label="event"
if command -v jq >/dev/null 2>&1 && [ -n "${OLLY_EVENT:-}" ]; then
  event_name="$(printf '%s' "$OLLY_EVENT" | jq -r '.event.engine | to_entries[0].key // "event"')"
  engine="$(printf '%s' "$OLLY_EVENT" | jq -r '.event.engine | to_entries[0].value.engineID.rawValue // ""')"
  display="$(printf '%s' "$OLLY_EVENT" | jq -r '.event.engine | to_entries[0].value.displayID // ""')"
  label="$event_name"
  if [ -n "$engine" ]; then
    label="$label $engine"
  fi
  if [ -n "$display" ]; then
    label="$label d$display"
  fi
fi

"$SKETCHYBAR" --set "$NAME" label="$label"
