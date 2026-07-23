#!/usr/bin/env bash
set -euo pipefail

repo_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
shard="$repo_dir/scripts/ci_shard.sh"

bash -n "$shard"
"$shard" --help | grep -Fq -- 'unit|ui|integration|performance|all'
set +e
invalid_output="$("$shard" --shard unknown 2>&1)"
invalid_exit=$?
set -e
[[ "$invalid_exit" -eq 2 && "$invalid_output" == *'--shard is required'* ]] || {
	printf 'error: CI shard argument validation failed\n' >&2
	exit 1
}
printf 'CI shard script tests passed\n'
