#!/usr/bin/env bash
set -euo pipefail

socket_dir="$(mktemp -d "${TMPDIR:-/tmp}/olly-ipc-smoke.XXXXXX")"
socket_path="$socket_dir/olly.sock"
app_bundle="${APP_BUNDLE:-dist/Olly.app}"
app_executable="${APP_EXECUTABLE:-}"
ollyctl_executable="${OLLYCTL_EXECUTABLE:-.build/release/ollyctl}"
timeout_seconds="${OLLY_IPC_SMOKE_TIMEOUT_SECONDS:-10}"

cleanup() {
    if [[ -n "${app_pid:-}" ]] && kill -0 "$app_pid" 2>/dev/null; then
        kill "$app_pid" 2>/dev/null || true
        wait "$app_pid" 2>/dev/null || true
    fi
    rm -rf "$socket_dir"
}
trap cleanup EXIT

if [[ -z "$app_executable" ]]; then
    if [[ ! -x "$app_bundle/Contents/MacOS/Olly" ]]; then
        CODESIGN_IDENTITY="${CODESIGN_IDENTITY:--}" scripts/package-macos-dmg.sh >/dev/null
    fi
    app_executable="$app_bundle/Contents/MacOS/Olly"
fi

swift build -c release --product ollyctl >/dev/null

XDG_RUNTIME_DIR="$socket_dir" "$app_executable" &
app_pid="$!"

deadline=$((SECONDS + timeout_seconds))
while [[ ! -S "$socket_path" ]]; do
    if ! kill -0 "$app_pid" 2>/dev/null; then
        echo "olly app exited before socket was created" >&2
        exit 1
    fi
    if (( SECONDS >= deadline )); then
        echo "timed out waiting for IPC socket: $socket_path" >&2
        exit 1
    fi
    sleep 0.1
done

"$ollyctl_executable" version --socket "$socket_path" >/dev/null
"$ollyctl_executable" state --socket "$socket_path" >/dev/null
"$ollyctl_executable" restore-windows --socket "$socket_path" >/dev/null

echo "app IPC smoke passed: $socket_path"
