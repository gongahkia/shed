#!/usr/bin/env bash
set -euo pipefail

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
repo_dir="$(cd "$script_dir/.." && pwd)"
source_dir="$repo_dir/Sources/ItsyApp"
ownership_file="$source_dir/App/AppFeatureOwnership.swift"

usage() {
	echo "usage: $0 [--source-dir path] [--ownership-file path]" >&2
}

while [[ "$#" -gt 0 ]]; do
	case "$1" in
	--source-dir)
		source_dir="$2"
		shift 2
		;;
	--ownership-file)
		ownership_file="$2"
		shift 2
		;;
	*)
		usage
		exit 2
		;;
	esac
done

[[ -d "$source_dir" ]] || { echo "error: source directory does not exist: $source_dir" >&2; exit 2; }
[[ -f "$ownership_file" ]] || { echo "error: ownership file does not exist: $ownership_file" >&2; exit 2; }

declared_roots=()
while IFS= read -r root; do
	declared_roots+=("$root")
done < <(rg -o 'sourceRoot: "[^"]+"' "$ownership_file" | sed -E 's/.*"([^"]+)"/\1/' | sort -u)

if [[ "${#declared_roots[@]}" -eq 0 ]]; then
	echo "error: no feature roots declared in $ownership_file" >&2
	exit 1
fi

contains_root() {
	local target="$1"
	local root
	for root in "${declared_roots[@]}"; do
		[[ "$root" == "$target" ]] && return 0
	done
	return 1
}

status=0
while IFS= read -r directory; do
	root="$(basename "$directory")"
	if ! contains_root "$root"; then
		echo "error: undeclared feature root: $root" >&2
		status=1
	fi
	disallowed="$(rg -n --glob '*.swift' '^[[:space:]]*(@[A-Za-z_]+[[:space:]]+)?import[[:space:]]+ItsyApp([[:space:]]|$)' "$directory" || true)"
	if [[ -n "$disallowed" ]]; then
		echo "error: feature root $root imports aggregate ItsyApp target:" >&2
		echo "$disallowed" >&2
		status=1
	fi
done < <(find "$source_dir" -mindepth 1 -maxdepth 1 -type d -print | sort)

for root in "${declared_roots[@]}"; do
	if [[ ! -d "$source_dir/$root" ]]; then
		echo "error: declared feature root is missing: $root" >&2
		status=1
	fi
done

exit "$status"
