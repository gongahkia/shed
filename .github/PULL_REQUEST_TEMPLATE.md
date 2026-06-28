## Summary

## NORTHSTAR Refs

- ref:N§

## RFC

- Locked-decision changes (`NORTHSTAR.md` §4): RFC issue #

## Verification

- [ ] `swift build -c release`
- [ ] `swift test`
- [ ] `swiftlint lint --config .swiftlint.yml --strict`
- [ ] `./scripts/check-no-private-api.sh`

## DSL Changes

- [ ] If `Sources/ollyDSL/*.swift` changed, `examples/` and public doc comments were updated.

## Perf Impact

- [ ] No expected impact on `NORTHSTAR.md` §12a / `docs/performance.md` budgets; PerfBench CI output reviewed.
- [ ] PerfBench CI artifact/link attached or explained when impact is possible.

## Private API

- [ ] No new CGS, SLS, or SkyLight usage.
- [ ] Any private-API-adjacent change has an RFC and fallback path.
