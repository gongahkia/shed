#!/usr/bin/env bash
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
VERIFY="$REPO_ROOT/scripts/verify-release-artifacts.sh"
TEST_ROOT="$(mktemp -d "${TMPDIR:-/tmp}/shed-release-verifier.XXXXXX")"
trap 'rm -rf "$TEST_ROOT"' EXIT

fail() {
  echo "release verifier test error: $*" >&2
  exit 1
}

sha256_file() {
  sha256sum "$1" | awk '{print $1}'
}

write_fixture() {
  local platform="$1"
  local artifact="$2"
  local signing="$3"
  local notarization="$4"
  local artifact_path="$TEST_ROOT/$artifact"
  local report="$TEST_ROOT/${artifact%.*}.validation.txt"
  local hash
  printf '%s fixture\n' "$platform" > "$artifact_path"
  hash="$(sha256_file "$artifact_path")"
  printf '%s  %s\n' "$hash" "$artifact" > "$artifact_path.sha256"
  printf '%s\n' \
    "artifact=$artifact" \
    "artifact_sha256=$hash" \
    "architecture=x64" \
    "bundle_version=2.0.0" \
    "java_feature=21" \
    "maven_build_plan=reproducible" \
    "installer_contents=validated" \
    "signing=$signing" \
    "notarization=$notarization" > "$report"
}

write_fixture macos Shed-2.0.0-macos-arm64.dmg adhoc not-requested
write_fixture windows Shed-2.0.0-windows-x64.msi NotSigned not-applicable
write_fixture linux Shed-2.0.0-linux-x64.deb not-applicable not-applicable
"$VERIFY" --artifacts "$TEST_ROOT" --version 2.0.0 >/dev/null
[[ -f "$TEST_ROOT/SHA256SUMS" ]] || fail 'missing aggregate checksums'
[[ -f "$TEST_ROOT/RELEASE_VERIFICATION.txt" ]] || fail 'missing release verification report'

printf 'altered\n' >> "$TEST_ROOT/Shed-2.0.0-windows-x64.msi"
if "$VERIFY" --artifacts "$TEST_ROOT" --version 2.0.0 >/dev/null 2>&1; then
  fail 'altered artifact was accepted'
fi
rm "$TEST_ROOT/Shed-2.0.0-linux-x64.deb"
if "$VERIFY" --artifacts "$TEST_ROOT" --version 2.0.0 >/dev/null 2>&1; then
  fail 'missing artifact was accepted'
fi

printf 'release artifact verifier tests passed\n'
