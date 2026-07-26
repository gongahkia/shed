#!/usr/bin/env bash
set -euo pipefail

OLLYCTL="${OLLYCTL:-ollyctl}"
SKETCHYBAR="${SKETCHYBAR:-sketchybar}"

"$OLLYCTL" events --json | while IFS= read -r event_json; do
  "$SKETCHYBAR" --trigger olly_update OLLY_EVENT="$event_json"
done
