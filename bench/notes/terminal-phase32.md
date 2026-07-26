# Phase 32 Terminal Checkpoint

Date: 2026-07-07

Implemented slice:

- terminal snapshots now retain attributed `TerminalCell` rows plus compatibility `lines`.
- SGR parser covers reset, bold, italic, underline, inverse, 16-color, 256-color, and truecolor foreground/background state.
- xterm 256-color palette is available in snapshots, with OSC 4 palette overrides and OSC 10/11 default foreground/background overrides.
- OSC parser covers title, current directory, hyperlinks, prompt marks, palette/default-color updates, and ignores OSC 52 clipboard payloads.
- DEC private modes cover alternate screen, bracketed paste, SGR mouse encoding, and normal/button/any mouse tracking.
- `ItsyTerminalView` renders per-cell foreground/background/style/link metadata and reports mouse press/release/drag/move/wheel input to enabled mouse modes.
- bracketed paste wrapping is covered through the view paste path.
- terminal smoke script added at `bench/scripts/terminal_smoke.sh`.

References checked:

- xterm control sequences: https://invisible-island.net/xterm/ctlseqs/ctlseqs.html
- xterm bracketed paste notes: https://invisible-island.net/xterm/xterm-paste64.html
- iTerm2 OSC 8 hyperlink sequence: https://iterm2.com/3.4/documentation-escape-codes.html
- hterm control sequence notes for bracketed paste and mouse modes: https://chromium.googlesource.com/apps/libapps/+/HEAD/hterm/docs/ControlSequences.md

Verification:

```sh
swift test --filter TerminalEnvironment
swift test --filter TerminalEmulator
swift test --filter Terminal
bench/scripts/terminal_smoke.sh
swift test
git diff --check
swift build -c release
```

Result:

- `TerminalEnvironment`: 3 tests passed.
- `TerminalEmulator`: 8 tests passed.
- `Terminal`: 11 tests passed.
- `bench/scripts/terminal_smoke.sh`: passed.
- full `swift test`: 447 tests passed.
- `git diff --check`: passed.
- `swift build -c release`: passed.

Conclusion:

- #4 acceptance is covered by attributed cells, OSC metadata/palette/link support, explicit OSC52 ignore policy, mouse mode translation, bracketed paste tests, environment allowlist tests, and smoke artifacts.
