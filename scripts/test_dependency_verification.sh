#!/usr/bin/env bash
set -euo pipefail

repo_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
verify="$repo_dir/scripts/verify_dependency_metadata.sh"
rehearsal="$repo_dir/scripts/dependency_rehearsal.sh"

"$verify"
"$verify" --help | grep -Fq -- '--require-native'
"$rehearsal" --help | grep -Fq -- '--candidate <ref>'

set +e
invalid_output="$("$verify" --invalid 2>&1)"
invalid_exit=$?
set -e
[[ "$invalid_exit" -eq 2 && "$invalid_output" == *'unknown argument'* ]] || {
	printf 'error: dependency verification argument validation failed\n' >&2
	exit 1
}
printf 'dependency verification tests passed\n'
