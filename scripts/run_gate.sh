#!/usr/bin/env bash
set -euo pipefail

gate=""
output=""
log=""
blocked_exit=2
artifacts=("")

usage() {
	echo "usage: $0 --gate name --output result.json [--log path] [--artifact path] [--blocked-exit code] -- command [args...]" >&2
}

while [[ "$#" -gt 0 ]]; do
	case "$1" in
	--gate)
		gate="$2"
		shift 2
		;;
	--output)
		output="$2"
		shift 2
		;;
	--log)
		log="$2"
		shift 2
		;;
	--artifact)
		artifacts+=("$2")
		shift 2
		;;
	--blocked-exit)
		blocked_exit="$2"
		shift 2
		;;
	--)
		shift
		break
		;;
	*)
		usage
		exit 2
		;;
	esac
done

if [[ -z "$gate" || -z "$output" || "$#" -eq 0 || ! "$blocked_exit" =~ ^[0-9]+$ ]]; then
	usage
	exit 2
fi
if ! command -v ruby >/dev/null 2>&1; then
	echo "BLOCKED requirement=ruby" >&2
	exit "$blocked_exit"
fi
if [[ -z "$log" ]]; then
	log="$output.log"
fi
mkdir -p "$(dirname "$output")" "$(dirname "$log")"

started_ms="$(ruby -e 'print((Process.clock_gettime(Process::CLOCK_MONOTONIC) * 1000).round)')"
set +e
"$@" >"$log" 2>&1
command_exit="$?"
set -e
finished_ms="$(ruby -e 'print((Process.clock_gettime(Process::CLOCK_MONOTONIC) * 1000).round)')"
duration_ms=$((finished_ms - started_ms))
if [[ "$command_exit" -eq 0 ]]; then
	status="passed"
	failure_message=""
elif [[ "$command_exit" -eq "$blocked_exit" ]]; then
	status="blocked"
	failure_message="$(/usr/bin/tail -n 1 "$log" 2>/dev/null || true)"
else
	status="failed"
	failure_message="$(/usr/bin/tail -n 1 "$log" 2>/dev/null || true)"
fi
architecture="$(uname -m)"
os_version="$(sw_vers -productVersion 2>/dev/null || uname -s)"
swift_version="$(swift --version 2>/dev/null | /usr/bin/head -n 1 || true)"
ruby -rjson -rtime -e '
  output, gate, status, duration, architecture, os_version, swift_version, log, failure_message, *artifacts = ARGV
  failure = status == "passed" ? nil : {"location" => log, "message" => failure_message}
  result = {
    "schema_version" => 1,
    "gate" => gate,
    "status" => status,
    "duration_ms" => duration.to_i,
    "environment" => {"architecture" => architecture, "os_version" => os_version, "swift_version" => swift_version},
    "failure" => failure,
    "artifacts" => [log, *artifacts].reject(&:empty?).uniq,
    "generated_at" => Time.now.utc.iso8601
  }
  File.write(output, JSON.pretty_generate(result) + "\n")
  puts JSON.generate(result)
' "$output" "$gate" "$status" "$duration_ms" "$architecture" "$os_version" "$swift_version" "$log" "$failure_message" "${artifacts[@]}"
exit "$command_exit"
