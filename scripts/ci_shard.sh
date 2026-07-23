#!/usr/bin/env bash
set -euo pipefail

usage() {
	printf '%s\n' \
		'usage: scripts/ci_shard.sh --shard unit|ui|integration|performance|all [--artifacts <directory>]' \
		'' \
		'Runs one deterministic CI capability shard. Each shard has one gate result and no automatic retry.'
}

shard=''
artifacts='.build/ci-shards'
while (($#)); do
	case "$1" in
		--shard) shard="${2:-}"; shift 2 ;;
		--artifacts) artifacts="${2:-}"; shift 2 ;;
		-h|--help) usage; exit 0 ;;
		*) printf 'error: unknown or incomplete argument: %s\n' "$1" >&2; usage >&2; exit 2 ;;
	esac
done
case "$shard" in unit|ui|integration|performance|all) ;; *) printf 'error: --shard is required\n' >&2; usage >&2; exit 2 ;; esac

repo_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$repo_dir"
mkdir -p "$artifacts"

run_gate() {
	local name="$1"
	shift
	scripts/run_gate.sh --gate "ci-$name" --output "$artifacts/$name.json" --log "$artifacts/$name.log" -- "$@"
}

run_unit() {
	local target
	while IFS= read -r target; do
		swift test --filter "$target" --jobs 1
	done < <(swift package describe --type json | ruby -rjson -e '
		JSON.parse(STDIN.read).fetch("targets").select { |target|
			target.fetch("type") == "test" && !["ItsyUISnapshotTests", "ItsyIntegrationTests"].include?(target.fetch("name"))
		}.map { |target| target.fetch("name") }.sort.each { |name| puts name }
	')
}

run_ui() {
	swift test --filter ItsyUISnapshotTests --jobs 1
}

run_integration() {
	swift test --filter ItsyIntegrationTests --jobs 1
	swift test --filter ItsyLSPSmokeTests --jobs 1
	bench/scripts/dap_smoke.sh
}

run_performance() {
	swift build -c release --target ItsyBench --jobs 1
.build/release/ItsyBench --smoke --runs 1
	bench/scripts/large_text_gate.sh
}

run_shard() {
	case "$1" in
		unit) run_gate unit run_unit ;;
		ui) run_gate ui run_ui ;;
		integration) run_gate integration run_integration ;;
		performance) run_gate performance run_performance ;;
	esac
}

if [[ "$shard" == all ]]; then
	for name in unit ui integration performance; do run_shard "$name"; done
	printf 'all CI shards passed\n'
	else
	run_shard "$shard"
fi
