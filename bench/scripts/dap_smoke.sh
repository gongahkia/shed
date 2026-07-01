#!/usr/bin/env bash
set -euo pipefail

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
repo_root="$(cd "$script_dir/../.." && pwd)"
workspace="$repo_root/bench/corpus"
source_file="$workspace/debug-hello.swift"
config_file="$workspace/.itsy/debug.json"
program="$workspace/.build/debug-hello"
swiftc_path="${SWIFTC:-$(/usr/bin/xcrun --find swiftc)}"
sdk_path="$(/usr/bin/xcrun --sdk macosx --show-sdk-path)"
target_arch="$(uname -m)"
break_line="$(grep -n 'BREAKPOINT' "$source_file" | head -1 | cut -d: -f1)"

mkdir -p "$(dirname "$program")"
"$swiftc_path" -sdk "$sdk_path" -target "$target_arch-apple-macosx14.0" -g -Onone "$source_file" -o "$program"
ruby "$script_dir/dap_smoke_probe.rb" "$workspace" "$config_file" "$source_file" "$break_line"
