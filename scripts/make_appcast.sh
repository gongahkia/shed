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
release_notes_path="${SPARKLE_RELEASE_NOTES_PATH:-}"
require_release_notes="${SPARKLE_REQUIRE_RELEASE_NOTES:-0}"
lsp_catalog_source_path="${ITSY_LSP_CATALOG_SOURCE_PATH:-}"
lsp_catalog_private_key="${ITSY_LSP_CATALOG_PRIVATE_KEY:-}"
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

if [[ "$require_release_notes" == "1" && -z "$release_notes_path" ]]; then
	echo "missing versioned release notes; set SPARKLE_RELEASE_NOTES_PATH" >&2
	exit 1
fi

if [[ -n "$release_notes_path" && ! -f "$release_notes_path" ]]; then
	echo "missing release notes: $release_notes_path" >&2
	exit 1
fi
if [[ -n "$lsp_catalog_source_path" || -n "$lsp_catalog_private_key" ]]; then
	[[ -n "$lsp_catalog_source_path" && -f "$lsp_catalog_source_path" ]] || { echo "missing ITSY_LSP_CATALOG_SOURCE_PATH" >&2; exit 1; }
	[[ -n "$lsp_catalog_private_key" ]] || { echo "missing ITSY_LSP_CATALOG_PRIVATE_KEY" >&2; exit 1; }
fi

mkdir -p "$updates_dir"
cp "$dmg_path" "$updates_dir/"
if [[ -n "$release_notes_path" ]]; then
	archive_name="$(basename "$dmg_path")"
	release_notes_name="${archive_name%.*}.md"
	cp "$release_notes_path" "$updates_dir/$release_notes_name"
fi
if [[ -n "$lsp_catalog_source_path" ]]; then
	ITSY_LSP_CATALOG_PRIVATE_KEY="$lsp_catalog_private_key" "$repo_dir/scripts/sign_lsp_catalog.swift" "$lsp_catalog_source_path" "$updates_dir/lsp-catalog.json"
fi
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

find "$updates_dir" -maxdepth 1 \( -name '*.xml' -o -name 'appcast*.rss' -o -name '*.md' \) -print
