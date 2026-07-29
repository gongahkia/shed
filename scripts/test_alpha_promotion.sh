#!/usr/bin/env bash
set -euo pipefail

repo_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
marker="$(mktemp "$repo_dir/qa/.alpha-promotion-test.XXXXXX")"
artifacts_dir="$(mktemp -d "${TMPDIR:-/tmp}/itsy-alpha-promotion-test.XXXXXX")"
trap 'rm -f "$marker"; rm -rf "$artifacts_dir"' EXIT

set +e
"$repo_dir/scripts/alpha_promotion.sh" --artifacts "$artifacts_dir" --jobs 1 >/dev/null 2>&1
exit_code=$?
set -e
[[ "$exit_code" -eq 1 ]] || {
	echo "error: dirty checkout must fail alpha promotion" >&2
	exit 1
}

result="$(find "$artifacts_dir" -mindepth 2 -maxdepth 2 -name result.json -print -quit)"
[[ -n "$result" ]] || { echo "error: alpha promotion result is missing" >&2; exit 1; }
ruby -rjson -e '
  result = JSON.parse(File.read(ARGV.fetch(0)))
  failed = result.fetch("failed_scenario")
  abort("expected failed result") unless result.fetch("status") == "failed"
  abort("expected clean-checkout scenario") unless failed.fetch("id") == "clean-checkout"
  %w[report log remediation].each { |key| abort("missing #{key}") if failed.fetch(key).to_s.empty? }
  abort("missing revision") unless result.fetch("revision").match?(/\A[0-9a-f]{40}\z/)
  ids = result.fetch("evidence").map { |entry| entry.fetch("id") }
  abort("missing required gates") unless ids == %w[clean-checkout preflight build tests lsp-matrix private-alpha self-hosting failure-injection release-ui performance]
' "$result"
printf 'Alpha promotion script tests passed\n'
