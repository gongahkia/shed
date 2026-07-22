#!/usr/bin/env bash
set -euo pipefail

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
repo_dir="$(cd "$script_dir/.." && pwd)"
artifacts_dir="${ITSY_SELF_HOSTING_ARTIFACTS:-$repo_dir/.build/self-hosting}"
jobs="${ITSY_SELF_HOSTING_JOBS:-1}"

usage() {
	echo "usage: $0 [--artifacts directory] [--jobs count]" >&2
}

while [[ "$#" -gt 0 ]]; do
	case "$1" in
	--artifacts)
		artifacts_dir="$2"
		shift 2
		;;
	--jobs)
		jobs="$2"
		shift 2
		;;
	*)
		usage
		exit 2
		;;
	esac
done

if [[ ! "$jobs" =~ ^[1-9][0-9]*$ ]]; then
	echo "INVALID jobs=$jobs" >&2
	exit 2
fi
missing=()
for requirement in swift git /bin/sh; do
	if [[ "$requirement" == /* ]]; then
		[[ -x "$requirement" ]] || missing+=("$requirement")
	elif ! command -v "$requirement" >/dev/null 2>&1; then
		missing+=("$requirement")
	fi
done
if [[ "${#missing[@]}" -gt 0 ]]; then
	echo "BLOCKED requirements=$(IFS=,; echo "${missing[*]}")" >&2
	exit 2
fi

mkdir -p "$artifacts_dir"
artifacts_dir="$(cd "$artifacts_dir" && pwd)"
run_dir="$artifacts_dir/run-$(date -u +%Y%m%dT%H%M%SZ)-$$"
mkdir -p "$run_dir"
repro="$run_dir/repro.sh"
printf '%s\n' '#!/usr/bin/env bash' 'set -euo pipefail' "cd $(printf '%q' "$repo_dir")" "ITSY_SELF_HOSTING_ARTIFACTS=$(printf '%q' "$run_dir") swift test --filter integrationSelfHostingWorkspaceRunsEditNavigateTaskGitAndDebugFlows --jobs $jobs" > "$repro"
chmod +x "$repro"
log="$run_dir/test.log"

if (cd "$repo_dir" && ITSY_SELF_HOSTING_ARTIFACTS="$run_dir" swift test --filter integrationSelfHostingWorkspaceRunsEditNavigateTaskGitAndDebugFlows --jobs "$jobs") >"$log" 2>&1; then
	echo "PASSED scenario=self-hosting log=$log repro=$repro"
	exit 0
fi

screenshot="$(find "$run_dir" -type f -name editor.png -print -quit)"
if [[ -n "$screenshot" ]]; then
	echo "REGRESSION scenario=self-hosting log=$log screenshot=$screenshot repro=$repro" >&2
else
	echo "REGRESSION scenario=self-hosting log=$log screenshot=unavailable repro=$repro" >&2
fi
exit 1
