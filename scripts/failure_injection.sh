#!/usr/bin/env bash
set -euo pipefail

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
repo_dir="$(cd "$script_dir/.." && pwd)"
artifacts_dir="${ITSY_FAILURE_INJECTION_ARTIFACTS:-$repo_dir/.build/failure-injection}"
jobs="${ITSY_FAILURE_INJECTION_JOBS:-1}"
scenario_filter=""

usage() {
	echo "usage: $0 [--artifacts directory] [--jobs count] [--scenario name] [--list]" >&2
}

scenarios=(
	"full-disk-save:failureInjectionFullDiskSavePreservesDocumentAndReportsRecoveryAction"
	"permission-save:failureInjectionPermissionDeniedSavePreservesDocumentAndReportsRecoveryAction"
	"recovery-killed-process:failureInjectionRecoveryJournalSurvivesKilledEditorAndRejectsMalformedJournal"
	"killed-task:failureInjectionKilledTaskReturnsOutputAndCancelledStatus"
	"malformed-lsp:failureInjectionMalformedLSPProtocolIsRetainedInStatusDetails"
	"malformed-dap:failureInjectionMalformedDAPProtocolReturnsActionableDetail"
	"failed-git:failureInjectionFailedGitCommandReturnsActionableDetail"
)

while [[ "$#" -gt 0 ]]; do
	case "$1" in
	--artifacts) artifacts_dir="$2"; shift 2 ;;
	--jobs) jobs="$2"; shift 2 ;;
	--scenario) scenario_filter="$2"; shift 2 ;;
	--list)
		printf '%s\n' "${scenarios[@]%%:*}"
		exit 0
		;;
	*) usage; exit 2 ;;
	esac
done

if [[ ! "$jobs" =~ ^[1-9][0-9]*$ ]]; then
	echo "invalid jobs: $jobs" >&2
	exit 2
fi
if ! command -v swift >/dev/null 2>&1; then
	echo "BLOCKED requirement=swift" >&2
	exit 2
fi

mkdir -p "$artifacts_dir"
artifacts_dir="$(cd "$artifacts_dir" && pwd)"
run_dir="$artifacts_dir/run-$(date -u +%Y%m%dT%H%M%SZ)-$$"
mkdir -p "$run_dir"
results="$run_dir/results.tsv"
repro="$run_dir/repro.sh"
printf '%s\n' '#!/usr/bin/env bash' 'set -euo pipefail' "cd $(printf '%q' "$repo_dir")" "$(printf '%q' "$script_dir/failure_injection.sh") --artifacts $(printf '%q' "$run_dir") --jobs $jobs${scenario_filter:+ --scenario $(printf '%q' "$scenario_filter")}" >"$repro"
chmod +x "$repro"

matched=0
failures=0
for entry in "${scenarios[@]}"; do
	name="${entry%%:*}"
	filter="${entry#*:}"
	if [[ -n "$scenario_filter" && "$scenario_filter" != "$name" ]]; then
		continue
	fi
	matched=1
	log="$run_dir/$name.log"
	if (cd "$repo_dir" && swift test --filter "$filter" --jobs "$jobs") >"$log" 2>&1; then
		printf '%s\tpassed\t%s\n' "$name" "$log" >>"$results"
		echo "PASSED scenario=$name log=$log"
	else
		printf '%s\tfailed\t%s\n' "$name" "$log" >>"$results"
		echo "REGRESSION scenario=$name log=$log repro=$repro" >&2
		failures=$((failures + 1))
	fi
done

if [[ "$matched" == 0 ]]; then
	echo "unknown scenario: $scenario_filter" >&2
	exit 2
fi

result="$run_dir/result.json"
ruby -rjson -rtime -e '
	rows, repro = ARGV
	scenarios = File.readlines(rows, chomp: true).reject(&:empty?).map { |line| name, status, log = line.split("\t", 3); {"name" => name, "status" => status, "log" => log} }
	status = scenarios.all? { |scenario| scenario.fetch("status") == "passed" } ? "passed" : "failed"
	puts JSON.pretty_generate({"schema" => 1, "generated_at" => Time.now.utc.iso8601, "status" => status, "repro" => repro, "scenarios" => scenarios})
' "$results" "$repro" >"$result"

if (( failures > 0 )); then
	echo "REGRESSION result=$result repro=$repro" >&2
	exit 1
fi
echo "PASSED result=$result repro=$repro"
