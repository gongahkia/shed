#!/usr/bin/env bash
set -euo pipefail

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
repo_dir="$(cd "$script_dir/.." && pwd)"
only_languages="${ITSY_LSP_MATRIX_LANGUAGES:-}"
artifacts_dir="${ITSY_LSP_MATRIX_ARTIFACTS:-$repo_dir/.build/lsp-matrix}"
summary_path=""
all_languages=false
list_only=false

core_languages=(c cpp csharp javascript python tsx typescript)
all_supported_languages=(bash c cpp csharp css dart dockerfile elixir go graphql haskell html java javascript julia json kotlin latex lua markdown markdown-inline nix ocaml php proto python r ruby rust scss sql svelte swift terraform toml tsx typescript vue yaml zig)

usage() {
	echo "usage: $0 [--all] [--only grammar[,grammar...]] [--artifacts directory] [--summary path] [--list]" >&2
}

server_probe() {
	case "$1" in
	bash) echo bash-language-server ;;
	c|cpp) echo clangd ;;
	csharp) echo omnisharp ;;
	dart) echo dart ;;
	dockerfile) echo docker-langserver ;;
	elixir) echo elixir-ls ;;
	go) echo gopls ;;
	haskell) echo haskell-language-server-wrapper ;;
	javascript|tsx|typescript) echo typescript-language-server ;;
	kotlin) echo kotlin-language-server ;;
	lua) echo lua-language-server ;;
	python) echo pyright-langserver ;;
	ruby) echo ruby-lsp ;;
	rust) echo rust-analyzer ;;
	sql) echo sqls ;;
	swift) echo sourcekit-lsp ;;
	terraform) echo terraform-ls ;;
	zig) echo zls ;;
	*) echo no_bundled_server ;;
	esac
}

contains_language() {
	local candidate="$1"
	local language
	for language in "${all_supported_languages[@]}"; do
		[[ "$language" == "$candidate" ]] && return 0
	done
	return 1
}

while [[ "$#" -gt 0 ]]; do
	case "$1" in
	--all)
		all_languages=true
		shift
		;;
	--only)
		only_languages="$2"
		shift 2
		;;
	--artifacts)
		artifacts_dir="$2"
		shift 2
		;;
	--summary)
		summary_path="$2"
		shift 2
		;;
	--list)
		list_only=true
		shift
		;;
	*)
		usage
		exit 2
		;;
	esac
done

if [[ "$list_only" == true ]]; then
	if [[ "$all_languages" == true ]]; then
		listed=("${all_supported_languages[@]}")
	else
		listed=("${core_languages[@]}")
	fi
	for language in "${listed[@]}"; do
		printf '%s\t%s\n' "$language" "$(server_probe "$language")"
	done
	exit 0
fi

selected=()
if [[ -n "$only_languages" ]]; then
	IFS=',' read -r -a selected <<< "$only_languages"
	for language in "${selected[@]}"; do
		if ! contains_language "$language"; then
			echo "unknown LSP matrix grammar: $language" >&2
			exit 2
		fi
	done
else
	if [[ "$all_languages" == true ]]; then
		selected=("${all_supported_languages[@]}")
	else
		selected=("${core_languages[@]}")
	fi
fi

mkdir -p "$artifacts_dir"
if [[ -z "$summary_path" ]]; then
	summary_path="$artifacts_dir/summary.jsonl"
fi
: > "$summary_path"
failed=0

for language in "${selected[@]}"; do
	probe="$(server_probe "$language")"
	log_path="$artifacts_dir/$language.log"
	if (cd "$repo_dir" && ITSY_LSP_MATRIX_LANGUAGES="$language" swift test --filter 'canonicalInventoryLSPProtocolMatrix|supportedLanguagesRunProcessBackedLSPFixtureMatrix' --jobs 1) >"$log_path" 2>&1; then
		status=passed
	else
		status=failed
		failed=1
	fi
	ruby -rjson -e 'puts JSON.generate({"grammar" => ARGV[0], "server_probe" => ARGV[1], "protocol_step" => "launch,open,diagnostics,completion,definition,rename", "status" => ARGV[2], "log_artifact" => ARGV[3]})' "$language" "$probe" "$status" "$log_path" >> "$summary_path"
	printf '%-16s %-34s %-7s %s\n' "$language" "$probe" "$status" "$log_path"
done

[[ "$failed" -eq 0 ]]
