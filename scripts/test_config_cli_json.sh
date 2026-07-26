#!/usr/bin/env bash
set -euo pipefail

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
repo_dir="$(cd "$script_dir/.." && pwd)"
workspace_dir="$(mktemp -d "${TMPDIR:-/tmp}/itsy-config-cli.XXXXXX")"
trap 'rm -rf "$workspace_dir"' EXIT

binary_path="${ITSY_CONFIG_CLI_BINARY:-$repo_dir/.build/debug/itsy}"
if [[ ! -x "$binary_path" ]]; then
	(cd "$repo_dir" && swift build --product itsy)
fi

path_json="$($binary_path config --workspace "$workspace_dir" path --json)"
CLI_PATH_JSON="$path_json" WORKSPACE_DIR="$workspace_dir" /usr/bin/ruby -rjson -e '
	path = JSON.parse(ENV.fetch("CLI_PATH_JSON")).fetch("path")
	root = File.realpath(File.dirname(File.dirname(path)))
	expected_root = File.realpath(ENV.fetch("WORKSPACE_DIR"))
	abort "unexpected settings path: #{path}" unless root == expected_root && File.basename(File.dirname(path)) == ".itsy" && File.basename(path) == "settings.json"
'

"$binary_path" config --workspace "$workspace_dir" set editor.tab_width 2
settings_path="$workspace_dir/.itsy/settings.json"
SETTINGS_PATH="$settings_path" /usr/bin/ruby -rjson -e '
	document = JSON.parse(File.read(ENV.fetch("SETTINGS_PATH")))
	abort "missing schema version" unless document.fetch("schema_version").is_a?(Integer)
	abort "unexpected tab width" unless document.fetch("settings").fetch("editor").fetch("tab_width") == 2
'

get_json="$($binary_path config --workspace "$workspace_dir" get editor.tab_width --json)"
CLI_GET_JSON="$get_json" /usr/bin/ruby -rjson -e '
	record = JSON.parse(ENV.fetch("CLI_GET_JSON"))
	abort "unexpected get result" unless record == {"key" => "editor.tab_width", "value" => "2"}
'

list_json="$($binary_path config --workspace "$workspace_dir" list --json)"
CLI_LIST_JSON="$list_json" /usr/bin/ruby -rjson -e '
	records = JSON.parse(ENV.fetch("CLI_LIST_JSON"))
	abort "missing list record" unless records.include?({"key" => "editor.tab_width", "value" => "2"})
'

"$binary_path" config --workspace "$workspace_dir" reset editor.tab_width
reset_json="$($binary_path config --workspace "$workspace_dir" get editor.tab_width --json)"
CLI_RESET_JSON="$reset_json" /usr/bin/ruby -rjson -e '
	record = JSON.parse(ENV.fetch("CLI_RESET_JSON"))
	abort "unexpected reset result" unless record == {"key" => "editor.tab_width", "value" => "4"}
'

if "$binary_path" config --workspace "$workspace_dir" set ui.font_scale 1.25 >/dev/null 2>&1; then
	echo "workspace UI setting unexpectedly succeeded" >&2
	exit 1
fi
