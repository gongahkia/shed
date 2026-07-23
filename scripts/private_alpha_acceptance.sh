#!/usr/bin/env bash
set -euo pipefail

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
repo_dir="$(cd "$script_dir/.." && pwd)"
manifest="$repo_dir/qa/private-alpha-v1.json"
artifacts_dir="${ITSY_PRIVATE_ALPHA_ARTIFACTS:-$repo_dir/.build/private-alpha-v1}"
jobs="${ITSY_PRIVATE_ALPHA_JOBS:-1}"
area=""
scenario=""
list_only=0

usage() {
	echo "usage: $0 [--area editing|lsp|git|devloop] [--scenario M1-43] [--artifacts directory] [--jobs count] [--list]" >&2
}

while [[ "$#" -gt 0 ]]; do
	case "$1" in
	--area)
		area="$2"
		shift 2
		;;
	--scenario)
		scenario="$2"
		shift 2
		;;
	--artifacts)
		artifacts_dir="$2"
		shift 2
		;;
	--jobs)
		jobs="$2"
		shift 2
		;;
	--list)
		list_only=1
		shift
		;;
	*)
		usage
		exit 2
		;;
	esac
done

if [[ ! "$jobs" =~ ^[1-9][0-9]*$ ]]; then
	echo "INVALID jobs=$jobs" >&2
	exit 2
fi
if [[ ! -f "$manifest" ]]; then
	echo "REGRESSION manifest=$manifest missing" >&2
	exit 1
fi
if ! command -v ruby >/dev/null 2>&1; then
	echo "BLOCKED requirement=ruby" >&2
	exit 2
fi
if ! command -v swift >/dev/null 2>&1; then
	echo "BLOCKED requirement=swift" >&2
	exit 2
fi

rows="$(ruby -rjson -e '
manifest = JSON.parse(File.read(ARGV.fetch(0)))
area = ARGV.fetch(1)
scenario = ARGV.fetch(2)
matches = manifest.fetch("scenarios").select { |entry| (area == "-" || entry.fetch("area") == area) && (scenario == "-" || entry.fetch("id") == scenario) }
abort("no private-alpha scenario matched") if matches.empty?
matches.each do |entry|
  tests = entry.fetch("tests").map { |test| "#{test.fetch("filter")}|#{test.fetch("fixture")}" }.join(",")
  requirements = entry.fetch("requirements").join(",")
  puts [entry.fetch("id"), entry.fetch("issue"), entry.fetch("milestone"), entry.fetch("area"), entry.fetch("requiredForAlpha") ? "required" : "optional", requirements.empty? ? "-" : requirements, tests].join("\t")
end
' "$manifest" "${area:--}" "${scenario:--}")"

if [[ "$list_only" -eq 1 ]]; then
	while IFS=$'\t' read -r scenario_id issue milestone scenario_area required requirements tests; do
		printf '%s issue=%s milestone=%s area=%s alpha=%s requirements=%s tests=%s\n' "$scenario_id" "$issue" "$milestone" "$scenario_area" "$required" "$requirements" "$tests"
	done <<< "$rows"
	exit 0
fi

mkdir -p "$artifacts_dir"
manifest_log="$artifacts_dir/manifest.log"
if (cd "$repo_dir" && swift test --filter privateAlphaManifestIsCompleteAndExecutable --jobs "$jobs") >"$manifest_log" 2>&1; then
	echo "PASSED scenario=manifest command=swift test --filter privateAlphaManifestIsCompleteAndExecutable log=$manifest_log"
else
	echo "REGRESSION scenario=manifest command=swift test --filter privateAlphaManifestIsCompleteAndExecutable log=$manifest_log" >&2
	exit 1
fi

available_executable() {
	local candidate="$1"
	if [[ "$candidate" == */* ]]; then
		[[ -x "$candidate" ]]
	else
		command -v "$candidate" >/dev/null 2>&1
	fi
}

requirement_available() {
	case "$1" in
	git)
		available_executable git
		;;
	debugpy)
		if [[ -n "${ITSY_DAP_DEBUGPY:-}" && -x "${ITSY_DAP_DEBUGPY:-}" ]]; then
			"${ITSY_DAP_DEBUGPY}" -c 'import debugpy' >/dev/null 2>&1
		else
			available_executable python3 && python3 -c 'import debugpy' >/dev/null 2>&1
		fi
		;;
	js-debug)
		[[ -n "${ITSY_DAP_JS_DEBUG:-}" && -r "${ITSY_DAP_JS_DEBUG:-}" ]] && available_executable "${ITSY_DAP_NODE:-node}"
		;;
	delve)
		available_executable "${ITSY_DAP_DELVE:-dlv}"
		;;
	lldb-dap)
		if [[ -n "${ITSY_DAP_LLDB:-}" ]]; then
			[[ -x "${ITSY_DAP_LLDB}" ]]
		else
			/usr/bin/xcrun --find lldb-dap >/dev/null 2>&1 && /usr/bin/xcrun --find clang >/dev/null 2>&1 && /usr/bin/xcrun --find clang++ >/dev/null 2>&1
		fi
		;;
	codelldb)
		[[ -n "${ITSY_DAP_CODELLDB:-}" && -x "${ITSY_DAP_CODELLDB:-}" ]] && available_executable rustc
		;;
	*)
		return 1
		;;
	esac
}

passed=0
blocked=0
optional_blocked=0
regressions=0
while IFS=$'\t' read -r scenario_id issue milestone scenario_area required requirements tests; do
	missing=()
	if [[ "$requirements" != "-" ]]; then
		IFS=',' read -r -a requirements_array <<< "$requirements"
		for requirement in "${requirements_array[@]}"; do
			if ! requirement_available "$requirement"; then
				missing+=("$requirement")
			fi
		done
	fi
	if [[ "${#missing[@]}" -gt 0 ]]; then
		if [[ "$required" == "required" ]]; then
			echo "BLOCKED scenario=$scenario_id issue=$issue requirements=$(IFS=,; echo "${missing[*]}")"
			blocked=$((blocked + 1))
		else
			echo "OPTIONAL_BLOCKED scenario=$scenario_id issue=$issue requirements=$(IFS=,; echo "${missing[*]}")"
			optional_blocked=$((optional_blocked + 1))
		fi
		continue
	fi

	dap_environment=("PATH=$PATH")
	if [[ "$requirements" == *"debugpy"* || "$requirements" == *"codelldb"* ]]; then
		dap_environment=(ITSY_DAP_REQUIRED=1)
	fi
	IFS=',' read -r -a tests_array <<< "$tests"
	failed=0
	for test_entry in "${tests_array[@]}"; do
		filter="${test_entry%%|*}"
		log="$artifacts_dir/${scenario_id}-${filter}.log"
		if (cd "$repo_dir" && env "${dap_environment[@]}" swift test --filter "$filter" --jobs "$jobs") >"$log" 2>&1; then
			echo "PASSED scenario=$scenario_id issue=$issue test=$filter command=swift test --filter $filter log=$log"
		else
			echo "REGRESSION scenario=$scenario_id issue=$issue test=$filter command=swift test --filter $filter log=$log" >&2
			failed=1
		fi
	done
	if [[ "$failed" -eq 0 ]]; then
		passed=$((passed + 1))
	else
		regressions=$((regressions + 1))
	fi
done <<< "$rows"

echo "SUMMARY passed=$passed blocked=$blocked optional_blocked=$optional_blocked regressions=$regressions"
if [[ "$regressions" -gt 0 ]]; then
	exit 1
fi
if [[ "$blocked" -gt 0 ]]; then
	exit 2
fi
