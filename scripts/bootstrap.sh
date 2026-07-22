#!/usr/bin/env bash
set -euo pipefail

usage() {
	printf '%s\n' \
		'usage: scripts/bootstrap.sh [--help]' \
		'' \
		'Initializes pinned native submodules, verifies their revisions, and resolves SwiftPM dependencies.' \
		'Runs only on macOS. It does not build the app or alter user configuration.' \
		'' \
		'environment:' \
		'  ITSY_SUBMODULE_JOBS   positive parallel submodule-update count (default: 8)' \
		'  ITSY_BOOTSTRAP_SKIP_SWIFTPM=1   skip SwiftPM dependency resolution' \
		'' \
		'exit codes:' \
		'  0  bootstrap completed' \
		'  2  unsupported platform, missing tool, or invalid argument/configuration' \
		'  other nonzero  failure propagated from preflight, Git, or SwiftPM'
}

case "${1:-}" in
	'')
		;;
	-h|--help)
		usage
		exit 0
		;;
	*)
		printf 'error: unknown argument: %s\n' "$1" >&2
		usage >&2
		exit 2
		;;
esac

repo_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$repo_dir"

if [[ "$(uname -s)" != 'Darwin' ]]; then
	printf 'error: scripts/bootstrap.sh supports macOS only\n' >&2
	exit 2
fi
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
