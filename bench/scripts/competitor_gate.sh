#!/usr/bin/env bash
set -euo pipefail

json="${1:?usage: bench/scripts/competitor_gate.sh bench/results/nightly-competitors-YYYY-MM-DD.json}"

ruby -rjson -e '
	path = ARGV.fetch(0)
	rows = JSON.parse(File.read(path))
	required = ENV.fetch("ITSY_COMPETITOR_REQUIRED", "Itsy,Zed,Sublime Text,CodeEdit").split(",")
	startup_limit = ENV.fetch("ITSY_COMPETITOR_STARTUP_SLOWDOWN_LIMIT", "1.25").to_f
	rss_limit = ENV.fetch("ITSY_COMPETITOR_RSS_ZED_LIMIT", "1.15").to_f

	grouped = rows.group_by { |row| row.fetch("competitor") }
	missing = required.reject { |name| grouped.key?(name) && !grouped.fetch(name).empty? }
	unless missing.empty?
		abort("missing competitor rows: #{missing.join(", ")}")
	end

	means = grouped.transform_values do |items|
		startup = items.map { |row| row.fetch("startup_ms").to_f }
		rss = items.map { |row| row.fetch("rss_kb").to_f }
		{
			runs: items.length,
			startup_ms: startup.sum / startup.length,
			rss_kb: rss.sum / rss.length
		}
	end

	failures = []
	itsy = means.fetch("Itsy")
	sublime = means.fetch("Sublime Text")
	zed = means.fetch("Zed")
	startup_ceiling = sublime.fetch(:startup_ms) * startup_limit
	rss_ceiling = zed.fetch(:rss_kb) * rss_limit
	if itsy.fetch(:startup_ms) > startup_ceiling
		failures << format("Itsy startup %.3f ms exceeds Sublime Text ceiling %.3f ms", itsy.fetch(:startup_ms), startup_ceiling)
	end
	if itsy.fetch(:rss_kb) > rss_ceiling
		failures << format("Itsy RSS %.0f KB exceeds Zed ceiling %.0f KB", itsy.fetch(:rss_kb), rss_ceiling)
	end

	lines = ["# Competitor Gate", "", "| App | Runs | Mean startup ms | Mean RSS KB |", "|---|---:|---:|---:|"]
	required.each do |name|
		metric = means.fetch(name)
		lines << format("| %s | %d | %.3f | %.0f |", name, metric.fetch(:runs), metric.fetch(:startup_ms), metric.fetch(:rss_kb))
	end
	lines += [
		"",
		format("- Startup gate: Itsy <= Sublime Text * %.2f (%.3f ms)", startup_limit, startup_ceiling),
		format("- RSS gate: Itsy <= Zed * %.2f (%.0f KB)", rss_limit, rss_ceiling)
	]
	lines += failures.map { |failure| "- FAIL: #{failure}" }
	summary = lines.join("\n") + "\n"
	puts summary
	if ENV["GITHUB_STEP_SUMMARY"] && !ENV["GITHUB_STEP_SUMMARY"].empty?
		File.open(ENV.fetch("GITHUB_STEP_SUMMARY"), "a") { |file| file.write(summary) }
	end
	exit(failures.empty? ? 0 : 1)
' "$json"
