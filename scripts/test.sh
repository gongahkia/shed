#!/usr/bin/env bash
set -euo pipefail

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
repo_dir="$(cd "$script_dir/.." && pwd)"
report_path="${ITSY_TEST_REPORT:-}"
coverage_raw_dir="${ITSY_TEST_COVERAGE_RAW_DIR:-}"
coverage_dir="${ITSY_TEST_COVERAGE_DIR:-}"
parallel_width="${SWT_EXPERIMENTAL_MAXIMUM_PARALLELIZATION_WIDTH:-1}"

if [[ -n "$report_path" ]]; then
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

export SWT_EXPERIMENTAL_MAXIMUM_PARALLELIZATION_WIDTH="$parallel_width"
raw_index=0
for test_target in "${test_targets[@]}"; do
	args=(test --filter "$test_target")
	if [[ -n "$coverage_raw_dir" ]]; then
		args=(test --enable-code-coverage --filter "$test_target")
	fi
	if (cd "$repo_dir" && swift "${args[@]}"); then
		if [[ -n "$coverage_raw_dir" ]]; then
			while IFS= read -r raw_profile; do
				raw_index=$((raw_index + 1))
				cp "$raw_profile" "$coverage_raw_dir/${raw_index}.profraw"
			done < <(find "$coverage_dir" -maxdepth 1 -type f -name '*.profraw' -print | sort)
		fi
		if [[ -n "$report_path" ]]; then
			printf '{"target":"%s","result":"passed"}\n' "$test_target" >> "$report_path"
		fi
	else
		if [[ -n "$report_path" ]]; then
			printf '{"target":"%s","result":"failed"}\n' "$test_target" >> "$report_path"
		fi
		exit 1
	fi
done
