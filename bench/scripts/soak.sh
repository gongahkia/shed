#!/usr/bin/env bash
set -euo pipefail

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
repo_dir="$(cd "$script_dir/../.." && pwd)"
duration="${PICO_SOAK_DURATION:-3600}"
interval="${PICO_SOAK_INTERVAL:-60}"
settle="${PICO_SOAK_SETTLE:-60}"
file_count="${PICO_SOAK_FILES:-50}"
workspace="${PICO_SOAK_WORKSPACE:-/tmp/pico-soak-workspace}"
results_dir="$repo_dir/bench/results"
date_stamp="${PICO_SOAK_DATE:-$(date +%F)}"
csv_out="${PICO_SOAK_CSV:-$results_dir/soak-$date_stamp.csv}"
json_out="${PICO_SOAK_JSON:-$results_dir/soak-$date_stamp.json}"
app_dir="$repo_dir/Pico.app"
files="$(mktemp)"
pid=""

cleanup() {
	if [[ -n "${pid:-}" ]] && kill -0 "$pid" 2>/dev/null; then
		kill "$pid" 2>/dev/null || true
		sleep 2
		kill -0 "$pid" 2>/dev/null && kill -KILL "$pid" 2>/dev/null || true
	fi
	rm -f "$files"
}

trap cleanup EXIT

(cd "$repo_dir" && swift build -c release >/dev/null && bench/scripts/make_app.sh >/dev/null)
rm -rf "$workspace"
mkdir -p "$workspace" "$results_dir"
rsync -a --delete --exclude .git --exclude .build --exclude Pico.app --exclude bench/traces "$repo_dir/" "$workspace/"
git -C "$repo_dir" ls-files | rg '\.(swift|md|sh|json|toml|ts)$' | head -"$file_count" > "$files"

actual_count="$(wc -l < "$files" | tr -d ' ')"
if [[ "$actual_count" -ne "$file_count" ]]; then
	echo "expected $file_count files, got $actual_count" >&2
	exit 1
fi

/usr/bin/open -n "$app_dir" --args "$workspace" --profile=plain
for _ in $(seq 1 100); do
	pid="$(pgrep -n -x Pico || true)"
	[[ -n "$pid" ]] && break
	sleep 0.1
done
if [[ -z "$pid" ]]; then
	echo "Pico did not launch" >&2
	exit 1
fi

for _ in $(seq 1 100); do
	window_count="$(osascript -e 'tell application "System Events" to count windows of process "Pico"' 2>/dev/null || echo 0)"
	[[ "$window_count" -gt 0 ]] && break
	sleep 0.1
done

opened=0
while IFS= read -r rel; do
	/usr/bin/open -b dev.pico.editor "$workspace/$rel"
	sleep 0.12
	osascript -e 'tell application "System Events" to keystroke "x"' >/dev/null
	opened=$((opened + 1))
	sleep 0.03
done < "$files"

sleep "$settle"
window_count="$(osascript -e 'tell application "System Events" to count windows of process "Pico"' 2>/dev/null || echo 0)"
baseline_rss="$(ps -o rss= -p "$pid" | tr -d ' ')"
start_time="$(date +%s)"
end_time=$((start_time + duration))

printf 'elapsed_s,rss_kb\n' > "$csv_out"
while true; do
	now="$(date +%s)"
	elapsed=$((now - start_time))
	if ! kill -0 "$pid" 2>/dev/null; then
		echo "process exited before soak end at elapsed_s=$elapsed" >&2
		exit 2
	fi
	rss="$(ps -o rss= -p "$pid" | tr -d ' ')"
	printf '%s,%s\n' "$elapsed" "$rss" >> "$csv_out"
	(( now >= end_time )) && break
	sleep "$interval"
done

ruby -rjson -rcsv -e '
	rows = CSV.read(ARGV[0], headers: true).map { |row| [row.fetch("elapsed_s").to_i, row.fetch("rss_kb").to_i] }
	baseline = ARGV[1].to_i
	final = rows.last[1]
	max = rows.map(&:last).max
	summary = {
		"pid" => ARGV[2].to_i,
		"opened_files" => ARGV[3].to_i,
		"windows" => ARGV[4].to_i,
		"settle_s" => ARGV[6].to_i,
		"duration_s" => rows.last[0],
		"samples" => rows.length,
		"baseline_rss_kb" => baseline,
		"final_rss_kb" => final,
		"max_rss_kb" => max,
		"final_growth_percent" => ((final - baseline).to_f / baseline) * 100.0,
		"peak_growth_percent" => ((max - baseline).to_f / baseline) * 100.0
	}
	summary["pass"] = summary.fetch("windows") == 1 && summary.fetch("peak_growth_percent") < 10.0
	File.write(ARGV[5], JSON.pretty_generate(summary) + "\n")
	puts JSON.pretty_generate(summary)
	exit(summary["pass"] ? 0 : 1)
' "$csv_out" "$baseline_rss" "$pid" "$opened" "$window_count" "$json_out" "$settle"
