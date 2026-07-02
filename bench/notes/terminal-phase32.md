# Phase 32 Terminal Checkpoint

Date: 2026-07-02

Implemented slice:

- replaced full environment forwarding in `ItsyTerminalSession` with an allowlist.
- kept expected user environment keys such as `HOME`, `USER`, `PATH`, locale, `TMPDIR`, XDG paths, and `SSH_AUTH_SOCK`.
- injected terminal-owned values for `INSIDE_ITSY_TERMINAL`, `TERM`, `TERM_PROGRAM`, `COLORTERM`, `SHELL`, and fallback `LC_CTYPE`.
- made `envp` serialization deterministic by sorting keys.

Verification:

```sh
swift test --filter TerminalEnvironment
```

Result:

- `TerminalEnvironment`: 3 tests passed.

Remaining for #4:

- full SGR/attributed cells.
- OSC parsing and clipboard policy.
- xterm mouse modes and view event translation.
- full bracketed paste validation.
- terminal smoke script/checklist.
