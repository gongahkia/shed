#!/usr/bin/env bash
set -euo pipefail

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
bench_dir="$(cd "$script_dir/.." && pwd)"
repo_dir="$(cd "$bench_dir/.." && pwd)"
results_dir="$bench_dir/results"
runs="${RUNS:-20}"
only="${PICO_BASELINE_ONLY:-}"
purge="${PICO_BASELINE_PURGE:-1}"
picobench="${PICOBENCH:-$repo_dir/.build/release/PicoBench}"
date_stamp="${BASELINE_DATE:-$(date +%F)}"
json_out="$results_dir/baseline-$date_stamp.json"
md_out="$results_dir/baseline-$date_stamp.md"
ndjson="$(mktemp)"

trap 'rm -f "$ndjson"' EXIT
mkdir -p "$results_dir"

if [[ ! -x "$picobench" ]]; then
	(cd "$repo_dir" && swift build -c release)
fi

apps=(
	"Zed|/Applications/Zed.app"
	"Sublime Text|/Applications/Sublime Text.app"
	"VSCode|/Applications/Visual Studio Code.app"
	"CodeEdit|/Applications/CodeEdit.app"
	"TextEdit|/System/Applications/TextEdit.app"
)

for entry in "${apps[@]}"; do
	name="${entry%%|*}"
	path="${entry#*|}"
	if [[ -n "$only" && "$name" != "$only" ]]; then
		continue
	fi
	if [[ ! -d "$path" ]]; then
		echo "missing app: $name ($path)" >&2
		exit 1
	fi
	for run in $(seq 1 "$runs"); do
		if [[ "$purge" != "0" ]]; then
			sudo purge
		fi
		result="$("$picobench" measure --app "$path")"
		ruby -rjson -e '
			payload = JSON.parse(STDIN.read)
			payload["competitor"] = ARGV[0]
			payload["run"] = ARGV[1].to_i
			payload["app_path"] = ARGV[2]
			puts JSON.generate(payload)
		' "$name" "$run" "$path" <<< "$result" >> "$ndjson"
	done
done

ruby -rjson -e '
	rows = File.readlines(ARGV[0], chomp: true).reject(&:empty?).map { |line| JSON.parse(line) }
	File.write(ARGV[1], JSON.pretty_generate(rows) + "\n")
	grouped = rows.group_by { |row| row["competitor"] }
	lines = ["# Baseline #{ARGV[3]}", "", "| App | Runs | Mean startup ms | Min startup ms | Max startup ms | Mean RSS KB |", "|---|---:|---:|---:|---:|---:|"]
	grouped.each do |name, items|
		startup = items.map { |row| row.fetch("startup_ms").to_f }
		rss = items.map { |row| row.fetch("rss_kb").to_f }
		mean_startup = startup.sum / startup.length
		mean_rss = rss.sum / rss.length
		lines << format("| %s | %d | %.3f | %.3f | %.3f | %.0f |", name, items.length, mean_startup, startup.min, startup.max, mean_rss)
	end
	File.write(ARGV[2], lines.join("\n") + "\n")
' "$ndjson" "$json_out" "$md_out" "$date_stamp"

echo "$json_out"
echo "$md_out"
