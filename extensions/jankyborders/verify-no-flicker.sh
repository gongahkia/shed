#!/usr/bin/env bash
set -euo pipefail

DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
TMP_DIR="$(mktemp -d "${TMPDIR:-/tmp}/olly-jankyborders.XXXXXX")"
trap 'rm -rf "$TMP_DIR"' EXIT

FAKE_CONFIG="$TMP_DIR/fake-borders-config"
LOG="$TMP_DIR/borders.log"
cat >"$FAKE_CONFIG" <<SH
#!/usr/bin/env bash
printf 'configured\n' >>"$LOG"
SH
chmod +x "$FAKE_CONFIG"

OLLY_JANKYBORDERS_CONFIG="$FAKE_CONFIG" \
OLLY_JANKYBORDERS_STATE="$TMP_DIR/focus-state" \
  "$DIR/olly_focus_borders.sh" "$DIR/fixtures/focus-duplicates.jsonl"

count="$(wc -l <"$LOG" | tr -d ' ')"
if [ "$count" != "1" ]; then
  printf 'expected 1 config call, got %s\n' "$count" >&2
  exit 1
fi

printf 'ok: duplicate focus/tag replay caused %s config call\n' "$count"
