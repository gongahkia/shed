#!/usr/bin/env bash
set -euo pipefail

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
repo_dir="$(cd "$script_dir/../.." && pwd)"
bench="${ITSY_WORKFLOW_BENCH:-$repo_dir/.build/release/ItsyBench}"
baseline="${ITSY_WORKFLOW_BASELINE:-$repo_dir/bench/results/editor-workflows-baseline.json}"
output="${ITSY_WORKFLOW_OUTPUT:-$repo_dir/bench/results/editor-workflows-current.json}"
repeats="${ITSY_WORKFLOW_REPEATS:-5}"
pane_transitions="${ITSY_WORKFLOW_PANE_TRANSITIONS:-200}"
lsp_file="${ITSY_WORKFLOW_LSP_FILE:-$repo_dir/Sources/ItsyBench/main.swift}"
threshold="${ITSY_WORKFLOW_THRESHOLD:-0.10}"
record_baseline=0
raw="$(mktemp)"
lsp_raw="$(mktemp)"
git_fixture="$(mktemp -d)"

usage() {
	echo "usage: $0 [--bench path] [--baseline path] [--output path] [--repeats count] [--record-baseline]" >&2
}

cleanup() {
	rm -f "$raw"
	rm -f "$lsp_raw"
	rm -rf "$git_fixture"
}
trap cleanup EXIT

while [[ "$#" -gt 0 ]]; do
	case "$1" in
	--bench) bench="$2"; shift 2 ;;
	--baseline) baseline="$2"; shift 2 ;;
	--output) output="$2"; shift 2 ;;
	--repeats) repeats="$2"; shift 2 ;;
	--record-baseline) record_baseline=1; shift ;;
	*) usage; exit 2 ;;
	esac
done

if [[ ! "$repeats" =~ ^[1-9][0-9]*$ ]]; then
	echo "invalid --repeats" >&2
	exit 2
fi
if [[ ! -x "$bench" || -n "$(find "$repo_dir/Sources/ItsyBench" "$repo_dir/Sources/ItsyEditor" "$repo_dir/Package.swift" -newer "$bench" -print -quit)" ]]; then
	(cd "$repo_dir" && swift build -c release --product ItsyBench >/dev/null)
fi
mkdir -p "$(dirname "$output")"

append_workflow() {
	local name="$1"
	local file="$2"
	local result
	result="$("$bench" workflow --file "$file" --repeats "$repeats" --pane-transitions "$pane_transitions")"
	ruby -rjson -e '
		name = ARGV[0]
		payload = JSON.parse(STDIN.read)
		payload.fetch("means_ms").each do |metric, value|
			puts JSON.generate({"name" => "#{name}.#{metric}", "unit" => "ms", "owner" => payload.fetch("owner"), "value" => value, "variance" => payload.fetch("variance_ms2").fetch(metric), "samples" => payload.fetch("repeats")})
		end
		rss = payload.fetch("samples").map { |sample| sample.fetch("rss_delta_kb").to_f }
		mean = rss.sum / rss.length
		variance = rss.sum { |value| (value - mean) ** 2 } / rss.length
		puts JSON.generate({"name" => "#{name}.rss_delta_kb", "unit" => "KB", "owner" => payload.fetch("owner"), "value" => mean, "variance" => variance, "samples" => payload.fetch("repeats")})
	' "$name" <<< "$result" >> "$raw"
}

append_process() {
	local name="$1"
	local owner="$2"
	shift 2
	ruby -rjson -e '
		runs = Integer(ENV.fetch("ITSY_WORKFLOW_REPEATS"))
		times = runs.times.map do
			start = Process.clock_gettime(Process::CLOCK_MONOTONIC)
			abort("command failed: #{ARGV.join(" ")}") unless system(*ARGV, out: File::NULL, err: File::NULL)
			(Process.clock_gettime(Process::CLOCK_MONOTONIC) - start) * 1000.0
		end
		mean = times.sum / times.length
		variance = times.sum { |value| (value - mean) ** 2 } / times.length
		puts JSON.generate({"name" => ENV.fetch("ITSY_WORKFLOW_NAME"), "unit" => "ms", "owner" => ENV.fetch("ITSY_WORKFLOW_OWNER"), "value" => mean, "variance" => variance, "samples" => times.length})
	' "$@" >> "$raw"
}

append_workflow small "$repo_dir/bench/corpus/small.ts"
append_workflow large "$repo_dir/bench/corpus/large.ts"


for _ in $(seq 1 "$repeats"); do
	ITSY_LSP_FILE="$lsp_file" ITSY_LSP_ROOT="$repo_dir" ITSY_LSP_LANGUAGE_ID=swift ruby "$script_dir/lsp_diagnostics_probe.rb" | ruby -rjson -e 'puts JSON.generate(JSON.parse(STDIN.read))' >> "$lsp_raw"
done
ruby -rjson -e '
		rows = STDIN.each_line.map { |line| JSON.parse(line) }
		values = rows.map { |row| row.fetch("lsp_didopen_to_diagnostics_ms") }
		mean = values.sum / values.length
		variance = values.sum { |value| (value - mean) ** 2 } / values.length
		puts JSON.generate({"name" => "lsp.didopen_to_diagnostics_ms", "unit" => "ms", "owner" => "environment", "value" => mean, "variance" => variance, "samples" => values.length})
	' < "$lsp_raw" >> "$raw"

git -C "$git_fixture" init -q
git -C "$git_fixture" config user.name ItsyBenchmark
git -C "$git_fixture" config user.email itsy-benchmark@example.invalid
cp "$repo_dir/bench/corpus/small.ts" "$git_fixture/main.ts"
git -C "$git_fixture" add main.ts
git -C "$git_fixture" commit -qm fixture
ITSY_WORKFLOW_REPEATS="$repeats" ITSY_WORKFLOW_NAME="git.refresh_ms" ITSY_WORKFLOW_OWNER="environment" append_process git.refresh_ms environment git -C "$git_fixture" status --porcelain
ITSY_WORKFLOW_REPEATS="$repeats" ITSY_WORKFLOW_NAME="task.output_ms" ITSY_WORKFLOW_OWNER="environment" append_process task.output_ms environment /bin/sh -c "printf 'itsy-task-output\\n' >/dev/null"

ruby -rjson -rtime -e '
	raw_path, baseline_path, output_path, record, threshold, repeats, pane_transitions, lsp_file = ARGV
		rows = File.readlines(raw_path, chomp: true).reject(&:empty?).map { |line| JSON.parse(line) }
		current = rows.sort_by { |row| row.fetch("name") }
		parameters = {"repeats" => repeats.to_i, "pane_transitions" => pane_transitions.to_i, "lsp_file" => lsp_file}
		baseline = File.exist?(baseline_path) ? JSON.parse(File.read(baseline_path)) : nil
		if record == "1"
			baseline = {"schema" => 1, "generated_at" => Time.now.utc.iso8601, "parameters" => parameters, "policy" => {"direction" => "lower", "relative_threshold" => threshold.to_f, "variance_multiplier" => 3.0}, "metrics" => current}
			File.write(baseline_path, JSON.pretty_generate(baseline) + "\n")
		end
		status = "passed"
		parameters_match = baseline.nil? || baseline["parameters"] == parameters
		metrics = current.map do |metric|
			base = baseline&.fetch("metrics", [])&.find { |candidate| candidate.fetch("name") == metric.fetch("name") }
			if !parameters_match
				status = "blocked" if status == "passed"
				metric.merge("status" => "blocked", "reason" => "baseline parameters differ")
			elsif base.nil?
				status = "blocked" if status == "passed"
				metric.merge("status" => "blocked", "reason" => "missing baseline")
			else
				policy = baseline.fetch("policy")
				relative_tolerance = base.fetch("value").to_f * policy.fetch("relative_threshold").to_f
				variance_tolerance = policy.fetch("variance_multiplier").to_f * Math.sqrt(base.fetch("variance").to_f + metric.fetch("variance").to_f)
				limit = base.fetch("value").to_f + [relative_tolerance, variance_tolerance].max
				failed = metric.fetch("value").to_f > limit
				status = "failed" if failed
				metric.merge("baseline" => base.fetch("value"), "baseline_variance" => base.fetch("variance"), "limit" => limit, "policy" => policy, "status" => failed ? "failed" : "passed")
			end
		end
		report = {"schema" => 1, "generated_at" => Time.now.utc.iso8601, "status" => status, "baseline" => baseline_path, "parameters" => parameters, "metrics" => metrics}
		File.write(output_path, JSON.pretty_generate(report) + "\n")
		puts JSON.pretty_generate(report)
		exit(status == "passed" ? 0 : status == "blocked" ? 2 : 1)
	' "$raw" "$baseline" "$output" "$record_baseline" "$threshold" "$repeats" "$pane_transitions" "$lsp_file"
