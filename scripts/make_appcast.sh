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
download_url_prefix="${SPARKLE_DOWNLOAD_URL_PREFIX:-}"
private_key="${SPARKLE_PRIVATE_KEY:-}"
ed_key_file="${SPARKLE_ED_KEY_FILE:-}"
require_ed_key="${SPARKLE_REQUIRE_ED_KEY:-0}"
default_generate_appcast="$repo_dir/.build/artifacts/sparkle/Sparkle/bin/generate_appcast"
generate_appcast="${SPARKLE_GENERATE_APPCAST:-}"
if [[ -z "$generate_appcast" ]]; then
	if [[ -x "$default_generate_appcast" ]]; then
		generate_appcast="$default_generate_appcast"
	else
		generate_appcast="generate_appcast"
	fi
fi

if [[ ! -f "$dmg_path" ]]; then
	echo "missing DMG: $dmg_path" >&2
	exit 1
fi

if ! command -v "$generate_appcast" >/dev/null 2>&1; then
	echo "missing Sparkle generate_appcast tool; run swift build or set SPARKLE_GENERATE_APPCAST=/path/to/generate_appcast" >&2
	exit 1
fi

if [[ "$require_ed_key" == "1" && -z "$private_key" && -z "$ed_key_file" ]]; then
	echo "missing Sparkle EdDSA private key; set SPARKLE_PRIVATE_KEY or SPARKLE_ED_KEY_FILE" >&2
	exit 1
fi

mkdir -p "$updates_dir"
cp "$dmg_path" "$updates_dir/"
appcast_args=()
if [[ -n "$download_url_prefix" ]]; then
	appcast_args+=(--download-url-prefix "$download_url_prefix")
fi
if [[ -n "$ed_key_file" ]]; then
	appcast_args+=(--ed-key-file "$ed_key_file")
elif [[ -n "$private_key" ]]; then
	appcast_args+=(--ed-key-file -)
fi
appcast_args+=("$updates_dir")

if [[ -n "$private_key" && -z "$ed_key_file" ]]; then
	printf '%s' "$private_key" | "$generate_appcast" "${appcast_args[@]}"
else
	"$generate_appcast" "${appcast_args[@]}"
fi

find "$updates_dir" -maxdepth 1 \( -name '*.xml' -o -name 'appcast*.rss' \) -print
