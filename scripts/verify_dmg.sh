#!/usr/bin/env bash
set -euo pipefail

repo_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
dmg_path="${1:-${ITSY_DMG_PATH:-}}"

if [[ -z "$dmg_path" ]]; then
	app_dir="${ITSY_APP_DIR:-$repo_dir/Itsy.app}"
	app_name="Itsy"
	version="0.1.0"
	if [[ -f "$app_dir/Contents/Info.plist" ]]; then
		app_name="$(/usr/libexec/PlistBuddy -c 'Print :CFBundleName' "$app_dir/Contents/Info.plist")"
		version="$(/usr/libexec/PlistBuddy -c 'Print :CFBundleShortVersionString' "$app_dir/Contents/Info.plist")"
	fi
	dmg_path="$repo_dir/dist/$app_name-$version.dmg"
fi

if [[ ! -f "$dmg_path" ]]; then
	echo "missing DMG: $dmg_path" >&2
	exit 1
fi

mount_dir="$(mktemp -d "${TMPDIR:-/tmp}/itsy-dmg.XXXXXX")"
cleanup() {
	hdiutil detach "$mount_dir" -quiet >/dev/null 2>&1 || true
	rm -rf "$mount_dir"
}
trap cleanup EXIT

hdiutil attach "$dmg_path" -nobrowse -readonly -mountpoint "$mount_dir" >/dev/null
shopt -s nullglob
apps=("$mount_dir"/*.app)
shopt -u nullglob

if [[ "${#apps[@]}" -ne 1 ]]; then
	echo "expected exactly one .app in DMG; found ${#apps[@]}" >&2
	exit 1
fi

app="${apps[0]}"
plist="$app/Contents/Info.plist"
executable="$app/Contents/MacOS/Itsy"
bundle_id="$(/usr/libexec/PlistBuddy -c 'Print :CFBundleIdentifier' "$plist")"

if [[ "$bundle_id" != "dev.itsy.editor" ]]; then
	echo "unexpected bundle id: $bundle_id" >&2
	exit 1
fi

if [[ ! -x "$executable" ]]; then
	echo "missing executable: $executable" >&2
	exit 1
fi

if [[ ! -e "$mount_dir/Applications" ]]; then
	echo "missing Applications link" >&2
	exit 1
fi

if ! codesign --verify --deep --strict "$app" >/dev/null 2>&1; then
	if [[ "${ITSY_ALLOW_UNSIGNED_DMG:-0}" != "1" ]]; then
		echo "mounted app is not signed" >&2
		exit 1
	fi
	echo "warning: mounted app is unsigned" >&2
fi

echo "$dmg_path"
