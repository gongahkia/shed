#!/usr/bin/env bash
set -euo pipefail

repo_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
binary="${DYLD_AUDIT_BINARY:-$repo_dir/.build/release/PicoApp}"
threshold="${DYLD_AUDIT_REBASE_LIMIT:-2000}"
output="$(mktemp)"

trap 'rm -f "$output"' EXIT

if [[ ! -x "$binary" ]]; then
	(cd "$repo_dir" && swift build -c release)
fi

DYLD_PRINT_STATISTICS=1 DYLD_PRINT_STATISTICS_DETAILS=1 "$binary" --bench-exit-on-ready >"$output" 2>&1 || {
	status=$?
	cat "$output"
	exit "$status"
}

rebase_count="$(
	ruby -e '
		text = STDIN.read
		patterns = [
			/(?:total\s+)?rebase\s+fixups?\D+([\d,]+)/i,
			/([\d,]+)\s+(?:total\s+)?rebase\s+fixups?/i,
			/rebases?\D+([\d,]+)\s+fixups?/i
		]
		patterns.each do |pattern|
			if (match = text.match(pattern))
				puts match[1].delete(",")
				exit
			end
		end
	' <"$output"
)"

if [[ -z "$rebase_count" ]]; then
	echo "warning: dyld statistics emitted no rebase fixup count; audit skipped" >&2
	exit 0
fi

if (( rebase_count >= threshold )); then
	echo "dyld rebase fixups $rebase_count >= limit $threshold" >&2
	exit 1
fi

echo "dyld rebase fixups $rebase_count < limit $threshold"
