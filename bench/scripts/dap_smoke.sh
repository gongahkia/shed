#!/usr/bin/env bash
set -euo pipefail

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
repo_root="$(cd "$script_dir/../.." && pwd)"
workspace="$repo_root/bench/corpus"
source_file="$workspace/debug-hello.swift"
config_file="$workspace/.itsy/debug.json"
program="$workspace/.build/debug-hello"
tmp_dir="$(mktemp -d "${TMPDIR:-/tmp}/itsy-dap-smoke.XXXXXX")"
swiftc_path="${SWIFTC:-$(/usr/bin/xcrun --find swiftc)}"
sdk_path="$(/usr/bin/xcrun --sdk macosx --show-sdk-path)"
target_arch="$(uname -m)"
break_line="$(grep -n 'BREAKPOINT' "$source_file" | head -1 | cut -d: -f1)"
breakpoint_store="$tmp_dir/breakpoints.json"

cleanup() {
	rm -rf "$tmp_dir"
}
trap cleanup EXIT

if [[ "${ITSY_DAP_SMOKE_SKIP_LLDB:-0}" != "1" ]]; then
	mkdir -p "$(dirname "$program")"
	"$swiftc_path" -sdk "$sdk_path" -target "$target_arch-apple-macosx14.0" -g -Onone "$source_file" -o "$program"
	python3 - "$source_file" "$break_line" "$breakpoint_store" <<'PY'
import json
import pathlib
import sys

source = pathlib.Path(sys.argv[1]).resolve()
line = int(sys.argv[2])
target = pathlib.Path(sys.argv[3])
target.write_text(json.dumps({
    "files": [{
        "url": source.as_uri(),
        "breakpoints": [{"line": line}]
    }]
}, indent=2), encoding="utf-8")
PY
	ruby "$script_dir/dap_smoke_probe.rb" "$workspace" "$config_file" "$source_file" "$break_line" "$breakpoint_store"
else
	echo "dap smoke skip: lldb"
fi

if python3 - <<'PY'
import importlib.util
try:
    available = importlib.util.find_spec("debugpy.adapter") is not None
except ModuleNotFoundError:
    available = False
raise SystemExit(0 if available else 1)
PY
then
	python_source="$workspace/debug-hello.py"
	python_config="$tmp_dir/debugpy.json"
	python_break_line="$(grep -n 'BREAKPOINT' "$python_source" | head -1 | cut -d: -f1)"
	cat > "$python_config" <<JSON
{
  "adapters": [
    {
      "id": "debugpy",
      "type": "executable",
      "command": "python3",
      "args": ["-m", "debugpy.adapter"]
    }
  ],
  "configurations": [
    {
      "name": "Debug Python Hello",
      "type": "debugpy",
      "request": "launch",
      "program": "\${workspaceFolder}/debug-hello.py",
      "cwd": "\${workspaceFolder}",
      "stopOnEntry": false
    }
  ]
}
JSON
	ITSY_DAP_SMOKE_ADAPTER=debugpy ITSY_DAP_SMOKE_CONFIG="Debug Python Hello" ruby "$script_dir/dap_smoke_probe.rb" "$workspace" "$python_config" "$python_source" "$python_break_line"
else
	echo "dap smoke skip: debugpy not installed"
fi
