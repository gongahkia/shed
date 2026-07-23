#!/usr/bin/env bash
set -euo pipefail

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
repo_dir="$(cd "$script_dir/../.." && pwd)"
duration="${ITSY_SOAK_DURATION:-3600}"
interval="${ITSY_SOAK_INTERVAL:-60}"
settle="${ITSY_SOAK_SETTLE:-60}"
file_count="${ITSY_SOAK_FILES:-50}"
pane_cycles="${ITSY_SOAK_PANE_CYCLES:-20}"
component_cycles="${ITSY_SOAK_COMPONENT_CYCLES:-3}"
rss_growth_limit="${ITSY_SOAK_RSS_GROWTH_PERCENT:-10}"
fd_growth_limit="${ITSY_SOAK_FD_GROWTH:-16}"
process_growth_limit="${ITSY_SOAK_PROCESS_GROWTH:-2}"
results_dir="$repo_dir/bench/results"
date_stamp="${ITSY_SOAK_DATE:-$(date +%F)}"
csv_out="${ITSY_SOAK_CSV:-$results_dir/soak-$date_stamp.csv}"
json_out="${ITSY_SOAK_JSON:-$results_dir/soak-$date_stamp.json}"
artifacts="${ITSY_SOAK_ARTIFACTS:-$results_dir/soak-$date_stamp-artifacts}"
app_dir="$repo_dir/Itsy.app"
workspace="$(mktemp -d "${TMPDIR:-/tmp}/itsy-soak-workspace.XXXXXX")"
files="$(mktemp)"
components="$(mktemp)"
crash_marker="$(mktemp)"
pid=""
cleanup_passed=true
app_alive=true
dry_run=0

usage() {
	echo "usage: $0 [--duration seconds] [--interval seconds] [--component-cycles count] [--dry-run]" >&2
}

cleanup_app() {
	if [[ -z "$pid" ]] || ! kill -0 "$pid" 2>/dev/null; then
		pid=""
		return
	fi
	kill "$pid" 2>/dev/null || cleanup_passed=false
	for _ in $(seq 1 40); do
		kill -0 "$pid" 2>/dev/null || break
		sleep 0.05
	done
	if kill -0 "$pid" 2>/dev/null; then
		kill -KILL "$pid" 2>/dev/null || cleanup_passed=false
		sleep 0.1
	fi
	if kill -0 "$pid" 2>/dev/null; then
		cleanup_passed=false
	else
		pid=""
	fi
}

cleanup() {
	cleanup_app
	rm -f "$files" "$components" "$crash_marker"
	rm -rf "$workspace"
}
trap cleanup EXIT

while [[ "$#" -gt 0 ]]; do
	case "$1" in
	--duration) duration="$2"; shift 2 ;;
	--interval) interval="$2"; shift 2 ;;
	--component-cycles) component_cycles="$2"; shift 2 ;;
	--dry-run) dry_run=1; shift ;;
	*) usage; exit 2 ;;
	esac
done
for value in "$duration" "$interval" "$settle" "$file_count" "$pane_cycles" "$component_cycles"; do
	if [[ ! "$value" =~ ^[1-9][0-9]*$ ]]; then
		echo "invalid positive integer: $value" >&2
		exit 2
	fi
done

if [[ "$dry_run" == 1 ]]; then
	ruby -rjson -e 'puts JSON.generate({"coverage" => %w[documents panes lsp dap terminal tasks git recovery], "cleanup" => "SIGTERM, wait, SIGKILL if needed", "thresholds" => {"rss_growth_percent" => ARGV[0].to_f, "fd_growth" => ARGV[1].to_i, "process_growth" => ARGV[2].to_i}})' "$rss_growth_limit" "$fd_growth_limit" "$process_growth_limit"
	exit 0
fi

record_component() {
	local cycle="$1"
	local name="$2"
	shift 2
	local log="$artifacts/component-$cycle-$name.log"
	local status=passed
	"$@" >"$log" 2>&1 || status=failed
	ruby -rjson -e 'puts JSON.generate({"cycle" => ARGV[0].to_i, "name" => ARGV[1], "status" => ARGV[2], "log" => ARGV[3]})' "$cycle" "$name" "$status" "$log" >> "$components"
	[[ "$status" == passed ]]
}

owned_process_count() {
	local parent="$1"
	local child
	local total=0
	for child in $(pgrep -P "$parent" 2>/dev/null || true); do
		total=$((total + 1 + $(owned_process_count "$child")))
	done
	echo "$total"
}

fd_count() {
	lsof -p "$pid" 2>/dev/null | wc -l | tr -d ' '
}

sample() {
	local elapsed="$1"
	local cycle="$2"
	local rss
	local fds
	local processes
	rss="$(ps -o rss= -p "$pid" | tr -d ' ')"
	fds="$(fd_count)"
	processes="$(owned_process_count "$pid")"
	printf '%s,%s,%s,%s,%s\n' "$elapsed" "$rss" "$fds" "$processes" "$cycle" >> "$csv_out"
}

mkdir -p "$results_dir" "$artifacts"
touch "$crash_marker"
(cd "$repo_dir" && swift build -c release >/dev/null && bench/scripts/make_app.sh >/dev/null)
rsync -a --delete --exclude .git --exclude .build --exclude Itsy.app --exclude bench/traces "$repo_dir/" "$workspace/"
git -C "$workspace" init -q
git -C "$workspace" config user.name ItsySoak
git -C "$workspace" config user.email itsy-soak@example.invalid
git -C "$workspace" add --all
git -C "$workspace" commit -qm fixture
git -C "$repo_dir" ls-files | rg '\.(swift|md|sh|json|toml|ts)$' | head -"$file_count" > "$files"

actual_count="$(wc -l < "$files" | tr -d ' ')"
if [[ "$actual_count" -ne "$file_count" ]]; then
	echo "expected $file_count files, got $actual_count" >&2
	exit 1
fi

/usr/bin/open -n "$app_dir" --args "$workspace" --profile=plain
for _ in $(seq 1 100); do
	pid="$(pgrep -n -x Itsy || true)"
	[[ -n "$pid" ]] && break
	sleep 0.1
done
if [[ -z "$pid" ]]; then
	echo "Itsy did not launch" >&2
	exit 1
fi
for _ in $(seq 1 100); do
	window_count="$(osascript -e 'tell application "System Events" to count windows of process "Itsy"' 2>/dev/null || echo 0)"
	[[ "$window_count" -gt 0 ]] && break
	sleep 0.1
done

opened=0
while IFS= read -r rel; do
	/usr/bin/open -b dev.itsy.editor "$workspace/$rel"
	sleep 0.12
	if ! osascript -e 'tell application "System Events" to keystroke "x"' >/dev/null; then
		record_component 0 documents /usr/bin/false || true
		break
	fi
	opened=$((opened + 1))
	done < "$files"
if [[ "$opened" -eq "$actual_count" ]]; then
	record_component 0 documents /usr/bin/true || true
fi
for _ in $(seq 1 "$pane_cycles"); do
	if ! osascript -e 'tell application "System Events" to key code 42 using command down' >/dev/null; then
		record_component 0 panes /usr/bin/false || true
		break
	fi
	done
if [[ "$pane_cycles" -gt 0 ]]; then
	record_component 0 panes /usr/bin/true || true
fi

sleep "$settle"
window_count="$(osascript -e 'tell application "System Events" to count windows of process "Itsy"' 2>/dev/null || echo 0)"
baseline_rss="$(ps -o rss= -p "$pid" | tr -d ' ')"
baseline_fds="$(fd_count)"
baseline_processes="$(owned_process_count "$pid")"
start_time="$(date +%s)"
end_time=$((start_time + duration))
printf 'elapsed_s,rss_kb,fd_count,process_count,component_cycle\n' > "$csv_out"
component_cycle=0

run_component_cycle() {
	local cycle="$1"
	record_component "$cycle" lsp swift test --filter 'LSPManagerTests|LSPSessionSupervisorTests' || true
	record_component "$cycle" dap swift test --filter 'DebugSessionTests|integrationDAPLaunchHitsBreakpointAndResumes' || true
	record_component "$cycle" terminal swift test --filter TerminalSessionTests || true
	record_component "$cycle" tasks swift test --filter WorkspaceTasksTests || true
	record_component "$cycle" git swift test --filter 'GitRepositoryTests|GitRemoteOperationTests' || true
	record_component "$cycle" recovery swift test --filter 'RecoveryJournalStoreTests|failureInjectionRecoveryJournalSurvivesKilledEditorAndRejectsMalformedJournal' || true
}

while true; do
	now="$(date +%s)"
	elapsed=$((now - start_time))
	if ! kill -0 "$pid" 2>/dev/null; then
		app_alive=false
		echo "process exited before soak end at elapsed_s=$elapsed" >&2
		break
	fi
	if [[ "$component_cycle" -lt "$component_cycles" ]]; then
		component_cycle=$((component_cycle + 1))
		run_component_cycle "$component_cycle"
	fi
	sample "$elapsed" "$component_cycle"
	(( now >= end_time )) && break
	sleep "$interval"
done

cleanup_app
crash_dir="$HOME/Library/Logs/DiagnosticReports"
crash_evidence="$artifacts/crash-evidence.txt"
if [[ -d "$crash_dir" ]]; then
	find "$crash_dir" -type f -name 'Itsy*.ips' -newer "$crash_marker" -print > "$crash_evidence"
else
	: > "$crash_evidence"
fi
log show --style compact --start "$(date -r "$start_time" '+%Y-%m-%d %H:%M:%S')" --predicate 'process == "Itsy"' > "$artifacts/itsy.log" 2>&1 || true

ruby -rjson -rcsv -e '
	rows, components_path, crash_path, out, opened, windows, settle, baseline_rss, baseline_fds, baseline_processes, rss_limit, fd_limit, process_limit, cleanup, app_alive, artifacts = ARGV
	samples = CSV.read(rows, headers: true).map { |row| row.to_h.transform_values(&:to_i) }
	components = File.readlines(components_path, chomp: true).reject(&:empty?).map { |line| JSON.parse(line) }
	crashes = File.readlines(crash_path, chomp: true).reject(&:empty?)
	baseline_rss = baseline_rss.to_i
	baseline_fds = baseline_fds.to_i
	baseline_processes = baseline_processes.to_i
	last = samples.last || {}
	max_rss = samples.map { |sample| sample.fetch("rss_kb") }.max || baseline_rss
	max_fds = samples.map { |sample| sample.fetch("fd_count") }.max || baseline_fds
	max_processes = samples.map { |sample| sample.fetch("process_count") }.max || baseline_processes
	rss_growth = baseline_rss.zero? ? 0.0 : ((max_rss - baseline_rss).to_f / baseline_rss) * 100.0
	failed_components = components.select { |component| component.fetch("status") != "passed" }
	pass = app_alive == "true" && windows.to_i == 1 && rss_growth <= rss_limit.to_f && max_fds - baseline_fds <= fd_limit.to_i && max_processes - baseline_processes <= process_limit.to_i && failed_components.empty? && crashes.empty? && cleanup == "true"
	summary = {
		"schema" => 2,
		"opened_files" => opened.to_i,
		"windows" => windows.to_i,
		"settle_s" => settle.to_i,
		"duration_s" => last.fetch("elapsed_s", 0),
		"samples" => samples.length,
		"baseline_rss_kb" => baseline_rss,
		"max_rss_kb" => max_rss,
		"peak_rss_growth_percent" => rss_growth,
		"baseline_fd_count" => baseline_fds,
		"max_fd_count" => max_fds,
		"fd_growth" => max_fds - baseline_fds,
		"baseline_process_count" => baseline_processes,
		"max_process_count" => max_processes,
		"process_growth" => max_processes - baseline_processes,
		"components" => components,
		"crash_evidence" => crashes,
		"artifacts" => {"component_logs" => artifacts, "crash_evidence" => crash_path, "app_log" => File.join(artifacts, "itsy.log")},
		"app_alive" => app_alive == "true",
		"cleanup_passed" => cleanup == "true",
		"thresholds" => {"rss_growth_percent" => rss_limit.to_f, "fd_growth" => fd_limit.to_i, "process_growth" => process_limit.to_i},
		"pass" => pass
	}
	File.write(out, JSON.pretty_generate(summary) + "\n")
	puts JSON.pretty_generate(summary)
	exit(pass ? 0 : 1)
' "$csv_out" "$components" "$crash_evidence" "$json_out" "$opened" "$window_count" "$settle" "$baseline_rss" "$baseline_fds" "$baseline_processes" "$rss_growth_limit" "$fd_growth_limit" "$process_growth_limit" "$cleanup_passed" "$app_alive" "$artifacts"
