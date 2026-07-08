#!/usr/bin/env bash
set -euo pipefail

repo_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
app_dir="${ITSY_APP_DIR:-$repo_dir/Itsy.app}"
identity="${ITSY_CODESIGN_IDENTITY:-}"

if [[ ! -d "$app_dir" ]]; then
	(cd "$repo_dir" && bench/scripts/make_app.sh >/dev/null)
fi

if [[ -z "$identity" ]]; then
	identities=()
	while IFS= read -r line; do
		identities+=("$line")
	done < <(security find-identity -v -p codesigning | sed -n 's/.*"\(Developer ID Application:[^"]*\)".*/\1/p')
	if [[ "${#identities[@]}" -ne 1 ]]; then
		echo "expected exactly one Developer ID Application identity; found ${#identities[@]}" >&2
		echo "set ITSY_CODESIGN_IDENTITY='Developer ID Application: <name> (<team>)'" >&2
		exit 1
	fi
	identity="${identities[0]}"
fi

sign_runtime() {
	codesign --force --sign "$identity" --options runtime --timestamp "$1"
}

if [[ -d "$app_dir/Contents/Frameworks" ]]; then
	sparkle_framework="$app_dir/Contents/Frameworks/Sparkle.framework"
	if [[ -d "$sparkle_framework" ]]; then
		sparkle_version_dir="$sparkle_framework/Versions/Current"
		if [[ ! -d "$sparkle_version_dir" ]]; then
			sparkle_version_dir="$(find "$sparkle_framework/Versions" -mindepth 1 -maxdepth 1 -type d -print | sort | tail -n 1)"
		fi
		for helper in "$sparkle_version_dir/XPCServices/Installer.xpc" "$sparkle_version_dir/Autoupdate" "$sparkle_version_dir/Updater.app"; do
			[[ -e "$helper" ]] || continue
			sign_runtime "$helper"
		done
		downloader="$sparkle_version_dir/XPCServices/Downloader.xpc"
		if [[ -e "$downloader" ]]; then
			codesign --force --sign "$identity" --options runtime --timestamp --preserve-metadata=entitlements "$downloader"
		fi
		sign_runtime "$sparkle_framework"
	fi
	while IFS= read -r -d '' code_path; do
		sign_runtime "$code_path"
	done < <(find "$app_dir/Contents/Frameworks" -path "$sparkle_framework" -prune -o -type f \( -name '*.dylib' -o -perm -111 \) -print0)
fi

sign_runtime "$app_dir"
codesign --verify --deep --strict --verbose=2 "$app_dir"
codesign -dvvv --entitlements :- "$app_dir"
echo "$app_dir"
