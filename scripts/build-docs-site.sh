#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$repo_root"

site_dir="${DOCS_SITE_OUTPUT_DIR:-.build/pages}"
work_dir="${DOCS_SITE_WORK_DIR:-.build/docs-site}"
hosting_base_path="${DOCS_HOSTING_BASE_PATH:-olly}"
targets=(ollyKit ollyCore ollyLayouts ollyDSL ollyIPC ollyDiagnostics ollyRuntime)

rm -rf "$site_dir" "$work_dir"
mkdir -p "$site_dir/docs" "$site_dir/api" "$work_dir/catalogs" "$work_dir/archives"

cp docs-site/index.html "$site_dir/index.html"
cp docs-site/styles.css "$site_dir/styles.css"
rsync -a --exclude '*.md' --exclude '*.docc' docs/ "$site_dir/docs/"

ruby scripts/render-docs-site.rb docs "$site_dir"

{
  printf '%s\n' '<!doctype html><html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width, initial-scale=1"><title>Olly API Reference</title><link rel="stylesheet" href="../styles.css"></head><body><header class="site-header"><a class="brand" href="../">Olly Docs</a><nav aria-label="Primary"><a href="../docs/">Guides</a><a href="./">API</a><a href="https://github.com/gongahkia/olly">GitHub</a></nav></header><main><article class="doc-shell"><h1>API Reference</h1><p>Generated DocC archives for the core SwiftPM targets.</p><ul class="doc-index">'
  for target in "${targets[@]}"; do
    lower_target="$(printf '%s' "$target" | tr '[:upper:]' '[:lower:]')"
    printf '<li><a href="./%s/documentation/%s/">%s</a></li>\n' "$target" "$lower_target" "$target"
  done
  printf '%s\n' '</ul></article></main></body></html>'
} > "$site_dir/api/index.html"

for target in "${targets[@]}"; do
  catalog="docs/$target.docc"
  if [[ ! -d "$catalog" ]]; then
    catalog="$work_dir/catalogs/$target.docc"
    mkdir -p "$catalog"
    cat > "$catalog/$target.md" <<MARKDOWN
# $target

Public API reference for the \`$target\` SwiftPM target.
MARKDOWN
  fi

  archive="$work_dir/archives/$target.doccarchive"
  xcrun docc convert "$catalog" --output-path "$archive"

  xcrun docc process-archive transform-for-static-hosting "$archive" \
    --output-path "$site_dir/api/$target" \
    --hosting-base-path "$hosting_base_path/api/$target"
done
