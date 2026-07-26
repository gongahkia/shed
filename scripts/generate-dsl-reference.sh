#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$repo_root"

build_dir="${DSL_REFERENCE_BUILD_DIR:-.build/dsl-reference}"
rm -rf "$build_dir"
mkdir -p "$build_dir"

swift package dump-symbol-graph \
  --pretty-print \
  --minimum-access-level public \
  --skip-synthesized-members

symbol_graph="$(find .build -path "*/symbolgraph/ollyDSL.symbols.json" -type f | sort | tail -n 1)"
if [[ -z "$symbol_graph" ]]; then
  echo "ollyDSL symbol graph not found" >&2
  exit 1
fi

symbol_dir="$(dirname "$symbol_graph")"
xcrun docc convert docs/ollyDSL.docc \
  --additional-symbol-graph-dir "$symbol_dir" \
  --output-path "$build_dir/ollyDSL.doccarchive"

scripts/render-dsl-reference.rb "$symbol_graph" docs/dsl-reference.md
