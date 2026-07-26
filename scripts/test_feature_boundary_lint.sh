#!/usr/bin/env bash
set -euo pipefail

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
fixture_dir="$(mktemp -d "${TMPDIR:-/tmp}/itsy-feature-lint.XXXXXX")"
trap 'rm -rf "$fixture_dir"' EXIT

source_dir="$fixture_dir/Sources/ItsyApp"
ownership_file="$source_dir/App/AppFeatureOwnership.swift"
mkdir -p "$source_dir/App" "$source_dir/Feature"
printf '%s\n' 'enum AppFeatureOwnership {' 'let boundaries = [.init(sourceRoot: "App"), .init(sourceRoot: "Feature")]' '}' > "$ownership_file"
printf '%s\n' 'import Foundation' > "$source_dir/Feature/Feature.swift"
bash "$script_dir/lint_feature_boundaries.sh" --source-dir "$source_dir" --ownership-file "$ownership_file"

printf '%s\n' '@testable import ItsyApp' > "$source_dir/Feature/Feature.swift"
if bash "$script_dir/lint_feature_boundaries.sh" --source-dir "$source_dir" --ownership-file "$ownership_file" > "$fixture_dir/output" 2>&1; then
	echo "expected aggregate target import to fail lint" >&2
	exit 1
fi
rg -q 'imports aggregate ItsyApp target' "$fixture_dir/output"
printf '%s\n' 'passed feature-boundary-lint'
