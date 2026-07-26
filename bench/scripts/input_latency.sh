#!/usr/bin/env bash
set -euo pipefail

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
repo_dir="$(cd "$script_dir/../.." && pwd)"
app="${ITSY_INPUT_LATENCY_APP:-$repo_dir/.build/release/ItsyApp}"
bench="${ITSY_INPUT_LATENCY_BENCH:-$repo_dir/.build/release/ItsyBench}"
baseline="${ITSY_INPUT_LATENCY_BASELINE:-$repo_dir/bench/results/input-latency-baseline.json}"
output="${ITSY_INPUT_LATENCY_OUTPUT:-$repo_dir/bench/results/input-latency-current.json}"
workloads="${ITSY_INPUT_LATENCY_WORKLOADS:-small:$repo_dir/bench/corpus/small.ts,large:$repo_dir/bench/corpus/large.ts}"
runs="${ITSY_INPUT_LATENCY_RUNS:-20}"
timeout_ms="${ITSY_INPUT_LATENCY_TIMEOUT_MS:-5000}"
record_baseline=0
raw="$(mktemp "${TMPDIR:-/tmp}/itsy-input-latency.XXXXXX")"
workspace_dir="$(mktemp -d "${TMPDIR:-/tmp}/itsy-input-workspaces.XXXXXX")"
artifacts="${output}.artifacts"
pid=""

usage() {
	echo "usage: $0 [--app path] [--bench path] [--baseline path] [--output path] [--workloads name:path,...] [--runs count] [--timeout-ms count] [--record-baseline]" >&2
}

cleanup() {
	if [[ -n "$pid" ]] && kill -0 "$pid" 2>/dev/null; then
		kill "$pid" 2>/dev/null || true
		wait "$pid" 2>/dev/null || true
	fi
	rm -f "$raw"
	rm -rf "$workspace_dir"
}
trap cleanup EXIT

while [[ "$#" -gt 0 ]]; do
	case "$1" in
	--app) app="$2"; shift 2 ;;
	--bench) bench="$2"; shift 2 ;;
	--baseline) baseline="$2"; shift 2 ;;
	--output) output="$2"; artifacts="${output}.artifacts"; shift 2 ;;
	--workloads) workloads="$2"; shift 2 ;;
	--runs) runs="$2"; shift 2 ;;
	--timeout-ms) timeout_ms="$2"; shift 2 ;;
	--record-baseline) record_baseline=1; shift ;;
	*) usage; exit 2 ;;
	esac
done
if [[ ! "$runs" =~ ^[1-9][0-9]*$ || ! "$timeout_ms" =~ ^[1-9][0-9]*$ ]]; then
	echo "invalid runs or timeout" >&2
	exit 2
fi
mkdir -p "$(dirname "$output")" "$artifacts"

append_result() {
	ruby -rjson -e 'puts JSON.generate({"workload" => ARGV[0], "file" => ARGV[1], "status" => ARGV[2], "latency_ms" => ARGV[3] == "-" ? nil : ARGV[3].to_f, "reason" => ARGV[4], "log" => ARGV[5]})' "$1" "$2" "$3" "$4" "$5" "$6" >> "$raw"
}

wait_for_first_draw() {
	local stage="$1"
	local deadline=$((SECONDS + timeout_ms / 1000 + 1))
	while (( SECONDS < deadline )); do
		rg -q '^first_draw ' "$stage" 2>/dev/null && return 0
		kill -0 "$pid" 2>/dev/null || return 1
		sleep 0.02
	done
	return 1
}

if [[ ! -x "$app" || ! -x "$bench" ]]; then
	append_result setup "$app" blocked - "missing app or ItsyBench executable" "$artifacts/setup.log"
else
	IFS=',' read -r -a workload_array <<< "$workloads"
	for workload in "${workload_array[@]}"; do
		name="${workload%%:*}"
		file="${workload#*:}"
		if [[ -z "$name" || "$file" == "$workload" || ! -f "$file" ]]; then
			append_result "$name" "$file" blocked - "missing workload file" "$artifacts/$name.log"
			continue
		fi
		for run in $(seq 1 "$runs"); do
			workspace="$workspace_dir/$name-$run"
			mkdir -p "$workspace"
			staged_file="$workspace/$(basename "$file")"
			cp "$file" "$staged_file"
			stage="$artifacts/$name-$run.stages"
			log="$artifacts/$name-$run.log"
			ITSY_BENCH_STAGES_PATH="$stage" "$app" "$staged_file" >"$log" 2>&1 &
			pid="$!"
			if ! wait_for_first_draw "$stage"; then
				append_result "$name" "$file" failed - "app did not reach first_draw" "$log"
				kill "$pid" 2>/dev/null || true
				wait "$pid" 2>/dev/null || true
				pid=""
				continue
			fi
			if latency_json="$("$bench" latency --pid "$pid" --timeout-ms "$timeout_ms" 2>>"$log")"; then
				latency="$(ruby -rjson -e 'print JSON.parse(STDIN.read).fetch("keydown_to_paint_ms")' <<< "$latency_json")"
				append_result "$name" "$file" passed "$latency" "" "$log"
			else
				reason="$(/usr/bin/tail -n 1 "$log" 2>/dev/null || true)"
				if [[ "$reason" == *permission* || "$reason" == *Accessibility* || "$reason" == *screen* ]]; then
					append_result "$name" "$file" blocked - "$reason" "$log"
				else
					append_result "$name" "$file" failed - "$reason" "$log"
				fi
			fi
			kill "$pid" 2>/dev/null || true
			wait "$pid" 2>/dev/null || true
			pid=""
		done
	done
fi

ruby -rjson -rtime -e '
  raw, baseline_path, output, record, artifacts = ARGV
  rows = File.readlines(raw, chomp: true).reject(&:empty?).map { |line| JSON.parse(line) }
  groups = rows.group_by { |row| row.fetch("workload") }
  workloads = groups.sort.to_h do |name, entries|
    passed = entries.select { |entry| entry.fetch("status") == "passed" }.map { |entry| entry.fetch("latency_ms") }
    status = if entries.any? { |entry| entry.fetch("status") == "failed" }
      "failed"
    elsif entries.any? { |entry| entry.fetch("status") == "blocked" }
      "blocked"
    else
      "passed"
    end
    [name, {"status" => status, "runs" => entries, "keydown_to_paint_ms" => passed.empty? ? nil : passed.sum / passed.length}]
  end
  status = workloads.values.any? { |workload| workload.fetch("status") == "failed" } ? "failed" : workloads.values.any? { |workload| workload.fetch("status") == "blocked" } ? "blocked" : "passed"
  baseline = File.exist?(baseline_path) ? JSON.parse(File.read(baseline_path)) : nil
  if status == "passed" && record == "1"
    baseline = {"schema" => 1, "aggregation" => "mean", "threshold" => 0.05, "workloads" => workloads.transform_values { |workload| samples = workload.fetch("runs").map { |run| run["latency_ms"] }.compact; {"keydown_to_paint_ms" => workload.fetch("keydown_to_paint_ms"), "upper_bound_ms" => samples.max} }}
    File.write(baseline_path, JSON.pretty_generate(baseline) + "\n")
  elsif status == "passed" && baseline.nil?
    status = "blocked"
    workloads.each_value { |workload| workload["reason"] = "missing recorded baseline: #{baseline_path}" }
  elsif status == "passed"
	threshold = baseline.fetch("threshold", 0.05).to_f
	workloads.each do |name, workload|
	  baseline_workload = baseline.fetch("workloads", {})[name]
	  unless baseline_workload.is_a?(Hash) && baseline_workload["keydown_to_paint_ms"].is_a?(Numeric)
		workload["status"] = "blocked"
		workload["reason"] = "baseline missing workload"
		status = "blocked" unless status == "failed"
		next
      end
      baseline_value = baseline_workload.fetch("keydown_to_paint_ms").to_f
      upper_bound = baseline_workload.fetch("upper_bound_ms", baseline_value).to_f
      limit = upper_bound * (1.0 + threshold)
      workload["baseline_ms"] = baseline_value
      workload["upper_bound_ms"] = upper_bound
      workload["limit_ms"] = limit
      if workload.fetch("keydown_to_paint_ms") > limit
        workload["status"] = "failed"
        workload["reason"] = "latency exceeds baseline tolerance"
        status = "failed"
      end
    end
  end
  report = {"schema" => 1, "aggregation" => "mean", "generated_at" => Time.now.utc.iso8601, "status" => status, "baseline" => baseline_path, "artifacts" => artifacts, "workloads" => workloads}
  File.write(output, JSON.pretty_generate(report) + "\n")
  puts JSON.generate(report)
  exit(status == "passed" ? 0 : status == "blocked" ? 2 : 1)
' "$raw" "$baseline" "$output" "$record_baseline" "$artifacts"
