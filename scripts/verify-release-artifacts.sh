#!/usr/bin/env bash
set -euo pipefail

fail() {
  echo "release verification error: $*" >&2
  exit 1
}

usage() {
  echo "usage: $0 --artifacts <directory> --version <version>" >&2
  exit 2
}

sha256_file() {
  sha256sum "$1" | awk '{print $1}'
}

report_value() {
  local report="$1"
  local key="$2"
  local value
  value="$(awk -F= -v key="$key" '$1 == key {print substr($0, length(key) + 2); exit}' "$report")"
  [[ -n "$value" ]] || fail "missing $key in $(basename "$report")"
  printf '%s\n' "$value"
}

ARTIFACTS=""
VERSION=""
while [[ $# -gt 0 ]]; do
  case "$1" in
    --artifacts)
      [[ $# -ge 2 ]] || usage
      ARTIFACTS="$2"
      shift 2
      ;;
    --version)
      [[ $# -ge 2 ]] || usage
      VERSION="$2"
      shift 2
      ;;
    *) usage ;;
  esac
done
[[ -n "$ARTIFACTS" && -n "$VERSION" ]] || usage
[[ -d "$ARTIFACTS" ]] || fail "missing artifact directory: $ARTIFACTS"
command -v sha256sum >/dev/null 2>&1 || fail "missing required tool: sha256sum"

declare -a ARTIFACT_NAMES=(
  "Shed-$VERSION-macos-arm64.dmg"
  "Shed-$VERSION-windows-x64.msi"
  "Shed-$VERSION-linux-x64.deb"
)
declare -a REPORT_NAMES=(
  "Shed-$VERSION-macos-arm64.validation.txt"
  "Shed-$VERSION-windows-x64.validation.txt"
  "Shed-$VERSION-linux-x64.validation.txt"
)
declare -a EXPECTED_SIGNING=(adhoc NotSigned not-applicable)
declare -a EXPECTED_NOTARIZATION=(not-requested not-applicable not-applicable)

for index in "${!ARTIFACT_NAMES[@]}"; do
  artifact_name="${ARTIFACT_NAMES[$index]}"
  artifact_path="$ARTIFACTS/$artifact_name"
  checksum_path="$artifact_path.sha256"
  report_path="$ARTIFACTS/${REPORT_NAMES[$index]}"
  [[ -f "$artifact_path" ]] || fail "missing artifact: $artifact_name"
  [[ -f "$checksum_path" ]] || fail "missing checksum: $(basename "$checksum_path")"
  [[ -f "$report_path" ]] || fail "missing validation report: $(basename "$report_path")"

  read -r expected_sha256 checksum_filename extra < "$checksum_path" || fail "empty checksum: $(basename "$checksum_path")"
  [[ -z "${extra:-}" ]] || fail "invalid checksum format: $(basename "$checksum_path")"
  [[ "$expected_sha256" =~ ^[0-9a-f]{64}$ ]] || fail "invalid checksum value: $(basename "$checksum_path")"
  [[ "$checksum_filename" == "$artifact_name" ]] || fail "unexpected checksum filename: $(basename "$checksum_path")"
  [[ "$(sha256_file "$artifact_path")" == "$expected_sha256" ]] || fail "checksum mismatch: $artifact_name"
  [[ "$(report_value "$report_path" artifact)" == "$artifact_name" ]] || fail "unexpected artifact in $(basename "$report_path")"
  [[ "$(report_value "$report_path" artifact_sha256)" == "$expected_sha256" ]] || fail "report checksum mismatch: $(basename "$report_path")"
  [[ "$(report_value "$report_path" architecture)" == "x64" ]] || fail "unexpected architecture in $(basename "$report_path")"
  [[ "$(report_value "$report_path" bundle_version)" == "$VERSION" ]] || fail "unexpected version in $(basename "$report_path")"
  [[ "$(report_value "$report_path" java_feature)" == "21" ]] || fail "unexpected Java feature in $(basename "$report_path")"
  [[ "$(report_value "$report_path" maven_build_plan)" == "reproducible" ]] || fail "unverified Maven build plan in $(basename "$report_path")"
  [[ "$(report_value "$report_path" installer_contents)" == "validated" ]] || fail "unverified installer contents in $(basename "$report_path")"
  [[ "$(report_value "$report_path" signing)" == "${EXPECTED_SIGNING[$index]}" ]] || fail "unexpected signing state in $(basename "$report_path")"
  [[ "$(report_value "$report_path" notarization)" == "${EXPECTED_NOTARIZATION[$index]}" ]] || fail "unexpected notarization state in $(basename "$report_path")"
done

CHECKSUMS_PATH="$ARTIFACTS/SHA256SUMS"
REPORT_PATH="$ARTIFACTS/RELEASE_VERIFICATION.txt"
{
  for artifact_name in "${ARTIFACT_NAMES[@]}"; do
    printf '%s  %s\n' "$(sha256_file "$ARTIFACTS/$artifact_name")" "$artifact_name"
  done
} > "$CHECKSUMS_PATH"
printf '%s\n' \
  "version=$VERSION" \
  "macos_artifact=${ARTIFACT_NAMES[0]}" \
  "macos_signing=${EXPECTED_SIGNING[0]}" \
  "macos_notarization=${EXPECTED_NOTARIZATION[0]}" \
  "windows_artifact=${ARTIFACT_NAMES[1]}" \
  "windows_signing=${EXPECTED_SIGNING[1]}" \
  "windows_notarization=${EXPECTED_NOTARIZATION[1]}" \
  "linux_artifact=${ARTIFACT_NAMES[2]}" \
  "linux_signing=${EXPECTED_SIGNING[2]}" \
  "linux_notarization=${EXPECTED_NOTARIZATION[2]}" \
  "checksums=verified" \
  "installer_contents=verified" > "$REPORT_PATH"

printf 'release artifacts verified: %s\nchecksums: %s\nreport: %s\n' "$ARTIFACTS" "$CHECKSUMS_PATH" "$REPORT_PATH"
