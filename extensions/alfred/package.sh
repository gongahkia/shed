#!/usr/bin/env bash
set -euo pipefail

cd "$(dirname "$0")/Workflow"
rm -f ../Olly.alfredworkflow
zip -qr ../Olly.alfredworkflow .
printf '%s\n' "wrote extensions/alfred/Olly.alfredworkflow"
