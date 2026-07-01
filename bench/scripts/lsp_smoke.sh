#!/usr/bin/env bash
set -euo pipefail

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
probe_script="$script_dir/lsp_diagnostics_probe.rb"
limit_ms="${ITSY_LSP_SMOKE_LIMIT_MS:-5000}"
tmp_root="$(mktemp -d "${TMPDIR:-/tmp}/itsy-lsp-smoke.XXXXXX")"
ok=0
skipped=0
failed=0
trap 'rm -rf "$tmp_root"' EXIT

languages=(swift typescript javascript rust python go c cpp zig elixir kotlin csharp bash dockerfile sql dart haskell lua ruby terraform)

language_enabled() {
	local language="$1"
	[[ -z "${ITSY_LSP_SMOKE_LANGUAGES:-}" || ",${ITSY_LSP_SMOKE_LANGUAGES}," == *",$language,"* ]]
}

server_binary() {
	case "$1" in
	swift) printf '%s\n' sourcekit-lsp ;;
	typescript|javascript) printf '%s\n' typescript-language-server ;;
	rust) printf '%s\n' rust-analyzer ;;
	python) printf '%s\n' pyright-langserver ;;
	go) printf '%s\n' gopls ;;
	c|cpp) printf '%s\n' clangd ;;
	zig) printf '%s\n' zls ;;
	elixir) printf '%s\n' elixir-ls ;;
	kotlin) printf '%s\n' kotlin-language-server ;;
	csharp) printf '%s\n' omnisharp ;;
	bash) printf '%s\n' bash-language-server ;;
	dockerfile) printf '%s\n' docker-langserver ;;
	sql) printf '%s\n' sqls ;;
	dart) printf '%s\n' dart ;;
	haskell) printf '%s\n' haskell-language-server-wrapper ;;
	lua) printf '%s\n' lua-language-server ;;
	ruby) printf '%s\n' ruby-lsp ;;
	terraform) printf '%s\n' terraform-ls ;;
	esac
}

server_command() {
	case "$1" in
	swift) printf '%s\n' "/usr/bin/xcrun sourcekit-lsp" ;;
	typescript|javascript) printf '%s\n' "typescript-language-server --stdio" ;;
	python) printf '%s\n' "pyright-langserver --stdio" ;;
	csharp) printf '%s\n' "omnisharp --languageserver" ;;
	bash) printf '%s\n' "bash-language-server start" ;;
	dockerfile) printf '%s\n' "docker-langserver --stdio" ;;
	dart) printf '%s\n' "dart language-server --protocol=lsp" ;;
	haskell) printf '%s\n' "haskell-language-server-wrapper --lsp" ;;
	terraform) printf '%s\n' "terraform-ls serve" ;;
	*) server_binary "$1" ;;
	esac
}

binary_available() {
	local language="$1"
	local binary="$2"
	if [[ "$language" == "swift" ]]; then
		/usr/bin/xcrun -f sourcekit-lsp >/dev/null 2>&1
	elif [[ "$language" == "rust" ]]; then
		command -v "$binary" >/dev/null 2>&1 && "$binary" --version >/dev/null 2>&1
	else
		command -v "$binary" >/dev/null 2>&1
	fi
}

write_fixture() {
	local language="$1"
	local root="$2"
	case "$language" in
	swift)
		mkdir -p "$root/Sources/Smoke"
		cat >"$root/Package.swift" <<'SWIFT'
// swift-tools-version: 5.9
import PackageDescription
let package = Package(name: "Smoke", targets: [.executableTarget(name: "Smoke")])
SWIFT
		cat >"$root/Sources/Smoke/main.swift" <<'SWIFT'
func smokeTarget(_ value: Int) -> Int { value }
let diagnosticProbe: Int = "not an int"
SWIFT
		printf '%s\n' "$root/Sources/Smoke/main.swift"
		;;
	typescript)
		mkdir -p "$root/src"
		printf '{"compilerOptions":{"strict":true,"noEmit":true},"include":["src/**/*.ts"]}\n' >"$root/tsconfig.json"
		printf 'const diagnosticProbe: number = "not a number";\n' >"$root/src/main.ts"
		printf '%s\n' "$root/src/main.ts"
		;;
	javascript)
		mkdir -p "$root/src"
		printf '{"compilerOptions":{"checkJs":true},"include":["src/**/*.js"]}\n' >"$root/jsconfig.json"
		printf '// @ts-check\nconst diagnosticProbe = ;\n' >"$root/src/main.js"
		printf '%s\n' "$root/src/main.js"
		;;
	rust)
		mkdir -p "$root/src"
		cat >"$root/Cargo.toml" <<'TOML'
[package]
name = "itsy_lsp_smoke"
version = "0.1.0"
edition = "2021"
TOML
		printf 'fn main() {\n    let diagnostic_probe: i32 = "not an int";\n    println!("{diagnostic_probe}");\n}\n' >"$root/src/main.rs"
		printf '%s\n' "$root/src/main.rs"
		;;
	python)
		printf '[tool.pyright]\ntypeCheckingMode = "basic"\n' >"$root/pyproject.toml"
		printf 'def diagnostic_probe(:\n    pass\n' >"$root/main.py"
		printf '%s\n' "$root/main.py"
		;;
	go)
		printf 'module example.com/itsy/lsp-smoke\n\ngo 1.21\n' >"$root/go.mod"
		printf 'package main\n\nfunc main() {\n\tvar diagnosticProbe int = "not an int"\n\t_ = diagnosticProbe\n}\n' >"$root/main.go"
		printf '%s\n' "$root/main.go"
		;;
	c)
		printf -- '-std=c11\n' >"$root/compile_flags.txt"
		printf 'int main(void) {\n    int diagnostic_probe = "not an int";\n    return diagnostic_probe;\n}\n' >"$root/main.c"
		printf '%s\n' "$root/main.c"
		;;
	cpp)
		printf -- '-std=c++17\n' >"$root/compile_flags.txt"
		printf '#include <string>\nint main() {\n    int diagnosticProbe = "not an int";\n    return diagnosticProbe;\n}\n' >"$root/main.cpp"
		printf '%s\n' "$root/main.cpp"
		;;
	zig)
		printf 'pub fn main() void {}\n' >"$root/build.zig"
		printf 'pub fn main() void {\n    const diagnostic_probe: i32 = "not an int";\n    _ = diagnostic_probe;\n}\n' >"$root/main.zig"
		printf '%s\n' "$root/main.zig"
		;;
	elixir)
		mkdir -p "$root/lib"
		cat >"$root/mix.exs" <<'ELIXIR'
defmodule Smoke.MixProject do
  use Mix.Project
  def project, do: [app: :smoke, version: "0.1.0", elixir: "~> 1.15"]
  def application, do: []
end
ELIXIR
		printf 'defmodule Smoke do\n  def broken do\n    1 +\n  end\nend\n' >"$root/lib/smoke.ex"
		printf '%s\n' "$root/lib/smoke.ex"
		;;
	kotlin)
		mkdir -p "$root/src/main/kotlin"
		printf 'pluginManagement { repositories { google(); mavenCentral(); gradlePluginPortal() } }\n' >"$root/settings.gradle.kts"
		printf 'repositories { mavenCentral() }\n' >"$root/build.gradle.kts"
		printf 'fun main() {\n    val diagnosticProbe: Int = "not an int"\n    println(diagnosticProbe)\n}\n' >"$root/src/main/kotlin/Main.kt"
		printf '%s\n' "$root/src/main/kotlin/Main.kt"
		;;
	csharp)
		cat >"$root/Smoke.csproj" <<'XML'
<Project Sdk="Microsoft.NET.Sdk">
  <PropertyGroup>
    <OutputType>Exe</OutputType>
    <TargetFramework>net8.0</TargetFramework>
  </PropertyGroup>
</Project>
XML
		printf '{}\n' >"$root/omnisharp.json"
		printf 'class Program {\n    static void Main() {\n        int diagnosticProbe = "not an int";\n    }\n}\n' >"$root/Program.cs"
		printf '%s\n' "$root/Program.cs"
		;;
	bash)
		printf '#!/usr/bin/env bash\nif then\n  echo broken\nfi\n' >"$root/main.sh"
		printf '%s\n' "$root/main.sh"
		;;
	dockerfile)
		printf 'FROM\nRUN echo smoke\n' >"$root/Dockerfile"
		printf '%s\n' "$root/Dockerfile"
		;;
	sql)
		printf 'SELECT FROM;\n' >"$root/main.sql"
		printf '%s\n' "$root/main.sql"
		;;
	dart)
		mkdir -p "$root/lib"
		printf 'name: itsy_lsp_smoke\nenvironment:\n  sdk: ">=3.0.0 <4.0.0"\n' >"$root/pubspec.yaml"
		printf 'void main() {\n  int diagnosticProbe = "not an int";\n  print(diagnosticProbe);\n}\n' >"$root/lib/main.dart"
		printf '%s\n' "$root/lib/main.dart"
		;;
	haskell)
		mkdir -p "$root/app"
		cat >"$root/itsy-lsp-smoke.cabal" <<'CABAL'
cabal-version: 3.0
name: itsy-lsp-smoke
version: 0.1.0.0
executable itsy-lsp-smoke
  main-is: Main.hs
  hs-source-dirs: app
  default-language: Haskell2010
CABAL
		printf 'module Main where\nmain :: IO ()\nmain = putStrLn 1\n' >"$root/app/Main.hs"
		printf '%s\n' "$root/app/Main.hs"
		;;
	lua)
		printf '{"diagnostics":{"enable":true},"workspace":{"checkThirdParty":false}}\n' >"$root/.luarc.json"
		printf 'local function broken(\n  return 1\nend\n' >"$root/main.lua"
		printf '%s\n' "$root/main.lua"
		;;
	ruby)
		printf 'source "https://rubygems.org"\n' >"$root/Gemfile"
		printf 'def broken(\nend\n' >"$root/main.rb"
		printf '%s\n' "$root/main.rb"
		;;
	terraform)
		mkdir -p "$root/.terraform"
		printf 'terraform {\n  required_version = ">= 1.0"\n}\nresource "null_resource" "bad" {\n' >"$root/main.tf"
		printf '%s\n' "$root/main.tf"
		;;
	esac
}

run_language() {
	local language="$1"
	if ! language_enabled "$language"; then
		return
	fi
	local binary command_string root source_path json_path err_path latency diagnostics error_text
	binary="$(server_binary "$language")"
	if ! binary_available "$language" "$binary"; then
		printf '%-12s %-6s %10s %7s %s\n' "$language" skip - - "$binary missing/unusable"
		skipped=$((skipped + 1))
		return
	fi
	command_string="$(server_command "$language")"
	root="$tmp_root/$language"
	mkdir -p "$root"
	source_path="$(write_fixture "$language" "$root")"
	json_path="$root/probe.json"
	err_path="$root/probe.err"
	if ITSY_LSP_CLIENT_NAME=itsy-lsp-smoke ITSY_LSP_DIAGNOSTICS_LIMIT_MS="$limit_ms" ITSY_LSP_COMMAND="$command_string" ITSY_LSP_LANGUAGE_ID="$language" ITSY_LSP_ROOT="$root" ITSY_LSP_FILE="$source_path" ruby "$probe_script" >"$json_path" 2>"$err_path"; then
		latency="$(ruby -rjson -e 'data = JSON.parse(File.read(ARGV[0])); printf("%.1f", data.fetch("lsp_didopen_to_diagnostics_ms").to_f)' "$json_path")"
		diagnostics="$(ruby -rjson -e 'data = JSON.parse(File.read(ARGV[0])); print data.fetch("lsp_diagnostics_count")' "$json_path")"
		if ruby -e 'exit(ARGV[0].to_f <= ARGV[1].to_f ? 0 : 1)' "$latency" "$limit_ms"; then
			printf '%-12s %-6s %10s %7s %s\n' "$language" ok "$latency" "$diagnostics" "$command_string"
			ok=$((ok + 1))
		else
			printf '%-12s %-6s %10s %7s %s\n' "$language" fail "$latency" "$diagnostics" "diagnostics exceeded ${limit_ms}ms"
			failed=$((failed + 1))
		fi
	else
		error_text="$(ruby -e 'puts File.read(ARGV[0]).lines.first.to_s.strip' "$err_path")"
		printf '%-12s %-6s %10s %7s %s\n' "$language" fail - - "${error_text:-probe failed}"
		failed=$((failed + 1))
	fi
}

printf '%-12s %-6s %10s %7s %s\n' language status latency_ms diags command
for language in "${languages[@]}"; do
	run_language "$language"
done
printf 'ok=%d skipped=%d failed=%d limit_ms=%s\n' "$ok" "$skipped" "$failed" "$limit_ms"
if [[ "$failed" -gt 0 ]]; then
	exit 1
fi
