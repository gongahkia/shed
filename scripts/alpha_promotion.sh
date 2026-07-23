#!/usr/bin/env bash
set -euo pipefail

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
repo_dir="$(cd "$script_dir/.." && pwd)"
manifest="$repo_dir/qa/alpha-promotion-v1.json"
artifacts_dir="${ITSY_ALPHA_PROMOTION_ARTIFACTS:-$repo_dir/.build/alpha-promotion}"
jobs="${ITSY_ALPHA_PROMOTION_JOBS:-1}"

usage() {
	echo "usage: $0 [--artifacts directory] [--jobs count] [--list]" >&2
}

while [[ "$#" -gt 0 ]]; do
	case "$1" in
	--artifacts) artifacts_dir="$2"; shift 2 ;;
	--jobs) jobs="$2"; shift 2 ;;
	--list)
		ruby -rjson -e 'JSON.parse(File.read(ARGV[0])).fetch("gates").each { |gate| puts gate.fetch("id") }' "$manifest"
		exit 0
		;;
	*) usage; exit 2 ;;
	esac
done

if [[ ! "$jobs" =~ ^[1-9][0-9]*$ ]]; then
	echo "invalid jobs: $jobs" >&2
	exit 2
fi
[[ -f "$manifest" ]] || { echo "missing manifest: $manifest" >&2; exit 1; }
command -v ruby >/dev/null 2>&1 || { echo "missing command: ruby" >&2; exit 2; }

checkout_error=""
checkout_changes=""
checkout_revision="unknown"
if ! checkout_changes="$(git -C "$repo_dir" status --porcelain=v1 --untracked-files=all 2>&1)"; then
	checkout_error="$checkout_changes"
fi
if ! checkout_revision="$(git -C "$repo_dir" rev-parse --verify HEAD 2>&1)"; then
	checkout_error="${checkout_error:+$checkout_error$'\n'}$checkout_revision"
	checkout_revision="unknown"
fi

mkdir -p "$artifacts_dir"
artifacts_dir="$(cd "$artifacts_dir" && pwd)"
run_dir="$artifacts_dir/run-$(date -u +%Y%m%dT%H%M%SZ)-$$"
evidence_dir="$run_dir/evidence"
mkdir -p "$evidence_dir"
rows="$run_dir/evidence.tsv"
repro="$run_dir/repro.sh"
printf '%s\n' '#!/usr/bin/env bash' 'set -euo pipefail' "cd $(printf '%q' "$repo_dir")" "$(printf '%q' "$script_dir/alpha_promotion.sh") --artifacts $(printf '%q' "$artifacts_dir") --jobs $jobs" >"$repro"
chmod +x "$repro"

remediation_for() {
	ruby -rjson -e 'gate = JSON.parse(File.read(ARGV[0])).fetch("gates").find { |entry| entry.fetch("id") == ARGV[1] }; abort("unknown gate") unless gate; print gate.fetch("remediation")' "$manifest" "$1"
}

status_label() {
	printf '%s' "$1" | tr '[:lower:]' '[:upper:]'
}

record_missing() {
	local gate="$1"
	printf '%s\tmissing\t\t\t%s\n' "$gate" "$(remediation_for "$gate")" >>"$rows"
}

run_gate() {
	local gate="$1"
	shift
	local report="$evidence_dir/$gate.json"
	local log="$evidence_dir/$gate.log"
	set +e
	"$script_dir/run_gate.sh" --gate "$gate" --output "$report" --log "$log" -- "$@"
	local run_exit=$?
	set -e
	local status="failed"
	if [[ -f "$report" ]]; then
		status="$(ruby -rjson -e 'print JSON.parse(File.read(ARGV[0])).fetch("status")' "$report")"
	fi
	printf '%s\t%s\t%s\t%s\t%s\n' "$gate" "$status" "$report" "$log" "$(remediation_for "$gate")" >>"$rows"
	printf '%s gate=%s report=%s log=%s\n' "$(status_label "$status")" "$gate" "$report" "$log"
	return "$run_exit"
}

checkout_ready=1
if ! run_gate clean-checkout env \
	"ITSY_ALPHA_CHECKOUT_ERROR=$checkout_error" \
	"ITSY_ALPHA_CHECKOUT_CHANGES=$checkout_changes" \
	"ITSY_ALPHA_CHECKOUT_REVISION=$checkout_revision" \
	/bin/bash -c '
		if [[ -n "$ITSY_ALPHA_CHECKOUT_ERROR" ]]; then
			printf "%s\\n" "$ITSY_ALPHA_CHECKOUT_ERROR" >&2
			echo "could not inspect repository cleanliness" >&2
			exit 1
		fi
		if [[ -n "$ITSY_ALPHA_CHECKOUT_CHANGES" ]]; then
			printf "%s\\n" "$ITSY_ALPHA_CHECKOUT_CHANGES" >&2
			echo "repository must be clean before alpha promotion" >&2
			exit 1
		fi
		printf "%s\\n" "$ITSY_ALPHA_CHECKOUT_REVISION"
	'; then
	checkout_ready=0
fi

preflight_ready=1
if [[ "$checkout_ready" == 1 ]]; then
	if ! run_gate preflight "$script_dir/private_alpha_preflight.sh" --output "$evidence_dir/preflight-matrix.json"; then
		preflight_ready=0
	fi
else
	record_missing preflight
fi

if [[ "$checkout_ready" == 1 && "$preflight_ready" == 1 ]]; then
	run_gate build swift build -c release || true
	run_gate tests swift test --jobs "$jobs" || true
	run_gate lsp-matrix "$script_dir/lsp_matrix.sh" --artifacts "$evidence_dir/lsp-matrix" || true
	run_gate private-alpha "$script_dir/private_alpha_acceptance.sh" --artifacts "$evidence_dir/private-alpha" --jobs "$jobs" || true
	run_gate self-hosting "$script_dir/self_hosting_acceptance.sh" --artifacts "$evidence_dir/self-hosting" --jobs "$jobs" || true
	run_gate failure-injection "$script_dir/failure_injection.sh" --artifacts "$evidence_dir/failure-injection" --jobs "$jobs" || true
	run_gate release-ui "$script_dir/release_ui_smoke.sh" --artifacts "$evidence_dir/release-ui" || true
	run_gate performance env ITSY_REGRESSION_OUT="$evidence_dir/regression.json" ITSY_REGRESSION_INPUT_LATENCY_RUNS=20 "$repo_dir/bench/scripts/regression.sh" || true
else
	for gate in build tests lsp-matrix private-alpha self-hosting failure-injection release-ui performance; do
		record_missing "$gate"
	done
fi

result="$run_dir/result.json"
ruby -rjson -rtime -e '
	rows_path, result_path, repro, revision = ARGV
	evidence = File.readlines(rows_path, chomp: true).reject(&:empty?).map do |line|
		id, status, report, log, remediation = line.split("\t", 5)
		{"id" => id, "status" => status, "report" => report, "log" => log, "remediation" => remediation}
	end
	status = if evidence.any? { |entry| entry.fetch("status") == "failed" }
		"failed"
	elsif evidence.all? { |entry| entry.fetch("status") == "passed" }
		"passed"
	else
		"blocked"
	end
	failed = evidence.find { |entry| entry.fetch("status") != "passed" }
	failed_scenario = failed && {"id" => failed.fetch("id"), "report" => failed.fetch("report"), "log" => failed.fetch("log"), "remediation" => failed.fetch("remediation")}
	puts JSON.pretty_generate({"schema" => 1, "generated_at" => Time.now.utc.iso8601, "revision" => revision, "status" => status, "repro" => repro, "failed_scenario" => failed_scenario, "failed_evidence" => failed, "evidence" => evidence})
' "$rows" "$result" "$repro" "$checkout_revision" >"$result"

status="$(ruby -rjson -e 'print JSON.parse(File.read(ARGV[0])).fetch("status")' "$result")"
if [[ "$status" == passed ]]; then
	echo "PASSED promotion=private-alpha result=$result repro=$repro"
	exit 0
fi
failed="$(ruby -rjson -e 'entry = JSON.parse(File.read(ARGV[0])).fetch("failed_evidence"); print "gate=#{entry.fetch("id")} status=#{entry.fetch("status")} log=#{entry.fetch("log")} remediation=#{entry.fetch("remediation")}"' "$result")"
echo "$(status_label "$status") promotion=private-alpha result=$result scenario=$failed" >&2
[[ "$status" == failed ]] && exit 1
exit 2
