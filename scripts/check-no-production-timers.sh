#!/usr/bin/env bash
set -euo pipefail

paths=()
for path in Package.swift Sources; do
    if [[ -e "$path" ]]; then
        paths+=("$path")
    fi
done

if [[ "${#paths[@]}" -eq 0 ]]; then
    exit 0
fi

pattern='(^|[^A-Za-z0-9_])(NSTimer|Timer[[:space:]]*\(|scheduledTimer|DispatchSourceTimer)([^A-Za-z0-9_]|$)'

if grep -RInE "$pattern" "${paths[@]}"; then
    echo "production timer usage detected" >&2
    exit 1
fi
