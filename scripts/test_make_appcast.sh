#!/usr/bin/env bash
set -euo pipefail

repo_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
temporary_dir="$(mktemp -d)"
trap 'rm -rf "$temporary_dir"' EXIT
dmg_path="$temporary_dir/Itsy-1.2.3.dmg"
notes_path="$temporary_dir/notes.md"
updates_dir="$temporary_dir/updates"
generator_path="$temporary_dir/generate_appcast"

touch "$dmg_path"
printf '%s\n' '# Itsy 1.2.3' > "$notes_path"
cp /usr/bin/true "$generator_path"

ITSY_DMG_PATH="$dmg_path" SPARKLE_UPDATES_DIR="$updates_dir" SPARKLE_GENERATE_APPCAST="$generator_path" SPARKLE_RELEASE_NOTES_PATH="$notes_path" SPARKLE_REQUIRE_RELEASE_NOTES=1 "$repo_dir/scripts/make_appcast.sh"
test -f "$updates_dir/Itsy-1.2.3.dmg"
test -f "$updates_dir/Itsy-1.2.3.md"
if ITSY_DMG_PATH="$dmg_path" SPARKLE_UPDATES_DIR="$updates_dir/missing" SPARKLE_GENERATE_APPCAST="$generator_path" SPARKLE_REQUIRE_RELEASE_NOTES=1 "$repo_dir/scripts/make_appcast.sh"; then
	echo 'expected missing release notes to fail' >&2
	exit 1
fi
