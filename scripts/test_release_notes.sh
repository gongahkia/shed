#!/usr/bin/env bash
set -euo pipefail

repo_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
temporary_dir="$(mktemp -d)"
trap 'rm -rf "$temporary_dir"' EXIT
changelog_path="$temporary_dir/CHANGELOG.md"
output_path="$temporary_dir/release.md"

printf '%s\n' \
	'# Changelog' \
	'' \
	'## [v1.2.3] - 2026-07-25' \
	'' \
	'### Added' \
	'' \
	'- Published release notes.' \
	'' \
	'## [v1.2.2] - 2026-07-24' \
	'' \
	'- Older release.' > "$changelog_path"

ITSY_CHANGELOG_PATH="$changelog_path" "$repo_dir/scripts/extract_release_notes.sh" v1.2.3 "$output_path"
grep -Fq 'Published release notes.' "$output_path"
! grep -Fq 'Older release.' "$output_path"
if ITSY_CHANGELOG_PATH="$changelog_path" "$repo_dir/scripts/extract_release_notes.sh" v9.9.9 "$output_path"; then
	echo 'expected missing release section to fail' >&2
	exit 1
fi
