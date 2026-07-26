#!/usr/bin/env bash
set -euo pipefail

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
repo_dir="$(cd "$script_dir/.." && pwd)"

if rg -n 'ItsySettingsParser|ItsySettingsValue|ItsySettingsStore\.serialize|settings\.toml|Open User TOML|Open Workspace TOML|global TOML|\[terminal\] TOML' \
	"$repo_dir/README.md" "$repo_dir/docs" "$repo_dir/Sources/ItsyConfig" "$repo_dir/Sources/ItsyConfigCLI" "$repo_dir/Sources/ItsyApp/Settings" "$repo_dir/Tests/ItsyConfigTests" \
	-g '*.md' -g '*.swift'; then
	echo "legacy settings TOML reference found" >&2
	exit 1
fi

bash "$script_dir/test_config_cli_json.sh"
(cd "$repo_dir" && SWT_EXPERIMENTAL_MAXIMUM_PARALLELIZATION_WIDTH=1 swift test --filter ItsyConfigTests)
