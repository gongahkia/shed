#!/usr/bin/env bash
set -euo pipefail

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
repo_dir="$(cd "$script_dir/../.." && pwd)"
root="${ITSY_INTERACTIVE_FIXTURE_ROOT:-$repo_dir/bench/corpus/interactive}"
force="${ITSY_INTERACTIVE_FIXTURE_FORCE:-0}"

generate_workspace() {
	local count="$1"
	local workspace="$root/quick-open-$count"
	local manifest="$workspace/.count"
	if [[ "$force" != "1" && -f "$manifest" && "$(<"$manifest")" == "$count" ]]; then
		return
	fi
	mkdir -p "$workspace"
	local index
	for ((index = 1; index <= count; index++)); do
		local group=$((index % 100))
		local directory="$workspace/src/group-$(printf '%02d' "$group")"
		mkdir -p "$directory"
		printf 'enum Module%05d { static let value = %d }\n' "$index" "$index" >"$directory/Module$(printf '%05d' "$index").swift"
	done
	printf '%s\n' "$count" >"$manifest"
}

generate_large_file() {
	local bytes="$1"
	local extension="$2"
	local output="$root/large/large-$bytes.$extension"
	if [[ "$force" != "1" && -f "$output" && "$(stat -f %z "$output")" == "$bytes" ]]; then
		return
	fi
	mkdir -p "$(dirname "$output")"
	perl -e '
		use strict;
		use warnings;
		my ($path, $target, $extension) = @ARGV;
		my %line = (
			swift => "let itsyPerfValue = 42\n",
			ts => "export const itsyPerfValue = 42;\n",
			py => "itsy_perf_value = 42\n",
			rs => "const ITSY_PERF_VALUE: usize = 42;\n",
		);
		my $chunk = $line{$extension} // die "unsupported extension";
		open my $fh, ">:raw", "$path.tmp" or die "open $path.tmp: $!";
		my $written = 0;
		while ($written + length($chunk) <= $target) {
			print {$fh} $chunk or die "write $path.tmp: $!";
			$written += length($chunk);
		}
		my $remaining = $target - $written;
		print {$fh} substr($chunk, 0, $remaining) if $remaining > 0;
		close $fh or die "close $path.tmp: $!";
		rename "$path.tmp", $path or die "rename $path.tmp: $!";
	' "$output" "$bytes" "$extension"
}

generate_workspace 10000
generate_workspace 50000
generate_large_file 1048576 swift
generate_large_file 104857600 ts
generate_large_file 1073741824 py

(
	cd "$root"
	find . -type f ! -name fixture-tree.sha256 -print | LC_ALL=C sort | while IFS= read -r file; do
		shasum -a 256 "$file"
	done | shasum -a 256 | awk '{print $1}'
) >"$root/fixture-tree.sha256"
printf '%s\n' "$root"
