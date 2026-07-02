#!/usr/bin/env bash
set -euo pipefail

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
bench_dir="$(cd "$script_dir/.." && pwd)"
corpus_dir="${ITSY_CORPUS_DIR:-$bench_dir/corpus}"

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
	local bytes="$2"
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
	' "$tmp" "$bytes"
	mv "$tmp" "$out"
}

gen_huge_text() {
	local out="$1"
	local bytes="$2"
	local tmp="$out.tmp"
	perl -e '
		use strict;
		use warnings;
		my ($path, $target) = @ARGV;
		open my $fh, ">:raw", $path or die "open $path: $!";
		my $state = 0x51A7E5ED;
		my $written = 0;
		my $chunk_size = 1024 * 1024;
		while ($written < $target) {
			my $chunk = "";
			my $limit = $target - $written;
			$limit = $chunk_size if $limit > $chunk_size;
			while (length($chunk) < $limit) {
				my $offset = $written + length($chunk);
				if ($offset % 80 == 79) {
					$chunk .= "\n";
					next;
				}
				$state = ($state * 1664525 + 1013904223) & 0xffffffff;
				$chunk .= chr(33 + (($state >> 16) % 94));
			}
			print {$fh} $chunk or die "write $path: $!";
			$written += length($chunk);
		}
		close $fh or die "close $path: $!";
	' "$tmp" "$bytes"
	mv "$tmp" "$out"
}

huge_log_bytes="${ITSY_HUGE_LOG_BYTES:-1073741824}"
huge_text_bytes="${ITSY_HUGE_TEXT_BYTES:-1073741824}"

gen_ts "$corpus_dir/small.ts" 1000
gen_ts "$corpus_dir/large.ts" 100000
gen_huge_log "$corpus_dir/huge.log" "$huge_log_bytes"
gen_huge_text "$corpus_dir/huge-text.log" "$huge_text_bytes"
