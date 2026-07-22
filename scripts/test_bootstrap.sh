#!/usr/bin/env bash
set -euo pipefail

repo_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
bootstrap="$repo_dir/scripts/bootstrap.sh"

fail() {
	printf 'error: %s\n' "$1" >&2
	exit 1
}

assert_contains() {
	local output="$1"
	local expected="$2"
	[[ "$output" == *"$expected"* ]] || fail "expected output: $expected"
}

help_output="$("$bootstrap" --help)"
assert_contains "$help_output" 'ITSY_SUBMODULE_JOBS'
assert_contains "$help_output" 'ITSY_BOOTSTRAP_SKIP_SWIFTPM=1'
assert_contains "$help_output" '0  bootstrap completed'
assert_contains "$help_output" '2  unsupported platform, missing tool, or invalid argument/configuration'
assert_contains "$help_output" 'other nonzero  failure propagated from preflight, Git, or SwiftPM'

set +e
unknown_output="$("$bootstrap" --unknown 2>&1)"
unknown_exit=$?
invalid_jobs_output="$(ITSY_SUBMODULE_JOBS=0 "$bootstrap" 2>&1)"
invalid_jobs_exit=$?
set -e

[[ "$unknown_exit" -eq 2 ]] || fail "unknown argument exit: $unknown_exit"
assert_contains "$unknown_output" 'error: unknown argument: --unknown'
[[ "$invalid_jobs_exit" -eq 2 ]] || fail "invalid jobs exit: $invalid_jobs_exit"
assert_contains "$invalid_jobs_output" 'error: ITSY_SUBMODULE_JOBS must be greater than zero'

printf 'bootstrap CLI tests passed\n'
