#!/usr/bin/env bash
set -euo pipefail

repo_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
app_dir="${ITSY_APP_DIR:-$repo_dir/Itsy.app}"
app_name="Itsy"
version="0.1.0"
if [[ -f "$app_dir/Contents/Info.plist" ]]; then
	app_name="$(/usr/libexec/PlistBuddy -c 'Print :CFBundleName' "$app_dir/Contents/Info.plist")"
	version="$(/usr/libexec/PlistBuddy -c 'Print :CFBundleShortVersionString' "$app_dir/Contents/Info.plist")"
fi

dmg_path="${ITSY_DMG_PATH:-$repo_dir/dist/$app_name-$version.dmg}"
updates_dir="${SPARKLE_UPDATES_DIR:-$repo_dir/dist/sparkle}"
generate_appcast="${SPARKLE_GENERATE_APPCAST:-generate_appcast}"

if [[ ! -f "$dmg_path" ]]; then
	echo "missing DMG: $dmg_path" >&2
	exit 1
fi

if ! command -v "$generate_appcast" >/dev/null 2>&1; then
	echo "missing Sparkle generate_appcast tool; set SPARKLE_GENERATE_APPCAST=/path/to/generate_appcast" >&2
	exit 1
fi

mkdir -p "$updates_dir"
cp "$dmg_path" "$updates_dir/"
"$generate_appcast" "$updates_dir"

find "$updates_dir" -maxdepth 1 \( -name '*.xml' -o -name 'appcast*.rss' \) -print
