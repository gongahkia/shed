#!/usr/bin/env bash
set -euo pipefail

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
repo_dir="$(cd "$script_dir/../.." && pwd)"
baseline="${ITSY_REGRESSION_BASELINE:-$repo_dir/bench/results/baseline-itsy-current.json}"
out="${ITSY_REGRESSION_OUT:-$repo_dir/bench/results/regression-current.json}"
runs="${ITSY_REGRESSION_RUNS:-20}"
threshold="${ITSY_REGRESSION_THRESHOLD:-0.05}"
rope_ops="${ITSY_REGRESSION_ROPE_OPS:-1000000}"
rope_runs="${ITSY_REGRESSION_ROPE_RUNS:-5}"
slice_length="${ITSY_REGRESSION_SLICE_LENGTH:-32}"
itsybench="${ITSYBENCH:-$repo_dir/.build/release/ItsyBench}"
itsyapp="${ITSY_APP_BINARY:-$repo_dir/.build/release/ItsyApp}"
hyperfine_json="$(mktemp)"
rope_json="$(mktemp)"

trap 'rm -f "$hyperfine_json" "$rope_json"' EXIT

if [[ ! -f "$baseline" ]]; then
	echo "missing itsy regression baseline: $baseline" >&2
	exit 1
fi

if [[ ! -x "$itsybench" || ! -x "$itsyapp" ]]; then
	(cd "$repo_dir" && swift build -c release)
fi

mkdir -p "$(dirname "$out")"
app_command="$itsyapp --bench-exit-on-ready"
hyperfine_args=(--shell=none --warmup 0 --runs "$runs" --export-json "$hyperfine_json")
if [[ "${ITSY_REGRESSION_PURGE:-0}" != "0" ]]; then
	hyperfine_args+=(--prepare "purge")
fi
hyperfine "${hyperfine_args[@]}" "$app_command" >/dev/null
for _ in $(seq 1 "$rope_runs"); do
	"$itsybench" rope --ops "$rope_ops" --slice-length "$slice_length" >>"$rope_json"
done

ruby -rjson -rtime -e '
	def swift_loc(repo)
		patterns = ["Sources/**/*.swift", "Tests/**/*.swift"]
		patterns.sum do |pattern|
			Dir[File.join(repo, pattern)].sum do |path|
				File.readlines(path).count { |line| line.strip != "" }
			end
		end
	end

	def value_text(value)
		value >= 1000 ? format("%.0f", value) : format("%.3f", value)
	end

	def escape_command(value)
		value.to_s.gsub("%", "%25").gsub("\r", "%0D").gsub("\n", "%0A")
	end

	baseline_path, hyperfine_path, rope_path, out_path, repo, binary, threshold_arg = ARGV
	baseline = JSON.parse(File.read(baseline_path))
	hyperfine = JSON.parse(File.read(hyperfine_path))
	rope_runs = File.readlines(rope_path, chomp: true).reject(&:empty?).map { |line| JSON.parse(line) }
	bench = hyperfine.fetch("results").first
	current = {
		"cold_start_ready_ms" => bench.fetch("median").to_f * 1000.0,
		"cold_start_ready_min_ms" => bench.fetch("min").to_f * 1000.0,
		"cold_start_ready_max_ms" => bench.fetch("max").to_f * 1000.0,
		"rope_random_insert_ns_per_op" => rope_runs.map { |run| run.fetch("random_insert_ns_per_op").to_f }.min,
		"rope_sequential_insert_ns_per_op" => rope_runs.map { |run| run.fetch("sequential_insert_ns_per_op").to_f }.min,
		"rope_slice_ns_per_op" => rope_runs.map { |run| run.fetch("slice_ns_per_op").to_f }.min,
		"binary_size_kb" => File.size(binary).to_f / 1024.0,
		"swift_loc" => swift_loc(repo).to_f
	}
	default_threshold = baseline.fetch("threshold", threshold_arg).to_f
	rows = baseline.fetch("metrics").map do |metric|
		name = metric.fetch("name")
		base = metric.fetch("baseline").to_f
		value = current.fetch(name)
		metric_threshold = metric.fetch("threshold", default_threshold).to_f
		direction = metric.fetch("direction", "lower")
		limit = direction == "higher" ? base * (1.0 - metric_threshold) : base * (1.0 + metric_threshold)
		failed = direction == "higher" ? value < limit : value > limit
		delta = base.zero? ? 0.0 : ((value - base) / base) * 100.0
		metric.merge(
			"current" => value,
			"delta_percent" => delta,
			"limit" => limit,
			"status" => failed ? "fail" : "pass"
		)
	end
	report = {
		"generated_at" => Time.now.utc.iso8601,
		"baseline" => baseline_path,
		"runs" => hyperfine.fetch("results").first.fetch("times").length,
		"rope_runs" => rope_runs.length,
		"threshold" => default_threshold,
		"metrics" => rows
	}
	File.write(out_path, JSON.pretty_generate(report) + "\n")
	lines = ["# Itsy regression", "", "| Metric | Baseline | Current | Limit | Status |", "|---|---:|---:|---:|---|"]
	rows.each do |row|
		lines << format("| %s | %s %s | %s %s | %s %s | %s |",
			row.fetch("name"),
			value_text(row.fetch("baseline").to_f),
			row.fetch("unit", ""),
			value_text(row.fetch("current").to_f),
			row.fetch("unit", ""),
			value_text(row.fetch("limit").to_f),
			row.fetch("unit", ""),
			row.fetch("status"))
	end
	puts lines.join("\n")
	if ENV["GITHUB_STEP_SUMMARY"]
		File.open(ENV.fetch("GITHUB_STEP_SUMMARY"), "a") { |file| file.puts(lines.join("\n")) }
	end
	failures = rows.select { |row| row.fetch("status") == "fail" }
	failures.each do |row|
		message = format("%s regressed: current %s %s, limit %s %s, baseline %s %s",
			row.fetch("name"),
			value_text(row.fetch("current").to_f),
			row.fetch("unit", ""),
			value_text(row.fetch("limit").to_f),
			row.fetch("unit", ""),
			value_text(row.fetch("baseline").to_f),
			row.fetch("unit", ""))
		if ENV["GITHUB_ACTIONS"] == "true"
			puts "::error title=Itsy regression::#{escape_command(message)}"
		else
			warn message
		end
	end
	exit(failures.empty? ? 0 : 1)
' "$baseline" "$hyperfine_json" "$rope_json" "$out" "$repo_dir" "$itsyapp" "$threshold"

echo "$out"
