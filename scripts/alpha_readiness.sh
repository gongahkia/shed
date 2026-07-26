#!/usr/bin/env bash
set -euo pipefail

output=""

usage() {
	echo "usage: $0 --output alpha-readiness.json gate-result.json [gate-result.json ...]" >&2
}

while [[ "$#" -gt 0 ]]; do
	case "$1" in
	--output)
		output="$2"
		shift 2
		;;
	*)
		break
		;;
	esac
done

if [[ -z "$output" || "$#" -eq 0 ]]; then
	usage
	exit 2
fi
if ! command -v ruby >/dev/null 2>&1; then
	echo "BLOCKED requirement=ruby" >&2
	exit 2
fi
mkdir -p "$(dirname "$output")"
ruby -rjson -rtime -e '
  output, *paths = ARGV
  required = %w[schema_version gate status duration_ms environment failure artifacts generated_at]
  reports = paths.map do |path|
    report = JSON.parse(File.read(path))
    missing = required.reject { |key| report.key?(key) }
    raise "#{path}: missing #{missing.join(", ")}" unless missing.empty?
    raise "#{path}: unsupported schema" unless report.fetch("schema_version") == 1
    raise "#{path}: invalid status" unless %w[passed blocked failed].include?(report.fetch("status"))
    raise "#{path}: invalid duration" unless report.fetch("duration_ms").is_a?(Integer) && report.fetch("duration_ms") >= 0
    raise "#{path}: invalid environment" unless report.fetch("environment").is_a?(Hash)
    report.merge("report_path" => path)
  end
  duplicate = reports.group_by { |report| report.fetch("gate") }.find { |_, entries| entries.length > 1 }
  raise "duplicate gate: #{duplicate.first}" if duplicate
  status = if reports.any? { |report| report.fetch("status") == "failed" }
    "failed"
  elsif reports.any? { |report| report.fetch("status") == "blocked" }
    "blocked"
  else
    "passed"
  end
  first_nonpassing = reports.find { |report| report.fetch("status") != "passed" }
  failure = first_nonpassing && {"gate" => first_nonpassing.fetch("gate"), "detail" => first_nonpassing.fetch("failure")}
  result = {
    "schema_version" => 1,
    "gate" => "alpha-readiness",
    "status" => status,
    "duration_ms" => reports.sum { |report| report.fetch("duration_ms") },
    "environment" => reports.first.fetch("environment"),
    "failure" => failure,
    "artifacts" => (paths + reports.flat_map { |report| report.fetch("artifacts") }).uniq,
    "generated_at" => Time.now.utc.iso8601,
    "gates" => reports.sort_by { |report| report.fetch("gate") }
  }
  File.write(output, JSON.pretty_generate(result) + "\n")
  puts JSON.generate(result)
  exit(status == "passed" ? 0 : status == "blocked" ? 2 : 1)
' "$output" "$@"
