# Olly

> Status: pre-alpha.

Olly is a pure-Swift macOS window manager experiment. The target design is AX-only, tag-based, and built around hot-swappable layout engines per workspace.

## Current State

Repo bootstrap is in progress. The package builds, tests, and has CI/lint scaffolding, but no window-management behavior is implemented yet.

## Target Shape

- macOS 14+ SwiftPM package.
- Menubar app target: `ollyApp`.
- CLI target: `ollyctl`.
- Library targets: `ollyKit`, `ollyCore`, `ollyLayouts`, `ollyDSL`, `ollyIPC`.
- AX-only window control. No SIP-off requirement.
- River-style tags and per-display layout state.
- Built-in v0.1 engines: NiriScroll, BSP, Manual, Floating, MasterStack.

## Development

```sh
./scripts/bootstrap-dev.sh
swift build -c release
swift test
swiftlint lint --config .swiftlint.yml --strict
./scripts/check-no-private-api.sh
```

## Inspiration Credits

Per NORTHSTAR §3, Olly studies these projects:

- [niri](https://github.com/niri-wm/niri): scrollable tiling model.
- [macniri](https://github.com/J-x-Z/macniri): cautionary macOS port reference.
- [river](https://github.com/riverwm/river): external layout generator architecture.
- [AeroSpace](https://github.com/nikitabobko/AeroSpace): virtual workspace emulation on macOS.
- [Hiro / OmniWM](https://github.com/BarutSRB/OmniWM): macOS multi-layout precedent.
- [Nehir](https://github.com/apphane-dev/nehir): IPC, live config, command palette bar.
- [Paneru](https://github.com/karinushka/paneru): Lua scripting and sliding layout precedent.
- [Hammerspoon](https://github.com/Hammerspoon/hammerspoon): macOS extension ecosystem reference.
- [Swindler](https://github.com/tmandry/Swindler): Swift AX abstraction reference.

## Reference

The name references Olruggio from *Witch Hat Atelier*, where window-ways are common.
