#!/usr/bin/env bash
set -euo pipefail

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
repo_dir="$(cd "$script_dir/../.." && pwd)"
grammar_root="$repo_dir/Sources/CTSGrammars/grammars"
out_dir="${GRAMMAR_DYLIB_DIR:-$repo_dir/.build/release/ItsyGrammars}"
min_version="${MACOSX_DEPLOYMENT_TARGET:-13.0}"

mkdir -p "$out_dir"

build_one() {
	local name="$1"
	shift
	local lib="$out_dir/libitsy-tree-sitter-$name.dylib"
	local args=(-dynamiclib -O3 -fPIC -mmacosx-version-min="$min_version" -install_name "@rpath/ItsyGrammars/$(basename "$lib")" -o "$lib")
	for source in "$@"; do
		args+=("-I$(dirname "$source")")
	done
	args+=("$@")
	xcrun clang "${args[@]}"
}

build_one c "$grammar_root/c/src/parser.c"
build_one cpp "$grammar_root/cpp/src/parser.c" "$grammar_root/cpp/src/scanner.c"
build_one css "$grammar_root/css/src/parser.c" "$grammar_root/css/src/scanner.c"
build_one go "$grammar_root/go/src/parser.c"
build_one html "$grammar_root/html/src/parser.c" "$grammar_root/html/src/scanner.c"
build_one javascript "$grammar_root/javascript/src/parser.c" "$grammar_root/javascript/src/scanner.c"
build_one json "$grammar_root/json/src/parser.c"
build_one markdown "$grammar_root/markdown/tree-sitter-markdown/src/parser.c" "$grammar_root/markdown/tree-sitter-markdown/src/scanner.c" "$grammar_root/markdown/tree-sitter-markdown-inline/src/parser.c" "$grammar_root/markdown/tree-sitter-markdown-inline/src/scanner.c"
build_one python "$grammar_root/python/src/parser.c" "$grammar_root/python/src/scanner.c"
build_one rust "$grammar_root/rust/src/parser.c" "$grammar_root/rust/src/scanner.c"
build_one toml "$grammar_root/toml/src/parser.c" "$grammar_root/toml/src/scanner.c"
build_one typescript "$grammar_root/typescript/typescript/src/parser.c" "$grammar_root/typescript/typescript/src/scanner.c" "$grammar_root/typescript/tsx/src/parser.c" "$grammar_root/typescript/tsx/src/scanner.c"
build_one yaml "$grammar_root/yaml/src/parser.c" "$grammar_root/yaml/src/scanner.c"

echo "$out_dir"
