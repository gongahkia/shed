#!/usr/bin/env bash
set -euo pipefail

if ! command -v brew >/dev/null 2>&1; then
    echo "Homebrew is required: https://brew.sh" >&2
    exit 1
fi

brew install swiftformat swiftlint create-dmg

echo "Grant Accessibility permission before running olly:"
echo "System Settings > Privacy & Security > Accessibility"

if [[ -t 0 ]]; then
    read -r -p "Open Accessibility settings now? [Y/n] " reply
    case "${reply:-Y}" in
        [Yy]*)
            open "x-apple.systempreferences:com.apple.preference.security?Privacy_Accessibility" || true
            ;;
    esac
fi
