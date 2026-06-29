#!/usr/bin/env bash
set -euo pipefail

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
bench_dir="$(cd "$script_dir/.." && pwd)"
corpus_dir="$bench_dir/corpus"

mkdir -p "$corpus_dir"

gen_ts() {
	local out="$1"
	local lines="$2"
	local tmp="$out.tmp"
	awk -v n="$lines" 'BEGIN {
		for (i = 1; i <= n; i++) {
			printf("export class ItsyFixture%06d { value = %d; render(): string { return `line-%06d`; } }\n", i, i, i)
		}
	}' > "$tmp"
	mv "$tmp" "$out"
}

gen_huge_log() {
	local out="$1"
	local tmp="$out.tmp"
	perl -e '
		use strict;
		use warnings;
		my ($path, $target) = @ARGV;
		open my $fh, ">:raw", $path or die "open $path: $!";
		my $line = qq{127.0.0.1 - - [28/Jun/2026:00:00:00 +0000] "GET /itsy/index.html HTTP/1.1" 200 1234 "-" "itsy-bench/1.0"\n};
		my $block = $line x 8192;
		my $written = 0;
		while ($written + length($block) <= $target) {
			print {$fh} $block or die "write $path: $!";
			$written += length($block);
		}
		my $remain = $target - $written;
		print {$fh} substr($block, 0, $remain) if $remain > 0;
		close $fh or die "close $path: $!";
	' "$tmp" 1073741824
	mv "$tmp" "$out"
}

gen_ts "$corpus_dir/small.ts" 1000
gen_ts "$corpus_dir/large.ts" 100000
gen_huge_log "$corpus_dir/huge.log"
