# Adoption Metrics

Status: v0.1 tracking plan. Review cadence: quarterly.

## Metrics

| Metric | Source | Collection |
|---|---|---|
| GitHub stars trajectory | GitHub repository API | `scripts/collect-adoption-metrics.rb` |
| GitHub forks/open issues | GitHub repository API | `scripts/collect-adoption-metrics.rb` |
| Homebrew cask installs | Homebrew formulae analytics API | `scripts/collect-adoption-metrics.rb` |
| Raycast extension installs | Raycast developer/store dashboard | Manual entry; no public install-count API verified on 2026-06-27. |

## Collection

```sh
mkdir -p metrics/adoption
GITHUB_TOKEN=... scripts/collect-adoption-metrics.rb > "metrics/adoption/$(date +%F).json"
```

Defaults:

- `OLLY_GITHUB_REPOSITORY`: inferred from `origin`, override with `owner/repo`.
- `HOMEBREW_CASK_TOKEN`: defaults to `olly`.
- `RAYCAST_EXTENSION`: defaults to `olly`.

## Quarterly Review

Every quarter, compare the newest snapshot against the previous quarter:

- Star growth rate.
- Homebrew install trend over 30, 90, and 365 days.
- Raycast install count from the dashboard.
- Support load from open issues.
- Whether README positioning still matches actual adoption channels.

Store snapshots under `metrics/adoption/`. Do not include user-identifying telemetry.
