#!/usr/bin/env bash
set -euo pipefail

repo_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
app_dir="${ITSY_APP_DIR:-$repo_dir/Itsy.app}"

if [[ ! -d "$app_dir" ]]; then
	(cd "$repo_dir" && bench/scripts/make_app.sh >/dev/null)
fi

plist="$app_dir/Contents/Info.plist"
app_name="$(/usr/libexec/PlistBuddy -c 'Print :CFBundleName' "$plist")"
version="$(/usr/libexec/PlistBuddy -c 'Print :CFBundleShortVersionString' "$plist")"
dmg_path="${ITSY_DMG_PATH:-$repo_dir/dist/$app_name-$version.dmg}"
staging_dir="$repo_dir/.build/dmg/$app_name"

if ! codesign --verify --deep --strict "$app_dir" >/dev/null 2>&1; then
	if [[ "${ITSY_ALLOW_UNSIGNED_DMG:-0}" != "1" ]]; then
		echo "app is not signed; run scripts/codesign.sh or set ITSY_ALLOW_UNSIGNED_DMG=1" >&2
		exit 1
	fi
	echo "warning: building unsigned DMG" >&2
fi

rm -rf "$staging_dir"
mkdir -p "$staging_dir" "$(dirname "$dmg_path")"
ditto "$app_dir" "$staging_dir/$app_name.app"
rm -f "$dmg_path" "$dmg_path.sha256"

if command -v create-dmg >/dev/null 2>&1; then
	create-dmg \
		--volname "$app_name $version" \
		--window-pos 200 120 \
		--window-size 640 360 \
		--icon-size 96 \
		--icon "$app_name.app" 176 160 \
		--app-drop-link 464 160 \
		--no-internet-enable \
		"$dmg_path" \
		"$staging_dir" >/dev/null
else
	ln -s /Applications "$staging_dir/Applications"
	hdiutil create -volname "$app_name $version" -srcfolder "$staging_dir" -ov -format UDZO "$dmg_path" >/dev/null
fi

dmg_identity="${ITSY_DMG_CODESIGN_IDENTITY:-${ITSY_CODESIGN_IDENTITY:-}}"
if [[ -n "$dmg_identity" ]]; then
	codesign --force --sign "$dmg_identity" --timestamp "$dmg_path"
	codesign --verify --verbose=2 "$dmg_path"
fi

hdiutil verify "$dmg_path" >/dev/null
"$repo_dir/scripts/verify_dmg.sh" "$dmg_path" >/dev/null
shasum -a 256 "$dmg_path" > "$dmg_path.sha256"
cat "$dmg_path.sha256"
echo "$dmg_path"
