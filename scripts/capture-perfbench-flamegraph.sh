#!/usr/bin/env bash
set -euo pipefail

output_dir="${1:-.perfbench-flamegraph}"
iterations="${PERFBENCH_FLAMEGRAPH_ITERATIONS:-80}"
windows="${PERFBENCH_FLAMEGRAPH_WINDOWS:-50}"
soak_events="${PERFBENCH_FLAMEGRAPH_SOAK_EVENTS:-5000}"
time_limit="${PERFBENCH_FLAMEGRAPH_TIME_LIMIT:-15s}"

rm -rf "$output_dir"
mkdir -p "$output_dir"

swift build -c release --product PerfBench
bin_dir="$(swift build -c release --show-bin-path)"
perfbench="$bin_dir/PerfBench"

"$perfbench" \
    --iterations "$iterations" \
    --windows "$windows" \
    --soak-events "$soak_events" \
    --output "$output_dir/PerfBench.json"

xctrace record \
    --template scripts/profile.tracetemplate \
    --time-limit "$time_limit" \
    --output "$output_dir/PerfBench.trace" \
    --no-prompt \
    --launch -- "$perfbench" \
    --iterations "$iterations" \
    --windows "$windows" \
    --soak-events "$soak_events"

xctrace export \
    --input "$output_dir/PerfBench.trace" \
    --toc \
    --output "$output_dir/PerfBench.trace-toc.xml"

printf 'PerfBench flamegraph artifacts written to %s\n' "$output_dir"
