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

## Perf Impact

- [ ] No expected impact on `docs/performance.md` budgets.
- [ ] Bench evidence attached or explained.

## Private API

- [ ] No new CGS, SLS, or SkyLight usage.
- [ ] Any private-API-adjacent change has an RFC and fallback path.
