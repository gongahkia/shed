#!/usr/bin/env bash
set -euo pipefail

swift test --filter TerminalEnvironment
swift test --filter TerminalEmulator
swift test --filter Terminal
