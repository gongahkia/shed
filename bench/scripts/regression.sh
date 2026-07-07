#!/usr/bin/env bash
set -euo pipefail

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
repo_dir="$(cd "$script_dir/../.." && pwd)"
baseline="${ITSY_REGRESSION_BASELINE:-$repo_dir/bench/results/baseline-itsy-current.json}"
out="${ITSY_REGRESSION_OUT:-$repo_dir/bench/results/regression-current.json}"
runs="${ITSY_REGRESSION_RUNS:-20}"
threshold="${ITSY_REGRESSION_THRESHOLD:-0.05}"
piecetree_ops="${ITSY_REGRESSION_PIECETREE_OPS:-10000}"
piecetree_runs="${ITSY_REGRESSION_PIECETREE_RUNS:-3}"
piecetree_file="${ITSY_REGRESSION_PIECETREE_FILE:-$repo_dir/bench/corpus/huge-text.log}"
slice_length="${ITSY_REGRESSION_SLICE_LENGTH:-32}"
itsybench="${ITSYBENCH:-$repo_dir/.build/release/ItsyBench}"
itsyapp="${ITSY_APP_BINARY:-$repo_dir/.build/release/ItsyApp}"
hyperfine_json="$(mktemp)"
piecetree_json="$(mktemp)"
open_json="$(mktemp)"
lsp_json="$(mktemp)"
lsp_guard_dir="$(mktemp -d)"
lsp_guard_home="$lsp_guard_dir/home"
lsp_guard_marker="$lsp_guard_dir/sourcekit-lsp-spawned"
lsp_probe_script="$script_dir/lsp_diagnostics_probe.rb"
lsp_diagnostics_limit_ms="${ITSY_REGRESSION_LSP_DIAGNOSTICS_LIMIT_MS:-5000}"
open_file="${ITSY_REGRESSION_OPEN_FILE:-$repo_dir/bench/corpus/huge-text.log}"
open_timeout_ms="${ITSY_REGRESSION_OPEN_TIMEOUT_MS:-15000}"

trap 'rm -f "$hyperfine_json" "$piecetree_json" "$open_json" "$lsp_json"; rm -rf "$lsp_guard_dir"' EXIT

setup_lsp_spawn_guard() {
	local bin_dir="$lsp_guard_dir/bin"
	local fake_lsp="$bin_dir/sourcekit-lsp"
	local config_dir="$lsp_guard_home/.config/itsy"
	mkdir -p "$bin_dir" "$config_dir"
	cat >"$fake_lsp" <<SH
#!/usr/bin/env bash
printf '%s\n' "\$0 \$*" > "$lsp_guard_marker"
exit 86
SH
	chmod +x "$fake_lsp"
	ruby -rjson -e '
		fake_lsp, out = ARGV
		config = {
			"swift" => {
				"languageId" => "swift",
				"command" => fake_lsp,
				"args" => [],
				"rootPatterns" => ["Package.swift", ".git"],
				"initOptions" => {},
				"settings" => {}
			}
		}
		File.write(out, JSON.pretty_generate(config) + "\n")
	' "$fake_lsp" "$config_dir/lsp.json"
}

assert_no_lsp_spawn() {
	local label="$1"
	if [[ -f "$lsp_guard_marker" ]]; then
		echo "sourcekit-lsp spawned during $label; LSP must stay lazy until didOpen" >&2
		cat "$lsp_guard_marker" >&2
		exit 1
	fi
}

assert_lsp_lazy_file_open() {
	local workspace="$lsp_guard_dir/workspace"
	local source_dir="$workspace/Sources/LazyProbe"
	local source_file="$source_dir/main.swift"
	local output
	rm -f "$lsp_guard_marker"
	mkdir -p "$source_dir"
	cat >"$workspace/Package.swift" <<'SWIFT'
// swift-tools-version: 5.9
import PackageDescription
let package = Package(name: "LazyProbe", targets: [.executableTarget(name: "LazyProbe")])
SWIFT
	printf 'print("lazy")\n' >"$source_file"
	if ! output="$(HOME="$lsp_guard_home" "$itsyapp" --bench-exit-after-initial-document "$source_file" 2>&1)"; then
		echo "$output" >&2
		exit 1
	fi
	assert_no_lsp_spawn "Swift file launch"
}

if [[ ! -f "$baseline" ]]; then
	echo "missing itsy regression baseline: $baseline" >&2
	exit 1
fi

if [[ ! -x "$itsybench" || ! -x "$itsyapp" ]]; then
	(cd "$repo_dir" && swift build -c release)
elif [[ -n "$(find "$repo_dir/Sources" "$repo_dir/Package.swift" -newer "$itsyapp" -print -quit)" ]]; then
	(cd "$repo_dir" && swift build -c release)
fi

setup_lsp_spawn_guard
assert_lsp_lazy_file_open

mkdir -p "$(dirname "$out")"
app_command="$itsyapp --bench-exit-on-ready"
hyperfine_args=(--shell=none --warmup 0 --runs "$runs" --export-json "$hyperfine_json")
if [[ "${ITSY_REGRESSION_PURGE:-0}" != "0" ]]; then
	hyperfine_args+=(--prepare "purge")
fi
rm -f "$lsp_guard_marker"
HOME="$lsp_guard_home" hyperfine "${hyperfine_args[@]}" "$app_command" >/dev/null
assert_no_lsp_spawn "cold-start launch"
ITSY_LSP_DIAGNOSTICS_LIMIT_MS="$lsp_diagnostics_limit_ms" ruby "$lsp_probe_script" >"$lsp_json"
for _ in $(seq 1 "$piecetree_runs"); do
	piecetree_args=(piecetree --ops "$piecetree_ops" --slice-length "$slice_length")
	if [[ -f "$piecetree_file" ]]; then
		piecetree_args+=(--file "$piecetree_file")
	fi
	"$itsybench" "${piecetree_args[@]}" >>"$piecetree_json"
done
if [[ -f "$open_file" ]]; then
	"$itsybench" open --app "$itsyapp" --file "$open_file" --timeout-ms "$open_timeout_ms" >"$open_json"
fi

ruby -rjson -rtime -e '
	def swift_loc(repo)
		patterns = ["Sources/**/*.swift"]
		patterns.sum do |pattern|
			Dir[File.join(repo, pattern)].reject { |path|
				path.include?("#{File::SEPARATOR}Sources#{File::SEPARATOR}CTSGrammars#{File::SEPARATOR}grammars#{File::SEPARATOR}")
			}.sum do |path|
				File.readlines(path).count { |line| line.strip != "" }
			end
		end
	end

	def value_text(value)
		value >= 1000 ? format("%.0f", value) : format("%.3f", value)
	end

	def escape_command(value)
		value.to_s.gsub("%", "%25").gsub("\r", "%0D").gsub("\n", "%0A")
	end

	baseline_path, hyperfine_path, piecetree_path, open_path, lsp_path, out_path, repo, binary, threshold_arg = ARGV
	baseline = JSON.parse(File.read(baseline_path))
	hyperfine = JSON.parse(File.read(hyperfine_path))
	piecetree_runs = File.readlines(piecetree_path, chomp: true).reject(&:empty?).map { |line| JSON.parse(line) }
	open = File.size?(open_path) ? JSON.parse(File.read(open_path)) : {}
	lsp = File.size?(lsp_path) ? JSON.parse(File.read(lsp_path)) : {}
	bench = hyperfine.fetch("results").first
	current = {
		"cold_start_ready_ms" => bench.fetch("median").to_f * 1000.0,
		"cold_start_ready_min_ms" => bench.fetch("min").to_f * 1000.0,
		"cold_start_ready_max_ms" => bench.fetch("max").to_f * 1000.0,
		"piecetree_random_insert_ns_per_op" => piecetree_runs.map { |run| run.fetch("random_insert_ns_per_op").to_f }.min,
		"piecetree_random_remove_ns_per_op" => piecetree_runs.map { |run| run.fetch("random_remove_ns_per_op").to_f }.min,
		"piecetree_sequential_insert_ns_per_op" => piecetree_runs.map { |run| run.fetch("sequential_insert_ns_per_op").to_f }.min,
		"piecetree_slice_ns_per_op" => piecetree_runs.map { |run| run.fetch("slice_ns_per_op").to_f }.min,
		"binary_size_kb" => File.size(binary).to_f / 1024.0,
		"swift_loc" => swift_loc(repo).to_f
	}
	lsp.each do |key, value|
		current[key] = value.to_f if value.is_a?(Numeric)
	end
	open.each do |key, value|
		current[key] = value.to_f if value.is_a?(Numeric)
	end
	default_threshold = baseline.fetch("threshold", threshold_arg).to_f
	rows = baseline.fetch("metrics").map do |metric|
		name = metric.fetch("name")
		base = metric.fetch("baseline").to_f
		value = current.fetch(name)
		metric_threshold = metric.fetch("threshold", default_threshold).to_f
		direction = metric.fetch("direction", "lower")
		limit = metric.key?("limit") ? metric.fetch("limit").to_f : (direction == "higher" ? base * (1.0 - metric_threshold) : base * (1.0 + metric_threshold))
		failed = direction == "higher" ? value < limit : value > limit
		delta = base.zero? ? 0.0 : ((value - base) / base) * 100.0
		metric.merge(
			"current" => value,
			"delta_percent" => delta,
			"limit" => limit,
			"status" => failed ? "fail" : "pass"
		)
	end
	report = {
		"generated_at" => Time.now.utc.iso8601,
		"baseline" => baseline_path,
		"runs" => hyperfine.fetch("results").first.fetch("times").length,
		"piecetree_runs" => piecetree_runs.length,
		"threshold" => default_threshold,
		"metrics" => rows
	}
	File.write(out_path, JSON.pretty_generate(report) + "\n")
	lines = ["# Itsy regression", "", "| Metric | Baseline | Current | Limit | Status |", "|---|---:|---:|---:|---|"]
	rows.each do |row|
		lines << format("| %s | %s %s | %s %s | %s %s | %s |",
			row.fetch("name"),
			value_text(row.fetch("baseline").to_f),
			row.fetch("unit", ""),
			value_text(row.fetch("current").to_f),
			row.fetch("unit", ""),
			value_text(row.fetch("limit").to_f),
			row.fetch("unit", ""),
			row.fetch("status"))
	end
	puts lines.join("\n")
	if ENV["GITHUB_STEP_SUMMARY"]
		File.open(ENV.fetch("GITHUB_STEP_SUMMARY"), "a") { |file| file.puts(lines.join("\n")) }
	end
	failures = rows.select { |row| row.fetch("status") == "fail" }
	failures.each do |row|
		message = format("%s regressed: current %s %s, limit %s %s, baseline %s %s",
			row.fetch("name"),
			value_text(row.fetch("current").to_f),
			row.fetch("unit", ""),
			value_text(row.fetch("limit").to_f),
			row.fetch("unit", ""),
			value_text(row.fetch("baseline").to_f),
			row.fetch("unit", ""))
		if ENV["GITHUB_ACTIONS"] == "true"
			puts "::error title=Itsy regression::#{escape_command(message)}"
		else
			warn message
		end
	end
	exit(failures.empty? ? 0 : 1)
' "$baseline" "$hyperfine_json" "$piecetree_json" "$open_json" "$lsp_json" "$out" "$repo_dir" "$itsyapp" "$threshold"

echo "$out"
