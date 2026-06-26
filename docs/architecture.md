# Architecture

Status: skeleton. See `NORTHSTAR.md` for locked decisions and vocabulary.

## AX Layer

Owns Accessibility permission state, application/window wrappers, AX observer streams, display discovery, and window movement. This layer must stay AX-only for v0.x and must not call private CGS/SLS/SkyLight APIs.

## Tag Store

Owns River-style tag bitfields, per-display active tag state, window-to-tag assignment, focus MRU, persistence, and tag dispatch decisions.

## Layout Engines

Owns the `LayoutEngine` protocol, engine registry, built-in engines, placement diffing, and the handoff from pure layout results to window movement.

## DSL

Owns the Swift result-builder config surface, keybind declarations, rules, engine bindings, live reload, and typed diagnostics.

## IPC

Owns the Unix-domain socket server, newline-delimited JSON protocol, event subscriptions, and command types consumed by `ollyctl` and integrations.

## Multi-Monitor

Owns per-display workspace views, screen bounds, safe-zone calculation, display hotplug handling, and single-native-Space invariants.
