#!/usr/bin/env bash
set -euo pipefail

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
repo_dir="$(cd "$script_dir/../.." && pwd)"
fixture_root="${ITSY_INTERACTIVE_FIXTURE_ROOT:-$repo_dir/bench/corpus/interactive}"
app_dir="${ITSY_INTERACTIVE_APP:-$repo_dir/Itsy.app}"
bench="${ITSY_INTERACTIVE_BENCH:-$repo_dir/.build/release/ItsyBench}"
baseline="${ITSY_INTERACTIVE_BASELINE:-$repo_dir/bench/results/interactive-perf-baseline.json}"
output="${ITSY_INTERACTIVE_OUTPUT:-$repo_dir/bench/results/interactive-perf-current.json}"
runs=10
state="cold"
record_baseline=0
keep_traces=0

usage() {
	echo "usage: $0 [--runs count] [--state cold|warm] [--record-baseline] [--output path] [--baseline path] [--keep-traces]" >&2
}

while [[ "$#" -gt 0 ]]; do
	case "$1" in
	--runs) runs="$2"; shift 2 ;;
	--state) state="$2"; shift 2 ;;
	--record-baseline) record_baseline=1; shift ;;
	--output) output="$2"; shift 2 ;;
	--baseline) baseline="$2"; shift 2 ;;
	--keep-traces) keep_traces=1; shift ;;
	*) usage; exit 2 ;;
	esac
done

if [[ ! "$runs" =~ ^[1-9][0-9]*$ ]]; then
	echo "invalid --runs" >&2
	exit 2
fi
if [[ "$state" != "cold" && "$state" != "warm" ]]; then
	echo "--state must be cold or warm" >&2
	exit 2
fi

bash "$script_dir/gen_interactive_fixtures.sh" >/dev/null
app_binary="$repo_dir/.build/release/ItsyApp"
if [[ ! -x "$bench" || ! -x "$app_binary" || -n "$(find "$repo_dir/Sources" "$repo_dir/Package.swift" -newer "$app_binary" -print -quit)" ]]; then
	(cd "$repo_dir" && swift build -c release)
fi
if [[ ! -x "$app_dir/Contents/MacOS/Itsy" || "$app_binary" -nt "$app_dir/Contents/MacOS/Itsy" ]]; then
	APP_DIR="$app_dir" bash "$script_dir/make_app.sh" >/dev/null
fi

raw_dir="$(mktemp -d)"
index_file="$raw_dir/index.tsv"
mkdir -p "$raw_dir/traces"
cleanup() {
	if [[ "$keep_traces" == "1" ]]; then
		echo "traces: $raw_dir" >&2
	else
		rm -rf "$raw_dir"
	fi
}
trap cleanup EXIT

commit="$(git -C "$repo_dir" rev-parse HEAD)"
fixture_checksum="$(tr -d '\n' < "$fixture_root/fixture-tree.sha256")"
run_case() {
	local name="$1"
	local scenario="$2"
	local target="$3"
	local query="$4"
	local expected_top="$5"
	local expected_results="$6"
	local delta="$7"
	local run
	for ((run = 1; run <= runs; run++)); do
		local trace="$raw_dir/traces/$name-$run.jsonl"
		local report="$raw_dir/$name-$run.json"
		local home
		if [[ "$state" == "warm" ]]; then
			home="$raw_dir/home-warm"
		else
			home="$raw_dir/home-$name-$run"
		fi
		mkdir -p "$home"
		local args=("$target" "--bench-scenario=$scenario" "--bench-exit-after-scenario")
		if [[ -n "$query" ]]; then
			args+=("--bench-query=$query")
		fi
		if [[ -n "$expected_top" ]]; then
			args+=("--bench-expected-top=$expected_top")
		fi
		if [[ -n "$expected_results" ]]; then
			args+=("--bench-expected-results=$expected_results")
		fi
		if [[ -n "$delta" ]]; then
			args+=("--bench-scroll-delta=$delta")
		fi
		if ! HOME="$home" ITSY_PERF_TRACE_PATH="$trace" "$app_dir/Contents/MacOS/Itsy" "${args[@]}" >"$raw_dir/$name-$run.log" 2>&1; then
			echo "benchmark app failed: $name run $run" >&2
			return 1
		fi
		"$bench" trace-report --trace "$trace" --scenario "$scenario" --state "$state" --fixture-checksum "$fixture_checksum" --app-commit "$commit" >"$report"
		printf '%s\t%s\t%s\n' "$name" "$target" "$report" >>"$index_file"
	done
}

for workspace_size in 10000 50000; do
	workspace="$fixture_root/quick-open-$workspace_size"
	expected="src/group-01/Module00001.swift"
	run_case "palette-${workspace_size}-exact" palette "$workspace" "Module00001.swift" "$expected" 1 ""
	run_case "palette-${workspace_size}-relative" palette "$workspace" "$expected" "$expected" 1 ""
	run_case "palette-${workspace_size}-fuzzy" palette "$workspace" "Mdl00001" "" "" ""
	run_case "palette-${workspace_size}-no-match" palette "$workspace" "__itsy_missing_file__" "" 0 ""
done

run_case "scroll-1m-precise" scroll "$fixture_root/large/large-1048576.swift" "" "" "" -1
run_case "scroll-1m-page" scroll "$fixture_root/large/large-1048576.swift" "" "" "" -960
run_case "scroll-100m-page" scroll "$fixture_root/large/large-104857600.ts" "" "" "" -960
run_case "scroll-1g-page" scroll "$fixture_root/large/large-1073741824.py" "" "" "" -960
run_case "scroll-1g-jump" scroll "$fixture_root/large/large-1073741824.py" "" "" "" -10000

mkdir -p "$(dirname "$output")"
ruby -rjson -rtime -e '
	index_path, output_path, baseline_path, record, state, fixture_checksum = ARGV
	rows = File.readlines(index_path, chomp: true).reject(&:empty?).map { |line| line.split("\t", 3) }
	reports = rows.map do |name, target, path|
		payload = JSON.parse(File.read(path))
		payload.merge("test" => name, "target" => target)
	end
	failures = reports.filter_map do |report|
		next unless report["failure_reason"]
		{"test" => report.fetch("test"), "reason" => report.fetch("failure_reason")}
	end
	metrics = reports.flat_map do |report|
		report.fetch("metrics").map do |metric, value|
			{"name" => "#{report.fetch("test")}.#{metric}", "samples_ms" => value.fetch("raw_samples_ms")}
		end
	end.group_by { |metric| metric.fetch("name") }.map do |name, entries|
		samples = entries.flat_map { |entry| entry.fetch("samples_ms") }
		sorted = samples.sort
		{"name" => name, "sample_count" => samples.length, "raw_samples_ms" => samples, "median_ms" => sorted[(sorted.length - 1) / 2], "p95_ms" => sorted[[(sorted.length * 0.95).ceil - 1, sorted.length - 1].min, "max_ms" => sorted.last}
	end.sort_by { |metric| metric.fetch("name") }
	current = {"schema" => 1, "generated_at" => Time.now.utc.iso8601, "state" => state, "fixture_checksum" => fixture_checksum, "tests" => reports, "metrics" => metrics, "failures" => failures}
	baseline = File.exist?(baseline_path) ? JSON.parse(File.read(baseline_path)) : nil
	if record == "1"
		current["manual_approval_required_before_rebaseline"] = true
		File.write(baseline_path, JSON.pretty_generate(current) + "\n")
		baseline = current
	end
	comparisons = metrics.map do |metric|
		base = baseline&.fetch("metrics", [])&.find { |candidate| candidate.fetch("name") == metric.fetch("name") }
		if base.nil?
			metric.merge("status" => "unbaselined")
		else
			base_value = base.fetch("median_ms").to_f
			current_value = metric.fetch("median_ms").to_f
			tolerance = [base_value * 0.05, 1.0].max
			metric.merge("baseline_median_ms" => base_value, "limit_ms" => base_value + tolerance, "status" => current_value > base_value + tolerance ? "regressed" : "passed")
		end
	end
	status = if failures.any? || comparisons.any? { |metric| metric.fetch("status") == "regressed" }
		"failed"
	elsif baseline.nil?
		"unbaselined"
	else
		"passed"
	end
	report = current.merge("baseline" => baseline_path, "comparisons" => comparisons, "status" => status)
	File.write(output_path, JSON.pretty_generate(report) + "\n")
	puts JSON.pretty_generate(report)
	exit(status == "failed" ? 1 : 0)
' "$index_file" "$output" "$baseline" "$record_baseline" "$state" "$fixture_checksum"
