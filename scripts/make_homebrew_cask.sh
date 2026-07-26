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
bundle_id="$(/usr/libexec/PlistBuddy -c 'Print :CFBundleIdentifier' "$plist")"
dmg_path="${ITSY_DMG_PATH:-$repo_dir/dist/$app_name-$version.dmg}"
cask_token="${ITSY_CASK_TOKEN:-itsy}"
cask_path="${ITSY_CASK_PATH:-$repo_dir/dist/$cask_token.rb}"
release_tag="${ITSY_RELEASE_TAG:-v$version}"
release_base="${ITSY_RELEASE_DOWNLOAD_BASE:-https://github.com/gongahkia/itsy/releases/download}"
homepage="${ITSY_HOMEPAGE_URL:-https://github.com/gongahkia/itsy}"
desc="${ITSY_CASK_DESC:-macOS-native code editor}"

if [[ ! -f "$dmg_path" ]]; then
	echo "missing DMG: $dmg_path" >&2
	exit 1
fi

sha256="$(shasum -a 256 "$dmg_path" | awk '{print $1}')"
mkdir -p "$(dirname "$cask_path")"
cat > "$cask_path" <<RUBY
cask "$cask_token" do
  version "$version"
  sha256 "$sha256"

  url "$release_base/$release_tag/$app_name-$version.dmg"
  name "$app_name"
  desc "$desc"
  homepage "$homepage"

  app "$app_name.app"

  zap trash: [
    "~/.config/itsy",
    "~/Library/Preferences/$bundle_id.plist",
    "~/Library/Saved Application State/$bundle_id.savedState",
  ]
end
RUBY

echo "$cask_path"
