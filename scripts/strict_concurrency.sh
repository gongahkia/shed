#!/usr/bin/env bash
set -euo pipefail

swift build -Xswiftc -strict-concurrency=complete -Xswiftc -warnings-as-errors
