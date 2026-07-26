#!/usr/bin/env bash
set -euo pipefail

repo_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
script="$repo_dir/scripts/provision_external_tool_fixtures.sh"
bridge="$repo_dir/scripts/js_debug_stdio_bridge.js"
manifest="$repo_dir/qa/external-tool-fixtures-v1.json"
root="$(mktemp -d "${TMPDIR:-/tmp}/itsy-fixtures-test.XXXXXX")"
trap 'rm -rf "$root"' EXIT

bash -n "$script"
node --check "$bridge"
ruby -rjson -e '
	data = JSON.parse(File.read(ARGV.fetch(0)))
	abort unless data.fetch("schema_version") == 1
	abort unless data.fetch("npm").all? { |fixture| fixture.fetch("integrity").start_with?("sha512-") }
	abort unless (data.fetch("python") + data.fetch("archives")).all? { |fixture| fixture.key?("sha256") || fixture.key?("artifacts") }
' "$manifest"
"$script" --list | grep -Fxq $'pyright\t1.1.411\tnpm'
set +e
output="$("$script" --offline --root "$root/missing" 2>&1)"
rc=$?
set -e
[[ "$rc" -eq 2 && "$output" == *'BLOCKED fixture=pyright reason=offline-cache-miss'* ]] || {
	echo "error: offline fixture miss must be explicit" >&2
	exit 1
}
printf 'External tool fixture script tests passed\n'
