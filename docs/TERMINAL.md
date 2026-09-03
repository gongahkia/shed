# Terminal

Shed opens an interactive shell in a PTY-backed split. `:terminal list` shows the default shell and extension-provided direct-argv profiles; `:terminal profile <extension:id>` opens one. Terminal session restoration, when enabled, records only a pane's working directory and starts a fresh shell. It never restores commands, arguments, scrollback, process state, or credentials.

## Shell integration

For newly opened interactive Bash, Zsh, and Fish terminals, `terminal.shell.integration` defaults to `true`. Shed creates startup files under `~/.shed/shell-integration/` for that terminal process only; it does not edit `.bashrc`, `.zshrc`, `config.fish`, or another user dotfile. The generated hooks emit Jediterm's private custom-command OSC channel with command start, command completion status, and current-directory events. Fish gets an isolated `XDG_CONFIG_HOME`; its existing `config.fish`, when present, is sourced explicitly before Shed's hook.

Use `:terminal commands` to inspect the retained event history and `:terminal cwd` to show the last reported directory. Turn the feature off with:

```toml
"terminal.shell.integration" = false
```

The integration is unavailable for noninteractive commands and unsupported shells. It is command metadata, not a shell security boundary: command text may contain secrets typed at a prompt, so event history remains in memory only for the terminal's lifetime and should be treated as sensitive.

## Emulator boundary

`TerminalConformanceFixtureTest` feeds deterministic characters into Jediterm's emulator without launching a shell. It covers plain output, ANSI SGR, newline output, Up-arrow and Enter input codes, and an unsupported private CSI sequence. The fixture requires surrounding output to remain intact and escape control bytes not to leak into terminal text.

Shed retains Jediterm/PTy4J behavior for terminal emulation. The fixture is a regression boundary, not a claim of complete xterm-sequence support.
