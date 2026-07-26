#!/usr/bin/env bash
set -euo pipefail

root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
app="${ITSY_APP:-$root/Itsy.app}"
app_name="${ITSY_APP_NAME:-Itsy}"
out_dir="${ITSY_SCREENSHOT_DIR:-$root/docs/screenshots}"
left="${ITSY_SCREENSHOT_X:-120}"
top="${ITSY_SCREENSHOT_Y:-120}"
width="${ITSY_SCREENSHOT_WIDTH:-1312}"
height="${ITSY_SCREENSHOT_HEIGHT:-816}"
delay="${ITSY_SCREENSHOT_DELAY:-1}"
terminal_delay="${ITSY_SCREENSHOT_TERMINAL_DELAY:-3}"
build=1

usage() {
	printf 'usage: %s [--no-build] [--keep-app]\n' "$(basename "$0")" >&2
	exit 2
}

keep_app=0
while (($#)); do
	case "$1" in
		--no-build)
			build=0
			;;
		--keep-app)
			keep_app=1
			;;
		-h|--help)
			usage
			;;
		*)
			usage
			;;
	esac
	shift
done

fail() {
	printf '%s\n' "$1" >&2
	exit 1
}

if [[ "$build" == 1 ]]; then
	(cd "$root" && swift build -c release >/dev/null && APP_DIR="$app" bench/scripts/make_app.sh >/dev/null)
elif [[ ! -d "$app" ]]; then
	fail "missing app bundle: $app"
fi

if [[ "$keep_app" == 0 ]]; then
	pkill -x "$app_name" >/dev/null 2>&1 || true
fi

mkdir -p "$out_dir"
demo_dir="$(mktemp -d "${TMPDIR:-/tmp}/itsy-screens.XXXXXX")"
demo_file="$demo_dir/phase36-demo.swift"
cat > "$demo_file" <<'SWIFT'
import Foundation

struct Ticket {
	let id: Int
	let title: String
	let done: Bool
}

let queue = [
	Ticket(id: 36, title: "docs automation", done: true),
	Ticket(id: 33, title: "vim binding coverage", done: true),
	Ticket(id: 32, title: "coverage gate", done: true),
]

for item in queue where item.done {
	print("#\(item.id): \(item.title)")
}
SWIFT

cleanup() {
	rm -rf "$demo_dir"
	if [[ "$keep_app" == 0 ]]; then
		pkill -x "$app_name" >/dev/null 2>&1 || true
	fi
}
trap cleanup EXIT

osascript_checked() {
	if ! osascript "$@" >/dev/null; then
		fail "AppleScript control failed; grant Accessibility access to the invoking terminal."
	fi
}

osascript_output() {
	local output
	if ! output="$(osascript "$@")"; then
		fail "AppleScript query failed; grant Accessibility access to the invoking terminal."
	fi
	printf '%s\n' "$output"
}

open -n -a "$app" "$demo_file"

osascript_checked <<OSA
tell application "$app_name" to activate
tell application "System Events"
	repeat 100 times
		if exists process "$app_name" then
			tell process "$app_name"
				if (count of windows) > 0 then exit repeat
			end tell
		end if
		delay 0.1
	end repeat
	if not (exists process "$app_name") then error "missing process"
	tell process "$app_name"
		if (count of windows) is 0 then error "missing window"
		set frontmost to true
		set position of window 1 to {$left, $top}
		set size of window 1 to {$width, $height}
	end tell
end tell
OSA

capture() {
	local name="$1"
	sleep "$delay"
	screencapture -x -R "${left},${top},${width},${height}" "$out_dir/$name.png"
}

capture_window() {
	local name="$1"
	local window_name="$2"
	local region
	sleep "$delay"
	region="$(osascript_output <<OSA
tell application "System Events"
	tell process "$app_name"
		repeat 100 times
			if exists window "$window_name" then exit repeat
			delay 0.1
		end repeat
		if not (exists window "$window_name") then error "missing window $window_name"
		set window_position to position of window "$window_name"
		set window_size to size of window "$window_name"
		return ((item 1 of window_position as integer) as text) & "," & ((item 2 of window_position as integer) as text) & "," & ((item 1 of window_size as integer) as text) & "," & ((item 2 of window_size as integer) as text)
	end tell
end tell
OSA
)"
	screencapture -x -R "$region" "$out_dir/$name.png"
}

press_key() {
	local key="$1"
	local modifiers="$2"
	osascript_checked <<OSA
tell application "$app_name" to activate
delay 0.1
tell application "System Events"
	tell process "$app_name"
		keystroke "$key" using {$modifiers}
	end tell
end tell
OSA
}

press_escape() {
	osascript_checked <<OSA
tell application "System Events"
	tell process "$app_name"
		key code 53
	end tell
end tell
OSA
}

press_key_code() {
	local key_code="$1"
	local modifiers="$2"
	osascript_checked <<OSA
tell application "$app_name" to activate
delay 0.1
tell application "System Events"
	tell process "$app_name"
		key code $key_code using {$modifiers}
	end tell
end tell
OSA
}

type_text() {
	local text="$1"
	osascript_checked <<OSA
tell application "$app_name" to activate
delay 0.1
tell application "System Events"
	tell process "$app_name"
		keystroke "$text"
	end tell
end tell
OSA
}

press_return() {
	osascript_checked <<OSA
tell application "$app_name" to activate
delay 0.1
tell application "System Events"
	tell process "$app_name"
		key code 36
	end tell
end tell
OSA
}

close_window() {
	local window_name="$1"
	osascript_checked <<OSA
tell application "$app_name" to activate
delay 0.1
tell application "System Events"
	tell process "$app_name"
		if exists window "$window_name" then click button 1 of window "$window_name"
	end tell
end tell
OSA
}

capture itsy-main
press_key_code 50 "command down, shift down"
sleep "$terminal_delay"
type_text "clear; printf 'Itsy terminal'; sleep 300"
press_return
capture_window itsy-terminal "Terminal"
close_window "Terminal"
press_key "p" "command down, shift down"
capture itsy-command-palette
press_escape
press_key "f" "command down"
capture itsy-find
press_escape

printf 'captured screenshots in %s\n' "$out_dir"
