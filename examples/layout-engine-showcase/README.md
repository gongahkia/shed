# Layout Engine Showcase

Three small olly layout engines as case studies for external authors.

Run from the olly repo root:

```sh
swift test --package-path examples/layout-engine-showcase
```

## Case Studies

- `FocusBandLayoutEngine`: focused window gets a wide center band; siblings split side rails.
- `GoldenColumnsLayoutEngine`: columns shrink by a golden-ratio progression.
- `PriorityGridLayoutEngine`: priority windows reserve the first row, remaining windows fill a grid.

All three engines keep `arrange()` synchronous and pure: no AX, no I/O, no async suspension, and no mutation outside returned placements.

Runtime `.dylib` loading is intentionally not demonstrated. olly v0.x external engines are SwiftPM packages loaded through the config sidecar path.
