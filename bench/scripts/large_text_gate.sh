#!/usr/bin/env bash
set -euo pipefail

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
repo_dir="$(cd "$script_dir/../.." && pwd)"
fixture="${ITSY_LARGE_TEXT_FIXTURE:-$repo_dir/bench/corpus/huge-text.log}"
fixture_bytes="${ITSY_LARGE_TEXT_BYTES:-1073741824}"
rss_budget_kb="${ITSY_LARGE_TEXT_RSS_BUDGET_KB:-1572864}"
operations="${ITSY_LARGE_TEXT_OPS:-128}"
itsybench="${ITSYBENCH:-$repo_dir/.build/release/ItsyBench}"

case "$fixture_bytes" in
	*[!0-9]*|'') echo "invalid ITSY_LARGE_TEXT_BYTES" >&2; exit 2 ;;
esac
case "$rss_budget_kb" in
	*[!0-9]*|'') echo "invalid ITSY_LARGE_TEXT_RSS_BUDGET_KB" >&2; exit 2 ;;
esac

fixture_dir="$(dirname "$fixture")"
mkdir -p "$fixture_dir"
if [[ ! -f "$fixture" || "$(stat -f %z "$fixture")" != "$fixture_bytes" ]]; then
	ITSY_CORPUS_DIR="$fixture_dir" ITSY_GENERATE_HUGE_LOG=0 ITSY_HUGE_TEXT_BYTES="$fixture_bytes" "$script_dir/gen_corpus.sh"
fi

if [[ ! -x "$itsybench" || -n "$(find "$repo_dir/Sources" "$repo_dir/Package.swift" -newer "$itsybench" -print -quit)" ]]; then
	(cd "$repo_dir" && swift build -c release)
fi

result="$("$itsybench" piecetree --ops "$operations" --file "$fixture" --mmap-contract --mmap-rss-budget-kb "$rss_budget_kb")"
ruby -rjson -e '
	payload = JSON.parse(STDIN.read)
	expected_bytes, expected_budget = ARGV.map(&:to_i)
	checks = {
		"mmap_load_bytes" => payload["mmap_load_bytes"] == expected_bytes,
		"mmap_search_offset" => payload["mmap_search_offset"].is_a?(Integer),
		"mmap_edit_length" => payload["mmap_edit_length"] == expected_bytes,
		"mmap_save_bytes" => payload["mmap_save_bytes"] == expected_bytes,
		"mmap_rss_budget_kb" => payload["mmap_rss_budget_kb"] == expected_budget,
		"mmap_contract_passed" => payload["mmap_contract_passed"] == true
	}
	unless checks.values.all?
		warn JSON.pretty_generate(payload)
		abort "large-text contract failed: #{checks.reject { |_, passed| passed }.keys.join(", ")}"
	end
	puts JSON.generate(payload)
' "$fixture_bytes" "$rss_budget_kb" <<<"$result"
