# Contributing

Read [NORTHSTAR.md](NORTHSTAR.md) before opening a PR. It defines scope, KPIs, architecture, and non-goals.

## PR Gates

- No KPI regression: cold start, idle RSS, large-file RAM, 1 GB open time, scroll FPS, keystroke latency, binary size, LOC.
- Run the relevant SwiftPM checks before review:

```sh
swift build
swift test
swift build -c release
```

- Include bench output when touching launch, rendering, rope, syntax, app startup, or file I/O paths.
- Keep changes scoped to the TODO item or issue being addressed.

## Dependencies

No new dependencies without a linked issue and explicit rationale. Runtime deps are limited to vendored C for Tree-sitter and grammars.
