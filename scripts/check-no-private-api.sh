#!/usr/bin/env bash
set -euo pipefail

paths=()
for path in Package.swift Sources Tests; do
    if [[ -e "$path" ]]; then
        paths+=("$path")
    fi
done

if [[ "${#paths[@]}" -eq 0 ]]; then
    exit 0
fi

pattern='(^|[^A-Za-z0-9_])(CGS[A-Z][A-Za-z0-9_]*|SLS[A-Z][A-Za-z0-9_]*|SkyLight)([^A-Za-z0-9_]|$)'

if grep -RInE "$pattern" "${paths[@]}"; then
    echo "private macOS API symbol references detected" >&2
    exit 1
fi
