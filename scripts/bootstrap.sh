#!/usr/bin/env bash
set -euo pipefail

repo_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$repo_dir"

if ! command -v git >/dev/null 2>&1; then
	printf 'error: missing command: git\n' >&2
	exit 2
fi
if ! command -v swift >/dev/null 2>&1; then
	printf 'error: missing command: swift\n' >&2
	exit 2
fi

submodule_jobs="${ITSY_SUBMODULE_JOBS:-8}"
case "$submodule_jobs" in
	''|*[!0-9]*)
		printf 'error: ITSY_SUBMODULE_JOBS must be a positive integer\n' >&2
		exit 2
		;;
	0)
		printf 'error: ITSY_SUBMODULE_JOBS must be greater than zero\n' >&2
		exit 2
		;;
esac
repair_incomplete_submodules() {
	while IFS=$'\t' read -r _ path; do
		if [[ -e "$path/.git" ]] && ! git -C "$path" rev-parse --verify HEAD >/dev/null 2>&1; then
			git submodule deinit --force -- "$path" >/dev/null 2>&1 || true
			if [[ -f "$path/.git" ]]; then
				rm -f "$path/.git"
			fi
		fi
	done < <(git ls-files --stage | awk '$1 == "160000" { print $2 "\t" $4 }')
}

git submodule sync
repair_incomplete_submodules
git submodule update --init --checkout --jobs "$submodule_jobs"
repair_incomplete_submodules
git submodule update --init --checkout
scripts/verify_native_dependencies.sh

if [[ "${ITSY_BOOTSTRAP_SKIP_SWIFTPM:-0}" == "1" ]]; then
	printf 'bootstrap completed without SwiftPM resolution\n'
	exit 0
fi

swift package resolve
printf 'bootstrap completed\n'
