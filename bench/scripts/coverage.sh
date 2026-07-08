#!/usr/bin/env bash
set -euo pipefail

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
repo_dir="$(cd "$script_dir/../.." && pwd)"
results_dir="$repo_dir/bench/results"
date_stamp="${ITSY_COVERAGE_DATE:-$(date +%F)}"
lcov_out="${ITSY_COVERAGE_LCOV:-$results_dir/coverage-$date_stamp.lcov}"
json_out="${ITSY_COVERAGE_JSON:-$results_dir/coverage-$date_stamp.json}"
md_out="${ITSY_COVERAGE_MD:-$results_dir/coverage-$date_stamp.md}"
drop_limit="${ITSY_COVERAGE_DROP_LIMIT:-3}"
gate="${ITSY_COVERAGE_GATE:-0}"
run_tests="${ITSY_COVERAGE_RUN_TESTS:-1}"
ignore_regex="${ITSY_COVERAGE_IGNORE_REGEX:-/Tests/|/\\.build/|/Sources/C(TreeSitter|Libgit2|TSGrammars)/}"
baseline="${ITSY_COVERAGE_BASELINE:-}"

mkdir -p "$results_dir"

codecov_json="$(cd "$repo_dir" && swift test --enable-code-coverage --show-codecov-path)"
codecov_dir="$(dirname "$codecov_json")"
if [[ "$run_tests" != "0" ]]; then
	(cd "$repo_dir" && swift test --enable-code-coverage)
fi

profdata="$codecov_dir/default.profdata"
if [[ ! -f "$profdata" ]]; then
	echo "missing coverage profile: $profdata" >&2
	exit 1
fi

test_binary="$(find "$repo_dir/.build" -type f -path '*/ItsyPackageTests.xctest/Contents/MacOS/ItsyPackageTests' -perm -111 -print | sort | tail -1)"
if [[ -z "$test_binary" ]]; then
	echo "missing test binary for llvm-cov export" >&2
	exit 1
fi

if [[ -z "$baseline" ]]; then
	baseline="$(find "$results_dir" -maxdepth 1 -name 'coverage-*.json' ! -name "$(basename "$json_out")" ! -name 'coverage-current.json' -print | sort | tail -1 || true)"
fi

tmp_lcov="$(mktemp "${TMPDIR:-/tmp}/itsy-coverage.XXXXXX")"
trap 'rm -f "$tmp_lcov"' EXIT
xcrun llvm-cov export \
	--format=lcov \
	--instr-profile "$profdata" \
	"$test_binary" \
	--ignore-filename-regex="$ignore_regex" > "$tmp_lcov"
mv "$tmp_lcov" "$lcov_out"

ruby -rjson -rtime -rfileutils -e '
	def pct(hit, total)
		return 0.0 if total.to_i.zero?
		(hit.to_f / total.to_f) * 100.0
	end

	def fmt(value)
		format("%.2f", value.to_f)
	end

	def rel(path, repo)
		prefix = repo.end_with?(File::SEPARATOR) ? repo : repo + File::SEPARATOR
		path.start_with?(prefix) ? path.delete_prefix(prefix) : path
	end

	lcov_path, json_path, md_path, repo, baseline_path, drop_limit_arg, gate_arg = ARGV
	drop_limit = drop_limit_arg.to_f
	gate = gate_arg == "1"
	files = []
	current = nil
	File.foreach(lcov_path) do |line|
		line = line.chomp
		case line
		when /^SF:(.+)/
			current = {"path" => rel($1, repo), "lines" => 0, "covered_lines" => 0, "functions" => 0, "covered_functions" => 0, "branches" => 0, "covered_branches" => 0}
		when /^LF:(\d+)/
			current["lines"] = $1.to_i if current
		when /^LH:(\d+)/
			current["covered_lines"] = $1.to_i if current
		when /^FNF:(\d+)/
			current["functions"] = $1.to_i if current
		when /^FNH:(\d+)/
			current["covered_functions"] = $1.to_i if current
		when /^BRF:(\d+)/
			current["branches"] = $1.to_i if current
		when /^BRH:(\d+)/
			current["covered_branches"] = $1.to_i if current
		when "end_of_record"
			files << current if current
			current = nil
		end
	end
	files << current if current
	lines = files.sum { |file| file.fetch("lines") }
	covered_lines = files.sum { |file| file.fetch("covered_lines") }
	functions = files.sum { |file| file.fetch("functions") }
	covered_functions = files.sum { |file| file.fetch("covered_functions") }
	branches = files.sum { |file| file.fetch("branches") }
	covered_branches = files.sum { |file| file.fetch("covered_branches") }
	if lines.zero?
		warn "coverage export has no lines"
		exit 1
	end

	line_percent = pct(covered_lines, lines)
	function_percent = pct(covered_functions, functions)
	branch_percent = branches.zero? ? nil : pct(covered_branches, branches)
	baseline = nil
	drop = nil
	status = "no-baseline"
	if baseline_path && !baseline_path.empty? && File.exist?(baseline_path)
		baseline_json = JSON.parse(File.read(baseline_path))
		baseline_percent = baseline_json.fetch("line_coverage_percent").to_f
		drop = baseline_percent - line_percent
		baseline = {"path" => rel(baseline_path, repo), "line_coverage_percent" => baseline_percent}
		status = drop > drop_limit ? "fail" : "pass"
	end
	top_uncovered = files.map { |file|
		file.merge("uncovered_lines" => file.fetch("lines") - file.fetch("covered_lines"), "line_coverage_percent" => pct(file.fetch("covered_lines"), file.fetch("lines")))
	}.select { |file| file.fetch("uncovered_lines").positive? }.sort_by { |file| [-file.fetch("uncovered_lines"), file.fetch("path")] }.first(15)

	report = {
		"schema" => 1,
		"generated_at" => Time.now.utc.iso8601,
		"lcov" => rel(lcov_path, repo),
		"line_coverage_percent" => line_percent,
		"covered_lines" => covered_lines,
		"lines" => lines,
		"function_coverage_percent" => function_percent,
		"covered_functions" => covered_functions,
		"functions" => functions,
		"branch_coverage_percent" => branch_percent,
		"covered_branches" => covered_branches,
		"branches" => branches,
		"drop_limit_percent" => drop_limit,
		"drop_percent" => drop,
		"baseline" => baseline,
		"status" => status,
		"top_uncovered_files" => top_uncovered
	}
	FileUtils.mkdir_p(File.dirname(json_path))
	File.write(json_path, JSON.pretty_generate(report) + "\n")

	branch_text = branch_percent ? "#{fmt(branch_percent)}%" : "n/a"
	lines_out = [
		"# Itsy Coverage",
		"",
		"- Lines: #{fmt(line_percent)}% (#{covered_lines}/#{lines})",
		"- Functions: #{fmt(function_percent)}% (#{covered_functions}/#{functions})",
		"- Branches: #{branch_text} (#{covered_branches}/#{branches})",
		"- Status: #{status}",
		"- Baseline: #{baseline ? baseline.fetch("path") : "none"}",
		"- Drop limit: #{fmt(drop_limit)} percentage points",
		"",
		"| File | Line % | Uncovered |",
		"|---|---:|---:|"
	]
	top_uncovered.each do |file|
		lines_out << "| #{file.fetch("path")} | #{fmt(file.fetch("line_coverage_percent"))}% | #{file.fetch("uncovered_lines")} |"
	end
	File.write(md_path, lines_out.join("\n") + "\n")
	if ENV["GITHUB_STEP_SUMMARY"]
		File.open(ENV.fetch("GITHUB_STEP_SUMMARY"), "a") { |file| file.puts(lines_out.join("\n")) }
	end
	puts JSON.generate(report)
	if gate && status == "fail"
		warn "line coverage dropped #{fmt(drop)} percentage points; limit is #{fmt(drop_limit)}"
		exit 2
	end
' "$lcov_out" "$json_out" "$md_out" "$repo_dir" "$baseline" "$drop_limit" "$gate"

echo "$lcov_out"
