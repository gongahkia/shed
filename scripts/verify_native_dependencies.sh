#!/usr/bin/env bash
set -euo pipefail

repo_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$repo_dir"

if ! git rev-parse --is-inside-work-tree >/dev/null 2>&1; then
	printf 'error: not inside a Git work tree\n' >&2
	exit 2
fi

failures=0
submodule_count=0
while IFS=$'\t' read -r expected path; do
	submodule_count=$((submodule_count + 1))
	if [[ ! -e "$path/.git" ]]; then
		printf 'missing\t%s\texpected=%s\tremediation=git submodule update --init --checkout\n' "$path" "$expected" >&2
		failures=$((failures + 1))
		continue
	fi
	actual=""
	if git -C "$path" rev-parse --verify HEAD >/dev/null 2>&1; then
		actual="$(git -C "$path" rev-parse --verify HEAD)"
	fi
	if [[ "$actual" != "$expected" ]]; then
		printf 'revision-mismatch\t%s\texpected=%s\tactual=%s\tremediation=git submodule update --init --checkout\n' "$path" "$expected" "${actual:-unreadable}" >&2
		failures=$((failures + 1))
		continue
	fi
	if [[ -n "$(git -C "$path" status --porcelain --untracked-files=all --ignore-submodules=all 2>/dev/null)" ]]; then
		printf 'dirty\t%s\texpected=%s\tremediation=commit/stash changes or git -C %q checkout -- .\n' "$path" "$expected" "$path" >&2
		failures=$((failures + 1))
		continue
	fi
	printf 'ok\t%s\tcommit=%s\n' "$path" "$actual"
done < <(git ls-files --stage | awk '$1 == "160000" { print $2 "\t" $4 }')

if ((submodule_count == 0)); then
	printf 'error: no Git submodules are declared\n' >&2
	exit 2
fi

if ((failures > 0)); then
	printf 'native dependency verification failed: %d issue(s)\n' "$failures" >&2
	exit 1
fi

printf 'native dependency verification passed: %d submodule(s)\n' "$submodule_count"
