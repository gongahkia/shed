#!/usr/bin/env bash
set -euo pipefail

repo_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
base_url="${UCD_BASE_URL:-https://www.unicode.org/Public/UCD/latest/ucd}"
out="${1:-$repo_dir/Tests/ItsyEditorTests/Fixtures/UCD/GraphemeBreakTest.txt}"
tmp="$(mktemp "${TMPDIR:-/tmp}/itsy-grapheme-break.XXXXXX")"

cleanup() {
	rm -f "$tmp"
}
trap cleanup EXIT

mkdir -p "$(dirname "$out")"
curl -fsSL "$base_url/auxiliary/GraphemeBreakTest.txt" -o "$tmp"
perl -0pi -e 's/[ \t]+\n/\n/g' "$tmp"
if [[ ! -s "$tmp" ]]; then
	echo "downloaded GraphemeBreakTest.txt is empty" >&2
	exit 1
fi
if ! grep -q "GraphemeBreakTest" "$tmp"; then
	echo "downloaded file does not look like GraphemeBreakTest.txt" >&2
	exit 1
fi
mv "$tmp" "$out"
trap - EXIT
echo "$out"
