#!/usr/bin/env bash
set -euo pipefail

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
repo_dir="$(cd "$script_dir/../.." && pwd)"
results_dir="$repo_dir/bench/results"
date_stamp="${ITSY_MEMORY_DATE:-$(date +%F)}"
json_out="${ITSY_MEMORY_JSON:-$results_dir/memory-$date_stamp.json}"
md_out="${ITSY_MEMORY_MD:-$results_dir/memory-$date_stamp.md}"
settle="${ITSY_MEMORY_SETTLE:-1}"
timeout="${ITSY_MEMORY_TIMEOUT:-10}"
top_count="${ITSY_MEMORY_TOP:-10}"
binary="${ITSY_MEMORY_BINARY:-$repo_dir/.build/release/ItsyApp}"
bench="${ITSYBENCH:-$repo_dir/.build/release/ItsyBench}"
gate="${ITSY_MEMORY_GATE:-0}"
gate_limit_kb="${ITSY_MEMORY_GATE_LIMIT_KB:-102400}"
gate_consecutive="${ITSY_MEMORY_GATE_CONSECUTIVE:-3}"
gate_history="${ITSY_MEMORY_GATE_HISTORY:-$results_dir/memory-gate-history.json}"
stage_file="$(mktemp "${TMPDIR:-/tmp}/itsy-memory-stages.XXXXXX")"
vmmap_out="$(mktemp "${TMPDIR:-/tmp}/itsy-vmmap.XXXXXX")"
pid=""

cleanup() {
	if [[ -n "${pid:-}" ]] && kill -0 "$pid" 2>/dev/null; then
		kill "$pid" 2>/dev/null || true
		wait "$pid" 2>/dev/null || true
		sleep 1
		kill -0 "$pid" 2>/dev/null && kill -KILL "$pid" 2>/dev/null || true
	fi
	rm -f "$stage_file" "$vmmap_out"
}
trap cleanup EXIT

if [[ ! -x "$binary" || ! -x "$bench" ]]; then
	(cd "$repo_dir" && swift build -c release >/dev/null)
fi

mkdir -p "$results_dir"
ITSY_BENCH_STAGES_PATH="$stage_file" "$binary" >/dev/null 2>&1 &
pid="$!"

deadline=$((SECONDS + timeout))
while (( SECONDS < deadline )); do
	if ! kill -0 "$pid" 2>/dev/null; then
		echo "Itsy exited before first_draw" >&2
		exit 1
	fi
	if rg -q '^first_draw ' "$stage_file" 2>/dev/null; then
		break
	fi
	sleep 0.05
done

if ! rg -q '^first_draw ' "$stage_file" 2>/dev/null; then
	echo "timed out waiting for first_draw" >&2
	exit 1
fi

sleep "$settle"
rss_json="$("$bench" rss --pid "$pid")"
vmmap -summary "$pid" > "$vmmap_out"

ruby -rjson -rtime -rfileutils -e '
	def kb(value)
		return nil unless value
		match = value.match(/\A([0-9.]+)([KMG]?)\z/)
		return nil unless match
		number = match[1].to_f
		case match[2]
		when "G" then (number * 1024 * 1024).round
		when "M" then (number * 1024).round
		else number.round
		end
	end

	def load_history(path)
		return {"schema" => 1, "runs" => []} unless path && File.exist?(path)
		JSON.parse(File.read(path))
	rescue JSON::ParserError
		{"schema" => 1, "runs" => []}
	end

	rss = JSON.parse(ARGV[0]).fetch("rss_kb")
	vmmap_text = File.read(ARGV[1])
	top_count = ARGV[2].to_i
	limit_kb = ARGV[6].to_i
	required_failures = [ARGV[7].to_i, 1].max
	history_path = ARGV[8]
	gate_enabled = ARGV[9] == "1"
	physical = vmmap_text[/^Physical footprint:\s+([0-9.]+[KMG]?)/, 1]
	physical_kb = kb(physical)
	status = physical_kb && physical_kb <= limit_kb ? "pass" : "fail"
	regions = []
	in_region_table = false
	vmmap_text.each_line do |line|
		if line.lstrip.start_with?("REGION TYPE")
			in_region_table = true
			next
		end
		next unless in_region_table
		stripped = line.strip
		next if stripped.empty? || stripped.start_with?("===========")
		break if stripped.start_with?("TOTAL")
		cols = line.strip.split(/\s{2,}/)
		next if cols.length < 3
		resident = kb(cols[2])
		next unless resident
		regions << {"region" => cols[0], "resident_kb" => resident}
	end
	regions = regions.sort_by { |row| -row.fetch("resident_kb") }.first(top_count)
	report = {
		"pid" => ARGV[3].to_i,
		"rss_kb" => rss,
		"physical_footprint_kb" => physical_kb,
		"physical_footprint_limit_kb" => limit_kb,
		"physical_footprint_status" => status,
		"top_regions" => regions
	}
	if history_path && !history_path.empty?
		history = load_history(history_path)
		runs = history.fetch("runs", [])
		runs << {
			"generated_at" => Time.now.utc.iso8601,
			"rss_kb" => rss,
			"physical_footprint_kb" => physical_kb,
			"limit_kb" => limit_kb,
			"status" => status
		}
		runs = runs.last(30)
		consecutive_failures = runs.reverse.take_while { |run| run.fetch("status") == "fail" }.length
		history = {
			"schema" => 1,
			"limit_kb" => limit_kb,
			"required_consecutive_failures" => required_failures,
			"consecutive_failures" => consecutive_failures,
			"runs" => runs
		}
		FileUtils.mkdir_p(File.dirname(history_path))
		File.write(history_path, JSON.pretty_generate(history) + "\n")
		report["physical_footprint_consecutive_failures"] = consecutive_failures
		report["physical_footprint_required_consecutive_failures"] = required_failures
	end
	File.write(ARGV[4], JSON.pretty_generate(report) + "\n")
	lines = [
		"# Itsy Memory Audit",
		"",
		"- RSS: #{rss} KB",
		"- Physical footprint: #{report.fetch("physical_footprint_kb")} KB",
		"- Physical footprint limit: #{limit_kb} KB",
		"- Physical footprint status: #{status}",
		"",
		"| Region | Resident KB |",
		"|---|---:|"
	]
	regions.each { |row| lines << "| #{row.fetch("region")} | #{row.fetch("resident_kb")} |" }
	File.write(ARGV[5], lines.join("\n") + "\n")
	puts JSON.generate(report)
	if gate_enabled && report.fetch("physical_footprint_consecutive_failures", status == "fail" ? 1 : 0) >= required_failures
		warn "physical footprint exceeded #{limit_kb} KB for #{required_failures} consecutive runs"
		exit 2
	end
' "$rss_json" "$vmmap_out" "$top_count" "$pid" "$json_out" "$md_out" "$gate_limit_kb" "$gate_consecutive" "$gate_history" "$gate"
