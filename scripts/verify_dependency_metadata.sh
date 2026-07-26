#!/usr/bin/env bash
set -euo pipefail

usage() {
	printf '%s\n' \
		'usage: scripts/verify_dependency_metadata.sh [--require-native]' \
		'' \
		'Validates SwiftPM pins, reviewed dependency metadata, and optionally every initialized native submodule license.'
}

require_native=0
case "${1:-}" in
	'') ;;
	--require-native) require_native=1 ;;
	-h|--help) usage; exit 0 ;;
	*) printf 'error: unknown argument: %s\n' "$1" >&2; usage >&2; exit 2 ;;
esac

repo_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$repo_dir"
command -v python3 >/dev/null 2>&1 || { printf 'error: missing command: python3\n' >&2; exit 2; }

python3 - "Package.resolved" "docs/dependency-metadata.json" <<'PY'
import json
import re
import sys

pins_path, metadata_path = sys.argv[1:]
with open(pins_path, encoding="utf-8") as file:
    pins = json.load(file)
with open(metadata_path, encoding="utf-8") as file:
    metadata = json.load(file)

if metadata.get("schemaVersion") != 1:
    raise SystemExit("error: unsupported dependency metadata schema")
entries = metadata.get("swiftPM")
if not isinstance(entries, dict) or not entries:
    raise SystemExit("error: dependency metadata must declare SwiftPM entries")

seen = set()
for pin in pins.get("pins", []):
    identity = pin.get("identity")
    state = pin.get("state", {})
    revision = state.get("revision")
    location = pin.get("location")
    if not isinstance(identity, str) or not identity:
        raise SystemExit("error: Package.resolved pin is missing identity")
    if identity in seen:
        raise SystemExit(f"error: duplicate SwiftPM pin: {identity}")
    seen.add(identity)
    if not isinstance(revision, str) or not re.fullmatch(r"[0-9a-f]{40}", revision):
        raise SystemExit(f"error: {identity} has an invalid immutable revision")
    if not isinstance(location, str) or not location.startswith("https://"):
        raise SystemExit(f"error: {identity} must use an HTTPS source location")
    entry = entries.get(identity)
    if not isinstance(entry, dict):
        raise SystemExit(f"error: missing dependency metadata for {identity}")
    if not isinstance(entry.get("license"), str) or not entry["license"]:
        raise SystemExit(f"error: {identity} is missing license metadata")
    if not isinstance(entry.get("licenseFile"), str) or not entry["licenseFile"]:
        raise SystemExit(f"error: {identity} is missing license file metadata")
    if entry.get("review") != "dependency-update":
        raise SystemExit(f"error: {identity} requires review=dependency-update")

extra = sorted(set(entries) - seen)
if extra:
    raise SystemExit("error: metadata has no matching SwiftPM pin: " + ", ".join(extra))
print(f"SwiftPM dependency metadata passed: {len(seen)} pin(s)")
PY

if ((require_native == 0)); then
	printf 'native license verification skipped\n'
	exit 0
fi

failures=0
count=0
while IFS=$'\t' read -r _ path; do
	count=$((count + 1))
	if [[ ! -d "$path" ]] || ! git -C "$path" rev-parse --verify HEAD >/dev/null 2>&1; then
		printf 'missing-native-submodule\t%s\tremediation=scripts/bootstrap.sh\n' "$path" >&2
		failures=$((failures + 1))
		continue
	fi
	license="$(find "$path" -maxdepth 1 -type f \( -iname 'LICENSE' -o -iname 'LICENSE.md' -o -iname 'LICENSE.txt' -o -iname 'COPYING' -o -iname 'COPYING.md' \) -print -quit)"
	if [[ -z "$license" ]]; then
		printf 'missing-native-license\t%s\tremediation=add root license metadata before updating this pin\n' "$path" >&2
		failures=$((failures + 1))
		continue
	fi
	printf 'native-license\t%s\t%s\n' "$path" "${license#"$path"/}"
done < <(git ls-files --stage | awk '$1 == "160000" { print $2 "\t" $4 }')

if ((count == 0)); then
	printf 'error: no native submodules declared\n' >&2
	exit 2
fi
if ((failures > 0)); then
	printf 'dependency metadata verification failed: %d issue(s)\n' "$failures" >&2
	exit 1
fi
printf 'native dependency license verification passed: %d submodule(s)\n' "$count"
