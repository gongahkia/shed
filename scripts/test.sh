#!/usr/bin/env bash
set -euo pipefail

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
repo_dir="$(cd "$script_dir/.." && pwd)"
report_path="${ITSY_TEST_REPORT:-}"
coverage_raw_dir="${ITSY_TEST_COVERAGE_RAW_DIR:-}"
coverage_dir="${ITSY_TEST_COVERAGE_DIR:-}"
parallel_width="${SWT_EXPERIMENTAL_MAXIMUM_PARALLELIZATION_WIDTH:-1}"

bash "$script_dir/lint_feature_boundaries.sh"

if [[ -n "$report_path" ]]; then
	mkdir -p "$(dirname "$report_path")"
	: > "$report_path"
fi
if [[ -n "$coverage_raw_dir" && -z "$coverage_dir" ]]; then
	echo "ITSY_TEST_COVERAGE_DIR is required when ITSY_TEST_COVERAGE_RAW_DIR is set" >&2
	exit 2
fi
if [[ -n "$coverage_raw_dir" ]]; then
	mkdir -p "$coverage_raw_dir"
fi

test_targets=()
while IFS= read -r test_target; do
	test_targets+=("$test_target")
done < <(
	cd "$repo_dir"
	swift package describe --type json | /usr/bin/ruby -rjson -e '
		JSON.parse(STDIN.read).fetch("targets").select { |target| target.fetch("type") == "test" }.map { |target| target.fetch("name") }.sort.each { |name| puts name }
	'
)
if [[ "${#test_targets[@]}" -eq 0 ]]; then
	echo "no SwiftPM test targets found" >&2
	exit 1
fi

test_results_path="$(mktemp "${TMPDIR:-/tmp}/itsy-test-results.XXXXXX")"
test_list_path="$(mktemp "${TMPDIR:-/tmp}/itsy-test-list.XXXXXX")"
test_logs_dir="$(mktemp -d "${TMPDIR:-/tmp}/itsy-test-logs.XXXXXX")"
trap 'rm -f "$test_results_path" "$test_list_path"; rm -rf "$test_logs_dir"' EXIT

export SWT_EXPERIMENTAL_MAXIMUM_PARALLELIZATION_WIDTH="$parallel_width"
raw_index=0
failed_target_count=0
for test_target in "${test_targets[@]}"; do
	test_output_path="$test_logs_dir/$test_target.log"
	args=(test --filter "$test_target")
	if [[ -n "$coverage_raw_dir" ]]; then
		args=(test --enable-code-coverage --filter "$test_target")
	fi
	if (cd "$repo_dir" && swift "${args[@]}") 2>&1 | tee "$test_output_path"; then
		if [[ -n "$coverage_raw_dir" ]]; then
			while IFS= read -r raw_profile; do
				raw_index=$((raw_index + 1))
				cp "$raw_profile" "$coverage_raw_dir/${raw_index}.profraw"
			done < <(find "$coverage_dir" -maxdepth 1 -type f -name '*.profraw' -print | sort)
		fi
		if [[ -n "$report_path" ]]; then
			printf '%s\tpassed\t%s\n' "$test_target" "$test_output_path" >> "$test_results_path"
		fi
	else
		if [[ -n "$report_path" ]]; then
			printf '%s\tfailed\t%s\n' "$test_target" "$test_output_path" >> "$test_results_path"
		fi
		failed_target_count=$((failed_target_count + 1))
	fi
done

test_listing_result='passed'
if ! (cd "$repo_dir" && swift test list --skip-build) > "$test_list_path"; then
	test_listing_result='failed'
	printf 'failed to list tests after execution\n' >&2
fi

if [[ -n "$report_path" ]]; then
	/usr/bin/ruby -rjson -e '
		report_path, test_list_path, test_results_path, listing_result = ARGV
		tests = listing_result == "passed" ? File.readlines(test_list_path, chomp: true).reject(&:empty?) : []
		rows = File.readlines(test_results_path, chomp: true).map { |line|
			target, result, output_path = line.split("\t", 3)
			{"target" => target, "result" => result, "output_path" => output_path}
		}
		targets = rows.map { |row|
			name = row.fetch("target")
			failed_tests = if row.fetch("result") == "failed"
				extracted_failures = File.readlines(row.fetch("output_path"), chomp: true).map { |line|
					match = line.match(/^✘ Test (?!run with )(.+?) (?:recorded an issue|failed after)/)
					next unless match
					display_name = match[1]
					specifier = tests.find { |test| test == "#{name}.#{display_name}" }
					filter = (specifier || name).sub(/\(\)\z/, "")
					{"test" => specifier || display_name, "reproducer" => "swift test --filter #{filter}"}
				}.compact.uniq
				extracted_failures = [{"test" => nil, "reproducer" => "swift test --filter #{name}"}] if extracted_failures.empty?
				extracted_failures
			else
				[]
			end
			row.slice("target", "result").merge(
				"discovered_test_count" => tests.count { |test| test.start_with?(name + ".") },
				"reproducer" => "swift test --filter #{name}",
				"failures" => failed_tests
			)
		}
		failures = targets.flat_map { |target| target.fetch("failures").map { |failure| target.slice("target").merge(failure) } }
		report = {
			"schema" => 1,
			"command" => "swift test",
			"test_listing_result" => listing_result,
			"discovered_test_count" => tests.length,
			"target_count" => targets.length,
			"failed_target_count" => targets.count { |target| target.fetch("result") == "failed" },
			"failed_test_count" => failures.length,
			"passed_test_count" => tests.length - failures.length,
			"failures" => failures,
			"targets" => targets,
			"result" => failures.empty? && listing_result == "passed" ? "passed" : "failed"
		}
		File.write(report_path, JSON.pretty_generate(report) + "\n")
	' "$report_path" "$test_list_path" "$test_results_path" "$test_listing_result"
fi

if ((failed_target_count > 0)) || [[ "$test_listing_result" != 'passed' ]]; then
	exit 1
fi
