#!/usr/bin/env bash
set -euo pipefail

mode="${1:-run}"
root_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
app_bundle="$root_dir/dist/Itsy.app"

pkill -x Itsy >/dev/null 2>&1 || true
(cd "$root_dir" && swift build -c release && APP_DIR="$app_bundle" bench/scripts/make_app.sh >/dev/null)

case "$mode" in
run) /usr/bin/open -n "$app_bundle" ;;
--debug|debug) lldb -- "$app_bundle/Contents/MacOS/Itsy" ;;
--logs|logs) /usr/bin/open -n "$app_bundle"; /usr/bin/log stream --info --style compact --predicate 'process == "Itsy"' ;;
--telemetry|telemetry) /usr/bin/open -n "$app_bundle"; /usr/bin/log stream --info --style compact --predicate 'subsystem == "dev.itsy.editor"' ;;
--verify|verify) /usr/bin/open -n "$app_bundle"; sleep 1; pgrep -x Itsy >/dev/null ;;
*) echo "usage: $0 [run|--debug|--logs|--telemetry|--verify]" >&2; exit 2 ;;
esac
