#!/usr/bin/env bash
set -euo pipefail

RELEASE_DIR="${1:-${RELEASE_DIR:-dist/release}}"
ALLOW_ADHOC_RELEASE="${ALLOW_ADHOC_RELEASE:-0}"
REQUIRE_NOTARIZED="${REQUIRE_NOTARIZED:-1}"

manifest="$RELEASE_DIR/release-manifest.json"
checksums="$RELEASE_DIR/SHA256SUMS"

if [[ ! -d "$RELEASE_DIR" ]]; then
    echo "release directory not found: $RELEASE_DIR" >&2
    exit 1
fi

if [[ ! -f "$manifest" ]]; then
    echo "release manifest not found: $manifest" >&2
    exit 1
fi

if [[ ! -f "$checksums" ]]; then
    echo "checksums not found: $checksums" >&2
    exit 1
fi

ruby -rjson -e 'JSON.parse(File.read(ARGV.fetch(0)))' "$manifest"
(
    cd "$RELEASE_DIR"
    shasum -a 256 -c SHA256SUMS >/dev/null
)

dmg_path="$(find "$RELEASE_DIR" -maxdepth 1 -type f -name 'Olly-*.dmg' -print -quit)"
source_path="$(find "$RELEASE_DIR" -maxdepth 1 -type f -name 'olly-*-source.tar.gz' -print -quit)"

if [[ -z "$dmg_path" ]]; then
    echo "release dmg not found in $RELEASE_DIR" >&2
    exit 1
fi

if [[ -z "$source_path" ]]; then
    echo "source tarball not found in $RELEASE_DIR" >&2
    exit 1
fi

if ! tar -tzf "$source_path" >/dev/null; then
    echo "source tarball is not readable: $source_path" >&2
    exit 1
fi

if codesign --verify --verbose=2 "$dmg_path" >/dev/null 2>&1; then
    signature_details="$(codesign -dv --verbose=4 "$dmg_path" 2>&1 || true)"
    if ! grep -q "Authority=Developer ID Application" <<<"$signature_details"; then
        echo "release dmg must be signed with Developer ID Application" >&2
        echo "$signature_details" >&2
        exit 1
    fi
else
    if [[ "$ALLOW_ADHOC_RELEASE" != "1" ]]; then
        echo "release dmg is unsigned or has an invalid signature: $dmg_path" >&2
        exit 1
    fi
    echo "validated local unsigned/ad-hoc release assets: $RELEASE_DIR"
    exit 0
fi

if [[ "$REQUIRE_NOTARIZED" == "1" ]]; then
    xcrun stapler validate "$dmg_path" >/dev/null
    spctl -a -t open --context context:primary-signature -v "$dmg_path" >/dev/null
fi

echo "validated Developer ID release assets: $RELEASE_DIR"
