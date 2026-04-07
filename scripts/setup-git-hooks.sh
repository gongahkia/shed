#!/usr/bin/env bash
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$REPO_ROOT"

if [[ ! -f ".githooks/pre-push" ]]; then
  echo "Missing .githooks/pre-push hook file."
  exit 1
fi

chmod +x .githooks/pre-push
git config core.hooksPath .githooks

echo "Git hooks configured."
echo "pre-push now runs: mvn -q test"
