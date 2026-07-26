#!/usr/bin/env bash
set -euo pipefail

APP_NAME="${APP_NAME:-Olly}"
VERSION="${VERSION:-${GITHUB_REF_NAME:-}}"
DIST_DIR="${DIST_DIR:-dist}"
RELEASE_DIR="${RELEASE_DIR:-$DIST_DIR/release}"
DMG_PATH="${DMG_PATH:-$DIST_DIR/$APP_NAME.dmg}"

if [[ -z "$VERSION" ]]; then
    echo "VERSION or GITHUB_REF_NAME is required" >&2
    exit 1
fi

if [[ ! -f "$DMG_PATH" ]]; then
    echo "dmg not found: $DMG_PATH" >&2
    exit 1
fi

repo_root="$(git rev-parse --show-toplevel)"
git_revision="$(git -C "$repo_root" rev-parse HEAD)"
dmg_name="$APP_NAME-$VERSION.dmg"
source_name="olly-$VERSION-source.tar.gz"
manifest="$RELEASE_DIR/release-manifest.json"

rm -rf "$RELEASE_DIR"
mkdir -p "$RELEASE_DIR"
cp "$DMG_PATH" "$RELEASE_DIR/$dmg_name"
git -C "$repo_root" archive --format tar.gz --prefix "olly-$VERSION/" -o "$RELEASE_DIR/$source_name" HEAD

(
    cd "$RELEASE_DIR"
    shasum -a 256 "$dmg_name" "$source_name" > SHA256SUMS
)

sha256() {
    shasum -a 256 "$1" | awk '{print $1}'
}

bytes() {
    stat -f %z "$1"
}

created_at="$(date -u '+%Y-%m-%dT%H:%M:%SZ')"
dmg_sha="$(sha256 "$RELEASE_DIR/$dmg_name")"
source_sha="$(sha256 "$RELEASE_DIR/$source_name")"
dmg_bytes="$(bytes "$RELEASE_DIR/$dmg_name")"
source_bytes="$(bytes "$RELEASE_DIR/$source_name")"

cat >"$manifest" <<JSON
{
  "schemaVersion": 1,
  "appName": "$APP_NAME",
  "version": "$VERSION",
  "gitRevision": "$git_revision",
  "createdAt": "$created_at",
  "artifacts": [
    {
      "kind": "dmg",
      "path": "$dmg_name",
      "sha256": "$dmg_sha",
      "bytes": $dmg_bytes
    },
    {
      "kind": "source",
      "path": "$source_name",
      "sha256": "$source_sha",
      "bytes": $source_bytes
    }
  ],
  "notarization": {
    "required": true
  }
}
JSON

ruby -rjson -e 'JSON.parse(File.read(ARGV.fetch(0)))' "$manifest"
echo "$RELEASE_DIR"
