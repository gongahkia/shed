# Phase 32 Terminal Checkpoint

Date: 2026-07-02

Implemented slice:

- replaced full environment forwarding in `ItsyTerminalSession` with an allowlist.
- kept expected user environment keys such as `HOME`, `USER`, `PATH`, locale, `TMPDIR`, XDG paths, and `SSH_AUTH_SOCK`.
- injected terminal-owned values for `INSIDE_ITSY_TERMINAL`, `TERM`, `TERM_PROGRAM`, `COLORTERM`, `SHELL`, and fallback `LC_CTYPE`.
- made `envp` serialization deterministic by sorting keys.
- added OSC 0/2 window-title parsing for BEL and ST terminators.
- ignores OSC 52 clipboard payloads in the emulator.
- added bracketed-paste private-mode parser tests.

Verification:

```sh
swift test --filter TerminalEnvironment
swift test --filter TerminalEmulator
swift test --filter Terminal
```

Result:

- `TerminalEnvironment`: 3 tests passed.
- `TerminalEmulator`: 3 tests passed.
- `Terminal`: 6 tests passed.

Remaining for #4:

- full SGR/attributed cells.
- OSC coverage beyond title and UI clipboard policy.
- xterm mouse modes and view event translation.
- full bracketed paste validation.
- terminal smoke script/checklist.
