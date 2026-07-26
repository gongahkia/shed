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
	local object_dir="$out_dir/.objects/$name"
	rm -rf "$object_dir"
	mkdir -p "$object_dir"
	local includes=()
	local objects=()
	local linker=clang
	for source in "$@"; do
		includes+=("-I$(dirname "$source")")
	done
	local index=0
	for source in "$@"; do
		local object="$object_dir/$index-$(basename "$source").o"
		case "$source" in
			*.cc|*.cpp|*.cxx)
				xcrun clang++ -O3 -fPIC -mmacosx-version-min="$min_version" "${includes[@]}" -c "$source" -o "$object"
				linker=clang++
				;;
			*)
				xcrun clang -O3 -fPIC -mmacosx-version-min="$min_version" "${includes[@]}" -c "$source" -o "$object"
				;;
		esac
		objects+=("$object")
		index=$((index + 1))
	done
	xcrun "$linker" -dynamiclib -mmacosx-version-min="$min_version" -install_name "@rpath/ItsyGrammars/$(basename "$lib")" -o "$lib" "${objects[@]}"
}

build_one bash "$grammar_root/bash/src/parser.c" "$grammar_root/bash/src/scanner.c"
build_one c "$grammar_root/c/src/parser.c"
build_one csharp "$grammar_root/c-sharp/src/parser.c" "$grammar_root/c-sharp/src/scanner.c"
build_one cpp "$grammar_root/cpp/src/parser.c" "$grammar_root/cpp/src/scanner.c"
build_one css "$grammar_root/css/src/parser.c" "$grammar_root/css/src/scanner.c"
build_one dart "$grammar_root/dart/src/parser.c" "$grammar_root/dart/src/scanner.c"
build_one dockerfile "$grammar_root/dockerfile/src/parser.c" "$grammar_root/dockerfile/src/scanner.c"
build_one elixir "$grammar_root/elixir/src/parser.c" "$grammar_root/elixir/src/scanner.c"
build_one go "$grammar_root/go/src/parser.c"
build_one graphql "$repo_dir/Sources/CTSGrammars/generated/graphql/parser.c"
build_one haskell "$grammar_root/haskell/src/parser.c" "$grammar_root/haskell/src/scanner.c"
build_one html "$grammar_root/html/src/parser.c" "$grammar_root/html/src/scanner.c"
build_one java "$grammar_root/java/src/parser.c"
build_one javascript "$grammar_root/javascript/src/parser.c" "$grammar_root/javascript/src/scanner.c"
build_one julia "$grammar_root/julia/src/parser.c" "$grammar_root/julia/src/scanner.c"
build_one json "$grammar_root/json/src/parser.c"
build_one kotlin "$grammar_root/kotlin/src/parser.c" "$grammar_root/kotlin/src/scanner.c"
build_one latex "$repo_dir/Sources/CTSGrammars/generated/latex/parser.c" "$grammar_root/latex/src/scanner.c"
build_one lua "$grammar_root/lua/src/parser.c" "$grammar_root/lua/src/scanner.c"
build_one markdown "$grammar_root/markdown/tree-sitter-markdown/src/parser.c" "$grammar_root/markdown/tree-sitter-markdown/src/scanner.c" "$grammar_root/markdown/tree-sitter-markdown-inline/src/parser.c" "$grammar_root/markdown/tree-sitter-markdown-inline/src/scanner.c"
build_one nix "$grammar_root/nix/src/parser.c" "$grammar_root/nix/src/scanner.c"
build_one ocaml "$grammar_root/ocaml/grammars/ocaml/src/parser.c" "$grammar_root/ocaml/grammars/ocaml/src/scanner.c"
build_one php "$grammar_root/php/php/src/parser.c" "$grammar_root/php/php/src/scanner.c"
build_one proto "$repo_dir/Sources/CTSGrammars/generated/proto/parser.c"
build_one python "$grammar_root/python/src/parser.c" "$grammar_root/python/src/scanner.c"
build_one r "$grammar_root/r/src/parser.c" "$grammar_root/r/src/scanner.c"
build_one ruby "$grammar_root/ruby/src/parser.c" "$grammar_root/ruby/src/scanner.c"
build_one rust "$grammar_root/rust/src/parser.c" "$grammar_root/rust/src/scanner.c"
build_one scss "$repo_dir/Sources/CTSGrammars/generated/scss/parser.c" "$grammar_root/scss/src/scanner.c"
build_one sql "$grammar_root/sql/src/parser.c" "$grammar_root/sql/src/scanner.c"
build_one svelte "$grammar_root/svelte/src/parser.c" "$grammar_root/svelte/src/scanner.c"
build_one swift "$grammar_root/swift/src/parser.c" "$grammar_root/swift/src/scanner.c"
build_one terraform "$grammar_root/hcl/dialects/terraform/src/parser.c" "$grammar_root/hcl/dialects/terraform/src/scanner.c"
build_one toml "$grammar_root/toml/src/parser.c" "$grammar_root/toml/src/scanner.c"
build_one typescript "$grammar_root/typescript/typescript/src/parser.c" "$grammar_root/typescript/typescript/src/scanner.c" "$grammar_root/typescript/tsx/src/parser.c" "$grammar_root/typescript/tsx/src/scanner.c"
build_one vue "$repo_dir/Sources/CTSGrammars/generated/vue/parser.c" "$repo_dir/Sources/CTSGrammars/generated/vue/scanner.cc"
build_one yaml "$grammar_root/yaml/src/parser.c" "$grammar_root/yaml/src/scanner.c"
build_one zig "$grammar_root/zig/src/parser.c"

echo "$out_dir"
