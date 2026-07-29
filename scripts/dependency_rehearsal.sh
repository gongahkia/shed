#!/usr/bin/env bash
set -euo pipefail

usage() {
	printf '%s\n' \
		'usage: scripts/dependency_rehearsal.sh --base <ref> --candidate <ref>' \
		'' \
		'Creates isolated temporary worktrees for the base and candidate refs, then bootstraps, builds, and tests both.' \
		'No dependency is upgraded automatically.'
}

base_ref=''
candidate_ref=''
while (($#)); do
	case "$1" in
		--base) base_ref="${2:-}"; shift 2 ;;
		--candidate) candidate_ref="${2:-}"; shift 2 ;;
		-h|--help) usage; exit 0 ;;
		*) printf 'error: unknown or incomplete argument: %s\n' "$1" >&2; usage >&2; exit 2 ;;
	esac
done

valid_ref() { [[ "$1" =~ ^[A-Za-z0-9._/@-]+$ && "$1" != -* && "$1" != *..* ]]; }
if [[ -z "$base_ref" || -z "$candidate_ref" ]] || ! valid_ref "$base_ref" || ! valid_ref "$candidate_ref"; then
	printf 'error: --base and --candidate must be safe Git refs\n' >&2
	exit 2
fi

repo_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$repo_dir"
git diff --quiet || { printf 'error: dependency rehearsal requires a clean worktree\n' >&2; exit 2; }

rehearsal_root="$(mktemp -d "${TMPDIR:-/tmp}/itsy-dependency-rehearsal.XXXXXX")"
cleanup() {
	for directory in "$rehearsal_root/base" "$rehearsal_root/candidate"; do
		if [[ -d "$directory" ]]; then
			git worktree remove --force "$directory" || true
		fi
	done
	rmdir "$rehearsal_root" 2>/dev/null || true
}
trap cleanup EXIT

run_ref() {
	local name="$1"
	local ref="$2"
	local directory="$rehearsal_root/$name"
	git worktree add --detach "$directory" "$ref"
	(
		cd "$directory"
		scripts/bootstrap.sh
		scripts/verify_dependency_metadata.sh --require-native
		swift build --target ItsyApp --jobs 1
		swift test --jobs 1
	)
}

run_ref base "$base_ref"
run_ref candidate "$candidate_ref"
printf 'dependency rehearsal passed: base=%s candidate=%s\n' "$base_ref" "$candidate_ref"
