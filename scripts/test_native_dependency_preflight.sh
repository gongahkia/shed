#!/usr/bin/env bash
set -euo pipefail

repo_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
verifier_source="$repo_dir/scripts/verify_native_dependencies.sh"
fixture_dir="$(mktemp -d "${TMPDIR:-/tmp}/itsy-native-preflight.XXXXXX")"
trap 'rm -rf "$fixture_dir"' EXIT

fail() {
	printf 'error: %s\n' "$1" >&2
	exit 1
}

assert_contains() {
	local output="$1"
	local expected="$2"
	[[ "$output" == *"$expected"* ]] || fail "expected output: $expected"
}

run_verifier() {
	set +e
	verifier_output="$("$fixture_repo/scripts/verify_native_dependencies.sh" 2>&1)"
	verifier_exit=$?
	set -e
}

origin_repo="$fixture_dir/origin"
fixture_repo="$fixture_dir/super"
module_path='deps/native'

git init --quiet "$origin_repo"
git -C "$origin_repo" config user.email 'test@example.invalid'
git -C "$origin_repo" config user.name 'Itsy Test'
git -C "$origin_repo" commit --allow-empty --quiet -m initial
expected_commit="$(git -C "$origin_repo" rev-parse HEAD)"
git -C "$origin_repo" commit --allow-empty --quiet -m mismatch
mismatch_commit="$(git -C "$origin_repo" rev-parse HEAD)"

git init --quiet "$fixture_repo"
git -C "$fixture_repo" config user.email 'test@example.invalid'
git -C "$fixture_repo" config user.name 'Itsy Test'
git -C "$fixture_repo" -c protocol.file.allow=always submodule add --quiet "$origin_repo" "$module_path"
git -C "$fixture_repo/$module_path" checkout --quiet --detach "$expected_commit"
git -C "$fixture_repo" add .gitmodules "$module_path"
git -C "$fixture_repo" commit --quiet -m 'add native dependency fixture'
mkdir -p "$fixture_repo/scripts"
cp "$verifier_source" "$fixture_repo/scripts/verify_native_dependencies.sh"

if git -C "$fixture_repo/$module_path" symbolic-ref --quiet HEAD >/dev/null 2>&1; then
	fail 'fixture submodule must use detached HEAD'
fi
run_verifier
[[ "$verifier_exit" -eq 0 ]] || fail "clean fixture failed: $verifier_output"
assert_contains "$verifier_output" $'ok\tdeps/native\tcommit='"$expected_commit"

git -C "$fixture_repo/$module_path" checkout --quiet --detach "$mismatch_commit"
run_verifier
[[ "$verifier_exit" -eq 1 ]] || fail "mismatched fixture exit: $verifier_exit"
assert_contains "$verifier_output" $'revision-mismatch\tdeps/native\texpected='"$expected_commit"$'\tactual='"$mismatch_commit"

git -C "$fixture_repo/$module_path" checkout --quiet --detach "$expected_commit"
touch "$fixture_repo/$module_path/untracked-fixture"
run_verifier
[[ "$verifier_exit" -eq 1 ]] || fail "dirty fixture exit: $verifier_exit"
assert_contains "$verifier_output" $'dirty\tdeps/native\texpected='"$expected_commit"

git -C "$fixture_repo" submodule deinit --force -- "$module_path" >/dev/null
run_verifier
[[ "$verifier_exit" -eq 1 ]] || fail "missing fixture exit: $verifier_exit"
assert_contains "$verifier_output" $'missing\tdeps/native\texpected='"$expected_commit"

mkdir -p "$fixture_repo/$module_path"
touch "$fixture_repo/$module_path/.git"
run_verifier
[[ "$verifier_exit" -eq 1 ]] || fail "unreadable fixture exit: $verifier_exit"
assert_contains "$verifier_output" $'unreadable\tdeps/native\texpected='"$expected_commit"

printf 'native dependency preflight fixtures passed\n'
