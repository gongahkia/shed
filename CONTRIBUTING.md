# Contributing

Check [GitHub issues](https://github.com/gongahkia/itsy/issues) before opening a PR. They track remaining scope, KPIs, and follow-up work.

## PR Gates

- No KPI regression: cold start, idle RSS, large-file RAM, 1 GB open time, scroll FPS, keystroke latency, binary size, LOC.
- Run the relevant SwiftPM checks before review:

```sh
swift build
swift test
swift build -c release
```

- Include bench output when touching launch, rendering, rope, syntax, app startup, or file I/O paths.
- Keep changes scoped to the issue being addressed.

## Dependencies

No new dependencies without a linked issue and explicit rationale. Runtime deps are limited to vendored C for Tree-sitter and grammars.
