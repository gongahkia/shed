#!/usr/bin/env bash
set -euo pipefail

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
repo_dir="$(cd "$script_dir/.." && pwd)"
output="${ITSY_PRIVATE_ALPHA_PREFLIGHT_OUTPUT:-$repo_dir/.build/private-alpha-preflight.json}"
format="text"
rows="$(mktemp "${TMPDIR:-/tmp}/itsy-private-alpha-preflight.XXXXXX")"
required_blocked=0
optional_blocked=0

trap 'rm -f "$rows"' EXIT

usage() {
	echo "usage: $0 [--output path] [--format text|json]" >&2
}

while [[ "$#" -gt 0 ]]; do
	case "$1" in
	--output) output="$2"; shift 2 ;;
	--format) format="$2"; shift 2 ;;
	*) usage; exit 2 ;;
	esac
done
if [[ "$format" != text && "$format" != json ]]; then
	echo "invalid format: $format" >&2
	exit 2
fi

record() {
	local category="$1"
	local name="$2"
	local required="$3"
	local status="$4"
	local detail="$5"
	local guidance="$6"
	printf '%s\t%s\t%s\t%s\t%s\t%s\n' "$category" "$name" "$required" "$status" "$detail" "$guidance" >>"$rows"
	if [[ "$status" == blocked ]]; then
		if [[ "$required" == true ]]; then
			required_blocked=$((required_blocked + 1))
		else
			optional_blocked=$((optional_blocked + 1))
		fi
	fi
}

available() {
	local candidate="$1"
	if [[ "$candidate" == */* ]]; then
		[[ -x "$candidate" ]]
	else
		command -v "$candidate" >/dev/null 2>&1
	fi
}

check_command() {
	local name="$1"
	local guidance="$2"
	if available "$name"; then
		record core "$name" true ready "$(command -v "$name")" ""
	else
		record core "$name" true blocked "not found on PATH" "$guidance"
	fi
}

lsp_guidance() {
	case "$1" in
	sourcekit-lsp) printf '%s\n' 'Install Xcode command-line tools: xcode-select --install' ;;
	typescript-language-server) printf '%s\n' 'Install: npm i -g typescript typescript-language-server' ;;
	rust-analyzer) printf '%s\n' 'Install: rustup component add rust-analyzer' ;;
	pyright-langserver) printf '%s\n' 'Install: npm i -g pyright' ;;
	gopls) printf '%s\n' 'Install: go install golang.org/x/tools/gopls@latest' ;;
	clangd) printf '%s\n' 'Install: brew install llvm; add LLVM bin to PATH' ;;
	zls) printf '%s\n' 'Install: brew install zls' ;;
	elixir-ls) printf '%s\n' 'Install: brew install elixir-ls' ;;
	kotlin-language-server) printf '%s\n' 'Install: brew install fwcd/kotlin-language-server/kotlin-language-server' ;;
	omnisharp) printf '%s\n' 'Install: brew install omnisharp' ;;
	bash-language-server) printf '%s\n' 'Install: npm i -g bash-language-server' ;;
	docker-langserver) printf '%s\n' 'Install: npm i -g dockerfile-language-server-nodejs' ;;
	sqls) printf '%s\n' 'Install: brew install sqls' ;;
	dart) printf '%s\n' 'Install: brew install dart-sdk' ;;
	haskell-language-server-wrapper) printf '%s\n' 'Install: brew install haskell-language-server' ;;
	lua-language-server) printf '%s\n' 'Install: brew install lua-language-server' ;;
	ruby-lsp) printf '%s\n' 'Install: gem install ruby-lsp' ;;
	terraform-ls) printf '%s\n' 'Install: brew install hashicorp/tap/terraform-ls' ;;
	*) printf '%s\n' "Install $1 and expose it on PATH" ;;
	esac
}

check_lsp_server() {
	local grammar="$1"
	local server="$2"
	if [[ "$server" == no_bundled_server ]]; then
		record lsp "$grammar" false not_applicable "no bundled server configured" ""
		return
	fi
	if [[ "$server" == sourcekit-lsp ]]; then
		if /usr/bin/xcrun --find sourcekit-lsp >/dev/null 2>&1; then
			record lsp "$grammar" true ready "$(/usr/bin/xcrun --find sourcekit-lsp)" ""
		else
			record lsp "$grammar" true blocked "sourcekit-lsp unavailable through xcrun" "$(lsp_guidance "$server")"
		fi
	elif available "$server"; then
		record lsp "$grammar" true ready "$(command -v "$server")" ""
	else
		record lsp "$grammar" true blocked "server $server not found on PATH" "$(lsp_guidance "$server")"
	fi
}

check_dap() {
	local name="$1"
	local required="$2"
	local is_available=1
	local detail=""
	local guidance=""
	case "$name" in
	debugpy)
		if ! available python3 || ! python3 -c 'import debugpy' >/dev/null 2>&1; then
			is_available=0; detail="python3 -m debugpy.adapter unavailable"; guidance="Install: python3 -m pip install debugpy"
		fi
		;;
	js-debug)
		if [[ -z "${ITSY_DAP_JS_DEBUG:-}" || ! -r "${ITSY_DAP_JS_DEBUG:-}" ]] || ! available "${ITSY_DAP_NODE:-node}"; then
			is_available=0; detail="ITSY_DAP_JS_DEBUG file or Node runtime unavailable"; guidance="Set ITSY_DAP_JS_DEBUG to js-debug's adapter entrypoint and ensure node is on PATH"
		fi
		;;
	delve)
		if ! available "${ITSY_DAP_DELVE:-dlv}"; then
			is_available=0; detail="dlv unavailable"; guidance="Install: brew install delve"
		fi
		;;
	lldb-dap)
		if [[ -n "${ITSY_DAP_LLDB:-}" ]]; then
			if [[ ! -x "${ITSY_DAP_LLDB}" ]]; then
				is_available=0; detail="ITSY_DAP_LLDB is not executable"; guidance="Set ITSY_DAP_LLDB to an executable lldb-dap path"
			fi
		elif ! /usr/bin/xcrun --find lldb-dap >/dev/null 2>&1 || ! /usr/bin/xcrun --find clang >/dev/null 2>&1 || ! /usr/bin/xcrun --find clang++ >/dev/null 2>&1; then
			is_available=0; detail="lldb-dap or Clang toolchain unavailable through xcrun"; guidance="Install Xcode command-line tools: xcode-select --install"
		fi
		;;
	codelldb)
		if [[ -z "${ITSY_DAP_CODELLDB:-}" || ! -x "${ITSY_DAP_CODELLDB:-}" ]] || ! available rustc; then
			is_available=0; detail="CodeLLDB executable or rustc unavailable"; guidance="Set ITSY_DAP_CODELLDB to CodeLLDB's adapter executable and install Rust: rustup toolchain install stable"
		fi
		;;
	esac
	if [[ "$is_available" == 1 ]]; then
		record dap "$name" "$required" ready "adapter prerequisites available" ""
	else
		record dap "$name" "$required" blocked "$detail" "$guidance"
	fi
}

check_command swift 'Install Xcode command-line tools: xcode-select --install'
check_command ruby 'Install Ruby: brew install ruby'
check_command git 'Install Xcode command-line tools: xcode-select --install'
check_command osascript 'macOS System Events is required for release UI smoke'
check_command screencapture 'macOS screen capture is required for release UI smoke'

while IFS=$'\t' read -r grammar server; do
	check_lsp_server "$grammar" "$server"
done < <("$script_dir/lsp_matrix.sh" --list)

check_dap debugpy true
check_dap js-debug true
check_dap delve true
check_dap lldb-dap true
check_dap codelldb false

mkdir -p "$(dirname "$output")"
ruby -rjson -rtime -e '
	rows, output, required_blocked, optional_blocked = ARGV
	matrix = File.readlines(rows, chomp: true).reject(&:empty?).map do |line|
		category, name, required, status, detail, guidance = line.split("\t", 6)
		{"category" => category, "name" => name, "required" => required == "true", "status" => status, "detail" => detail, "guidance" => guidance}
	end
	status = required_blocked.to_i > 0 ? "blocked" : "ready"
	puts JSON.pretty_generate({"schema" => 1, "generated_at" => Time.now.utc.iso8601, "status" => status, "required_blocked" => required_blocked.to_i, "optional_blocked" => optional_blocked.to_i, "matrix" => matrix})
' "$rows" "$output" "$required_blocked" "$optional_blocked" >"$output"

if [[ "$format" == text ]]; then
	printf '%-8s %-24s %-8s %-16s %s\n' category capability required status detail
	while IFS=$'\t' read -r category name required status detail guidance; do
		printf '%-8s %-24s %-8s %-16s %s\n' "$category" "$name" "$required" "$status" "$detail"
		if [[ -n "$guidance" ]]; then
			printf '  guidance: %s\n' "$guidance"
		fi
	done <"$rows"
	printf 'SUMMARY required_blocked=%s optional_blocked=%s report=%s\n' "$required_blocked" "$optional_blocked" "$output"
else
	cat "$output"
fi

if (( required_blocked > 0 )); then
	exit 2
fi
