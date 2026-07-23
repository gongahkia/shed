#!/usr/bin/env bash
set -euo pipefail

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
repo_dir="$(cd "$script_dir/.." && pwd)"
manifest="$repo_dir/qa/external-tool-fixtures-v1.json"
root="$repo_dir/.build/external-tool-fixtures"
env_file=""
offline=false
list_only=false

usage() {
	echo "usage: $0 [--root directory] [--env path] [--offline] [--list]" >&2
}

while [[ "$#" -gt 0 ]]; do
	case "$1" in
	--root) root="$2"; shift 2 ;;
	--env) env_file="$2"; shift 2 ;;
	--offline) offline=true; shift ;;
	--list) list_only=true; shift ;;
	*) usage; exit 2 ;;
	esac
done

[[ -f "$manifest" ]] || { echo "FAILED fixture=manifest reason=missing path=$manifest" >&2; exit 1; }
command -v ruby >/dev/null 2>&1 || { echo "BLOCKED fixture=provisioner reason=missing-command command=ruby" >&2; exit 2; }

ruby -rjson -ruri -e '
	data = JSON.parse(File.read(ARGV.fetch(0)))
	abort("unsupported schema") unless data.fetch("schema_version") == 1
	fixtures = data.fetch("npm") + data.fetch("python") + data.fetch("archives")
	ids = fixtures.map { |fixture| fixture.fetch("id") }
	abort("duplicate fixture id") unless ids.uniq.length == ids.length
	fixtures.each do |fixture|
		abort("invalid fixture id") unless fixture.fetch("id").match?(/\A[a-z0-9-]+\z/)
		abort("invalid fixture version") unless fixture.fetch("version").match?(/\A[0-9A-Za-z._-]+\z/)
	end
	(data.fetch("npm") + data.fetch("python")).each do |fixture|
		abort("invalid fixture URL") unless URI(fixture.fetch("url")).scheme == "https"
	end
	data.fetch("npm").each { |fixture| abort("invalid npm integrity") unless fixture.fetch("integrity").match?(/\Asha512-[A-Za-z0-9+\/=]+\z/) }
	(data.fetch("python") + data.fetch("archives").reject { |fixture| fixture.key?("artifacts") }).each { |fixture| abort("invalid sha256") unless fixture.fetch("sha256").match?(/\A[0-9a-f]{64}\z/) }
	data.fetch("archives").select { |fixture| fixture.key?("artifacts") }.each do |fixture|
		fixture.fetch("artifacts").each_value do |artifact|
			abort("invalid fixture URL") unless URI(artifact.fetch("url")).scheme == "https"
			integrity = artifact["integrity"] || artifact.fetch("sha256")
			abort("invalid integrity") unless integrity.match?(/\A(?:[0-9a-f]{64}|sha512:[0-9a-f]{128})\z/)
		end
	end
' "$manifest"

if [[ "$list_only" == true ]]; then
	ruby -rjson -e '
		data = JSON.parse(File.read(ARGV.fetch(0)))
		data.fetch("npm").each { |fixture| puts [fixture.fetch("id"), fixture.fetch("version"), "npm"].join("\t") }
		data.fetch("python").each { |fixture| puts [fixture.fetch("id"), fixture.fetch("version"), "python-wheel"].join("\t") }
		data.fetch("archives").each { |fixture| puts [fixture.fetch("id"), fixture.fetch("version"), fixture.fetch("format")].join("\t") }
	' "$manifest"
	exit 0
fi

mkdir -p "$root"
root="$(cd "$root" && pwd)"
mkdir -p "$root/downloads" "$root/node_modules" "$root/bin"
blocked=0
failed=0

record() {
	printf '%s fixture=%s %s\n' "$2" "$1" "$3" >&2
}

require_command() {
	if command -v "$1" >/dev/null 2>&1; then
		return 0
	fi
	record provisioner BLOCKED "reason=missing-command command=$1"
	return 2
}

verify_integrity() {
	local archive="$1"
	local expected="$2"
	local actual
	case "$expected" in
	sha512-*) actual="sha512-$(/usr/bin/openssl dgst -sha512 -binary "$archive" | /usr/bin/base64)" ;;
	sha512:*) actual="sha512:$(/usr/bin/shasum -a 512 "$archive" | /usr/bin/awk '{print $1}')" ;;
	*) actual="$(/usr/bin/shasum -a 256 "$archive" | /usr/bin/awk '{print $1}')" ;;
	esac
	[[ "$actual" == "$expected" ]]
}

download_fixture() {
	local fixture="$1"
	local version="$2"
	local url="$3"
	local integrity="$4"
	local archive="$root/downloads/$fixture/${url##*/}"
	mkdir -p "$(dirname "$archive")"
	if [[ ! -f "$archive" ]]; then
		if [[ "$offline" == true ]]; then
			record "$fixture" BLOCKED "reason=offline-cache-miss path=$archive"
			return 2
		fi
		if ! /usr/bin/curl --fail --silent --show-error --location --output "$archive.partial" "$url"; then
			record "$fixture" FAILED "reason=download-failed url=$url"
			return 1
		fi
		mv "$archive.partial" "$archive"
	fi
	if ! verify_integrity "$archive" "$integrity"; then
		record "$fixture" FAILED "reason=integrity-mismatch path=$archive"
		return 1
	fi
	printf '%s\n' "$archive"
}

safe_tar() {
	/usr/bin/tar -tzf "$1" | /usr/bin/awk 'index($0, "../") == 1 || index($0, "/../") > 0 || index($0, "/") == 1 { exit 1 }'
}

write_exec_wrapper() {
	printf '%s\n' '#!/usr/bin/env bash' "exec $(printf '%q' "$2") \"\$@\"" >"$root/bin/$1"
	chmod +x "$root/bin/$1"
}

write_omnisharp_wrapper() {
	printf '%s\n' '#!/usr/bin/env bash' "export DOTNET_ROOT=$(printf '%q' "$root/dotnet-6.0.419")" 'export DOTNET_MULTILEVEL_LOOKUP=0' "exec $(printf '%q' "$root/omnisharp/OmniSharp") \"\$@\"" >"$root/bin/omnisharp"
	chmod +x "$root/bin/omnisharp"
}

write_node_wrapper() {
	printf '%s\n' '#!/usr/bin/env bash' "exec $(printf '%q' "$2") $(printf '%q' "$3") \"\$@\"" >"$root/bin/$1"
	chmod +x "$root/bin/$1"
}

write_js_debug_bridge() {
	/usr/bin/install -m 755 "$repo_dir/scripts/js_debug_stdio_bridge.js" "$root/js-debug-stdio-bridge.js"
	write_node_wrapper itsy-js-debug-node "$(command -v node)" "$root/js-debug-stdio-bridge.js"
}

write_debugpy_wrapper() {
	printf '%s\n' '#!/usr/bin/env bash' 'set -euo pipefail' "export PYTHONPATH=$(printf '%q' "$root/python")"'${PYTHONPATH:+:$PYTHONPATH}' "exec $(printf '%q' "$1") \"\$@\"" >"$root/bin/itsy-debugpy-python"
	chmod +x "$root/bin/itsy-debugpy-python"
}

provision_npm() {
	local fixture="$1" version="$2" url="$3" integrity="$4" package="$5" entrypoint="$6" command="$7"
	local archive stage target
	archive="$(download_fixture "$fixture" "$version" "$url" "$integrity")" || return $?
	target="$root/node_modules/$package"
	if [[ ! -f "$target/$entrypoint" ]]; then
		safe_tar "$archive" || { record "$fixture" FAILED "reason=unsafe-archive path=$archive"; return 1; }
		stage="$(mktemp -d "$root/.npm-stage.XXXXXX")"
		if ! /usr/bin/tar -xzf "$archive" -C "$stage" || [[ ! -d "$stage/package" ]] || [[ -e "$target" ]]; then
			record "$fixture" FAILED "reason=extract-failed path=$archive"
			return 1
		fi
		mv "$stage/package" "$target"
		rmdir "$stage" 2>/dev/null || true
	fi
	[[ -f "$target/$entrypoint" ]] || { record "$fixture" FAILED "reason=missing-entrypoint path=$target/$entrypoint"; return 1; }
	if [[ "$command" != "-" ]]; then
		write_node_wrapper "$command" "$(command -v node)" "$target/$entrypoint"
	fi
	record "$fixture" READY "version=$version integrity=verified path=$target"
}

provision_python() {
	local fixture="$1" version="$2" url="$3" sha256="$4" package="$5"
	local archive
	archive="$(download_fixture "$fixture" "$version" "$url" "$sha256")" || return $?
	if [[ ! -d "$root/python/$package" ]]; then
		if ! python3 -m pip install --disable-pip-version-check --no-cache-dir --no-deps --no-index --target "$root/python" "$archive"; then
			record "$fixture" FAILED "reason=wheel-install-failed path=$archive"
			return 1
		fi
	fi
	[[ -d "$root/python/$package" ]] || { record "$fixture" FAILED "reason=missing-package path=$root/python/$package"; return 1; }
	write_debugpy_wrapper "$(command -v python3)"
	record "$fixture" READY "version=$version integrity=verified path=$root/python/$package"
}

provision_archive() {
	local fixture="$1" version="$2" url="$3" sha256="$4" format="$5" destination="$6" required_path="$7"
	local archive
	archive="$(download_fixture "$fixture" "$version" "$url" "$sha256")" || return $?
	if [[ ! -e "$root/$required_path" ]]; then
		case "$format" in
		tar_gzip)
			safe_tar "$archive" || { record "$fixture" FAILED "reason=unsafe-archive path=$archive"; return 1; }
			mkdir -p "$root/$destination"
			/usr/bin/tar -xzf "$archive" -C "$root/$destination" || { record "$fixture" FAILED "reason=extract-failed path=$archive"; return 1; }
			;;
		zip)
			mkdir -p "$root/$destination"
			/usr/bin/ditto -x -k "$archive" "$root/$destination" || { record "$fixture" FAILED "reason=extract-failed path=$archive"; return 1; }
			;;
		*) record "$fixture" FAILED "reason=unsupported-format format=$format"; return 1 ;;
		esac
	fi
	[[ -e "$root/$required_path" ]] || { record "$fixture" FAILED "reason=missing-entrypoint path=$root/$required_path"; return 1; }
	record "$fixture" READY "version=$version integrity=verified path=$root/$required_path"
}

run_fixture() {
	local rc=0
	"$@" || rc=$?
	if [[ "$rc" -eq 0 ]]; then
		return 0
	fi
	if [[ "$rc" -eq 2 ]]; then
		blocked=1
	else
		failed=1
	fi
}

for command in curl openssl shasum tar ditto node python3 xcrun; do
	run_fixture require_command "$command"
done
if [[ "$blocked" -eq 1 || "$failed" -eq 1 ]]; then
	[[ "$failed" -eq 1 ]] && exit 1
	exit 2
fi

while IFS=$'\t' read -r fixture version url integrity package entrypoint command; do
	run_fixture provision_npm "$fixture" "$version" "$url" "$integrity" "$package" "$entrypoint" "$command"
done < <(ruby -rjson -e 'JSON.parse(File.read(ARGV.fetch(0))).fetch("npm").each { |fixture| puts [fixture.fetch("id"), fixture.fetch("version"), fixture.fetch("url"), fixture.fetch("integrity"), fixture.fetch("package"), fixture.fetch("entrypoint"), fixture.fetch("command")].join("\t") }' "$manifest")

while IFS=$'\t' read -r fixture version url sha256 package; do
	run_fixture provision_python "$fixture" "$version" "$url" "$sha256" "$package"
done < <(ruby -rjson -e 'JSON.parse(File.read(ARGV.fetch(0))).fetch("python").each { |fixture| puts [fixture.fetch("id"), fixture.fetch("version"), fixture.fetch("url"), fixture.fetch("sha256"), fixture.fetch("package")].join("\t") }' "$manifest")

architecture="$(uname -m)"
case "$architecture" in
arm64|x86_64) ;;
*) record provisioner BLOCKED "reason=unsupported-architecture architecture=$architecture"; blocked=1 ;;
esac
if [[ "$blocked" -eq 0 ]]; then
	while IFS=$'\t' read -r fixture version url sha256 format destination required_path; do
		run_fixture provision_archive "$fixture" "$version" "$url" "$sha256" "$format" "$destination" "$required_path"
	done < <(ruby -rjson -e '
		data, architecture = ARGV
		JSON.parse(File.read(data)).fetch("archives").each do |fixture|
			artifact = fixture["artifacts"]&.fetch(architecture)
			integrity = artifact ? (artifact["integrity"] || artifact.fetch("sha256")) : fixture.fetch("sha256")
			puts [fixture.fetch("id"), fixture.fetch("version"), artifact ? artifact.fetch("url") : fixture.fetch("url"), integrity, fixture.fetch("format"), fixture.fetch("destination"), fixture.fetch("required_path")].join("\t")
		end
	' "$manifest" "$architecture")
fi

if [[ "$blocked" -eq 0 && "$failed" -eq 0 ]]; then
	if clangd="$(/usr/bin/xcrun --find clangd 2>/dev/null)"; then
		write_exec_wrapper clangd "$clangd"
		record clangd READY "source=xcrun path=$clangd"
	else
		record clangd BLOCKED "reason=system-tool-missing command=xcrun-clangd"
		blocked=1
	fi
	if lldb_dap="$(/usr/bin/xcrun --find lldb-dap 2>/dev/null)"; then
		write_exec_wrapper lldb-dap "$lldb_dap"
		record lldb-dap READY "source=xcrun path=$lldb_dap"
	else
		record lldb-dap BLOCKED "reason=system-tool-missing command=xcrun-lldb-dap"
		blocked=1
	fi
	if [[ -f "$root/omnisharp/OmniSharp" ]]; then
		chmod +x "$root/omnisharp/OmniSharp"
	fi
	if [[ -x "$root/omnisharp/OmniSharp" ]]; then
		write_omnisharp_wrapper
	else
		record omnisharp FAILED "reason=non-executable path=$root/omnisharp/OmniSharp"
		failed=1
	fi
	write_js_debug_bridge
fi

if [[ "$failed" -eq 1 ]]; then
	exit 1
fi
if [[ "$blocked" -eq 1 ]]; then
	exit 2
fi

if [[ -n "$env_file" ]]; then
	mkdir -p "$(dirname "$env_file")"
	printf '%s\n' \
		"PATH=$root/bin:$PATH" \
		"ITSY_DAP_DEBUGPY=$root/bin/itsy-debugpy-python" \
		"ITSY_DAP_JS_DEBUG=$root/js-debug/src/dapDebugServer.js" \
		"ITSY_DAP_NODE=$root/bin/itsy-js-debug-node" \
		"ITSY_DAP_LLDB=$root/bin/lldb-dap" >"$env_file"
fi

printf 'READY fixture-set=external-tools root=%s\n' "$root"
