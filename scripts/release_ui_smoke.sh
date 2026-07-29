#!/usr/bin/env bash
set -euo pipefail

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
repo_dir="$(cd "$script_dir/.." && pwd)"
app="${ITSY_RELEASE_UI_APP:-$repo_dir/Itsy.app}"
artifacts_dir="${ITSY_RELEASE_UI_ARTIFACTS:-$repo_dir/.build/release-ui-smoke}"
timeout_seconds="${ITSY_RELEASE_UI_TIMEOUT_SECONDS:-15}"
build=1
pid=""
run_dir=""
ui_log=""

usage() {
	echo "usage: $0 [--app path] [--artifacts directory] [--timeout seconds] [--no-build]" >&2
}

while [[ "$#" -gt 0 ]]; do
	case "$1" in
	--app) app="$2"; shift 2 ;;
	--artifacts) artifacts_dir="$2"; shift 2 ;;
	--timeout) timeout_seconds="$2"; shift 2 ;;
	--no-build) build=0; shift ;;
	*) usage; exit 2 ;;
	esac
done

if [[ ! "$timeout_seconds" =~ ^[1-9][0-9]*$ ]]; then
	echo "invalid timeout: $timeout_seconds" >&2
	exit 2
fi

mkdir -p "$artifacts_dir"
artifacts_dir="$(cd "$artifacts_dir" && pwd)"
run_dir="$artifacts_dir/run-$(date -u +%Y%m%dT%H%M%SZ)-$$"
workspace="$run_dir/workspace"
home_dir="$run_dir/home"
screenshots="$run_dir/screenshots"
ui_log="$run_dir/ui.log"
app_log="$run_dir/app.log"
result="$run_dir/result.json"
repro="$run_dir/repro.sh"
mkdir -p "$workspace/.itsy" "$home_dir" "$screenshots"

cleanup() {
	if [[ -n "$pid" ]] && kill -0 "$pid" 2>/dev/null; then
		kill "$pid" 2>/dev/null || true
		wait "$pid" 2>/dev/null || true
	fi
}
trap cleanup EXIT

write_result() {
	local status="$1"
	local reason="${2:-}"
	ruby -rjson -rtime -e '
		status, reason, app, run_dir, app_log, ui_log, screenshots, repro = ARGV
		puts JSON.pretty_generate({
			"schema" => 1,
			"generated_at" => Time.now.utc.iso8601,
			"status" => status,
			"reason" => reason,
			"app" => app,
			"artifacts" => {"run" => run_dir, "app_log" => app_log, "ui_log" => ui_log, "screenshots" => screenshots, "repro" => repro}
		})
	' "$status" "$reason" "$app" "$run_dir" "$app_log" "$ui_log" "$screenshots" "$repro" >"$result"
}

capture() {
	local name="$1"
	screencapture -x "$screenshots/$name.png" >>"$ui_log" 2>&1
}

block() {
	local reason="$1"
	write_result blocked "$reason"
	echo "BLOCKED reason=$reason result=$result log=$ui_log" >&2
	exit 2
}

fail() {
	local reason="$1"
	capture failure || true
	write_result failed "$reason"
	echo "REGRESSION reason=$reason result=$result log=$ui_log screenshot=$screenshots/failure.png repro=$repro" >&2
	exit 1
}

if [[ "$build" == 1 ]]; then
	(cd "$repo_dir" && swift build -c release >/dev/null && APP_DIR="$app" bench/scripts/make_app.sh >/dev/null)
fi
app_binary="$app/Contents/MacOS/Itsy"
[[ -x "$app_binary" ]] || block "missing packaged app executable: $app_binary"
for command in git osascript screencapture; do
	command -v "$command" >/dev/null 2>&1 || block "missing command: $command"
done
if ! osascript -e 'tell application "System Events" to return UI elements enabled' >>"$ui_log" 2>&1; then
	block "Accessibility access is required for release UI smoke"
fi
if ! capture preflight || [[ ! -s "$screenshots/preflight.png" ]]; then
	block "screen capture access is required for release UI smoke"
fi

printf '%s\n' 'import Foundation' '' 'struct Smoke {' '    let value = "release-ui"' '}' '' 'print(Smoke().value)' >"$workspace/main.swift"
printf '%s\n' '{"version":1,"scope":"project","tasks":[{"id":"smoke","label":"Smoke Task","command":"/bin/sh","arguments":["-lc","printf task-smoke"],"presentation":{"reveal":"always","focus":false,"dedicated":false,"show_resolved_command":true}}]}' >"$workspace/.itsy/tasks.json"
printf '%s\n' '{"adapters":[{"id":"smoke","command":"/usr/bin/true","type":"executable"}],"configurations":[{"name":"Smoke Debug","type":"smoke","request":"launch","program":"${workspaceFolder}/main.swift"}]}' >"$workspace/.itsy/debug.json"
git -C "$workspace" init -q
git -C "$workspace" config user.email smoke@example.invalid
git -C "$workspace" config user.name "Release UI Smoke"
git -C "$workspace" add .
git -C "$workspace" commit -qm "smoke fixture"
printf '%s\n' '// git status fixture' >>"$workspace/main.swift"
printf '%s\n' '#!/usr/bin/env bash' 'set -euo pipefail' "cd $(printf '%q' "$repo_dir")" "$(printf '%q' "$script_dir/release_ui_smoke.sh") --app $(printf '%q' "$app") --artifacts $(printf '%q' "$artifacts_dir") --no-build" >"$repro"
chmod +x "$repro"

HOME="$home_dir" "$app_binary" "$workspace/main.swift" >"$app_log" 2>&1 &
pid="$!"

window_exists() {
	local title="$1"
	local output
	if ! output="$(osascript - "$pid" "$title" <<'OSA' 2>>"$ui_log"
on run argv
	set targetPID to (item 1 of argv) as integer
	set targetTitle to item 2 of argv
	tell application "System Events"
		set matches to every process whose unix id is targetPID
		if (count of matches) is 0 then return "false"
		tell item 1 of matches
			if targetTitle is "__main__" then return ((count of windows) > 0) as text
			return (exists window targetTitle) as text
		end tell
	end tell
end run
OSA
)"; then
		return 1
	fi
	[[ "$output" == true ]]
}

wait_for_window() {
	local title="$1"
	local deadline=$((SECONDS + timeout_seconds))
	while (( SECONDS < deadline )); do
		window_exists "$title" && return 0
		kill -0 "$pid" 2>/dev/null || return 1
		sleep 0.1
	done
	return 1
}

send_key() {
	local key="$1"
	local mode="$2"
	osascript - "$pid" "$key" "$mode" <<'OSA' >>"$ui_log" 2>&1
on run argv
	set targetPID to (item 1 of argv) as integer
	set keyText to item 2 of argv
	set mode to item 3 of argv
	tell application "System Events"
		set matches to every process whose unix id is targetPID
		if (count of matches) is 0 then error "target process is unavailable"
		tell item 1 of matches
			set frontmost to true
			if mode is "cmd" then
				keystroke keyText using {command down}
			else if mode is "cmdshift" then
				keystroke keyText using {command down, shift down}
			else if mode is "cmdopt" then
				keystroke keyText using {command down, option down}
			else
				keystroke keyText
			end if
		end tell
	end tell
end run
OSA
}

press_return() {
	osascript - "$pid" <<'OSA' >>"$ui_log" 2>&1
on run argv
	set targetPID to (item 1 of argv) as integer
	tell application "System Events"
		tell first process whose unix id is targetPID
			set frontmost to true
			key code 36
		end tell
	end tell
end run
OSA
}

close_panel() {
	local title="$1"
	osascript - "$pid" "$title" <<'OSA' >>"$ui_log" 2>&1
on run argv
	set targetPID to (item 1 of argv) as integer
	set targetTitle to item 2 of argv
	tell application "System Events"
		tell first process whose unix id is targetPID
			if exists window targetTitle then click button 1 of window targetTitle
		end tell
	end tell
end run
OSA
}

run_palette_command() {
	local command="$1"
	local title="$2"
	send_key p cmdshift || return 1
	window_exists "Command Palette" || true
	send_key "$command" plain || return 1
	press_return || return 1
	wait_for_window "$title"
}

wait_for_window __main__ || fail "first window did not appear"
capture first-window || fail "could not capture first window"
send_key $'\\' cmd || fail "pane split shortcut failed"
sleep 0.3
capture panes || fail "could not capture pane state"
send_key f cmd || fail "find shortcut failed"
sleep 0.3
capture find || fail "could not capture find state"
send_key '`' cmdshift || fail "terminal shortcut failed"
wait_for_window Terminal || fail "terminal panel did not appear"
capture terminal || fail "could not capture terminal state"
close_panel Terminal || fail "could not close terminal panel"
send_key m cmdshift || fail "problems shortcut failed"
wait_for_window Problems || fail "problems panel did not appear"
capture problems || fail "could not capture problems state"
close_panel Problems || fail "could not close problems panel"
run_palette_command "Run Task" Tasks || fail "tasks panel did not appear"
capture tasks || fail "could not capture tasks state"
close_panel Tasks || fail "could not close tasks panel"
run_palette_command "Git Changes" "Git Changes" || fail "Git Changes panel did not appear"
capture git || fail "could not capture Git state"
close_panel "Git Changes" || fail "could not close Git Changes panel"
run_palette_command "Start Debugging" Debug || fail "debug panel did not appear"
capture debug || fail "could not capture debug state"
close_panel Debug || fail "could not close debug panel"
write_result passed ""
echo "PASSED scenario=release-ui-smoke result=$result log=$ui_log screenshots=$screenshots repro=$repro"
