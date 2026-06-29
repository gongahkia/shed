#!/usr/bin/env bash
set -euo pipefail

repo_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
app_dir="${ITSY_APP_DIR:-$repo_dir/Itsy.app}"
version="0.1.0"
if [[ -f "$app_dir/Contents/Info.plist" ]]; then
	version="$(/usr/libexec/PlistBuddy -c 'Print :CFBundleShortVersionString' "$app_dir/Contents/Info.plist")"
fi
dmg_path="${ITSY_DMG_PATH:-$repo_dir/dist/Itsy-$version.dmg}"

if [[ ! -f "$dmg_path" ]]; then
	echo "missing DMG: $dmg_path" >&2
	exit 1
fi

profile="${ITSY_NOTARY_PROFILE:-}"
notary_args=(submit "$dmg_path" --wait)
if [[ -n "$profile" ]]; then
	notary_args+=(--keychain-profile "$profile")
else
	apple_id="${ITSY_NOTARY_APPLE_ID:-}"
	team_id="${ITSY_NOTARY_TEAM_ID:-}"
	password="${ITSY_NOTARY_PASSWORD:-}"
	if [[ -z "$apple_id" || -z "$team_id" || -z "$password" ]]; then
		echo "set ITSY_NOTARY_PROFILE or ITSY_NOTARY_APPLE_ID/ITSY_NOTARY_TEAM_ID/ITSY_NOTARY_PASSWORD" >&2
		exit 1
	fi
	notary_args+=(--apple-id "$apple_id" --team-id "$team_id" --password "$password")
fi

xcrun notarytool "${notary_args[@]}"
xcrun stapler staple "$dmg_path"
xcrun stapler validate "$dmg_path"
spctl -a -t open --context context:primary-signature -v "$dmg_path"
echo "$dmg_path"
