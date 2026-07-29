#!/usr/bin/env bash
set -euo pipefail

if [[ "$#" != 2 ]]; then
	echo "usage: $0 <tag> <output-path>" >&2
	exit 64
fi

repo_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
tag="$1"
output_path="$2"
changelog_path="${ITSY_CHANGELOG_PATH:-$repo_dir/docs/CHANGELOG.md}"

if [[ ! -f "$changelog_path" ]]; then
	echo "missing changelog: $changelog_path" >&2
	exit 1
fi

output_dir="$(dirname "$output_path")"
mkdir -p "$output_dir"
temporary_path="$(mktemp "$output_dir/.itsy-release-notes.XXXXXX")"
trap 'rm -f "$temporary_path"' EXIT

if ! awk -v heading="## [$tag]" '
$0 == heading || index($0, heading " -") == 1 {
	found = 1
	next
}
found && /^## / { exit }
found {
	print
	if ($0 ~ /[^[:space:]]/) content = 1
}
END {
	if (!found || !content) exit 1
}
' "$changelog_path" > "$temporary_path"; then
	echo "missing or empty changelog section: $tag" >&2
	exit 1
fi

mv "$temporary_path" "$output_path"
