# Terminal

Shed opens an interactive shell in a PTY-backed bottom split. `:terminal split side` places a new default-shell terminal beside the active pane, while `:terminal split bottom` makes the default placement explicit. `:terminal list` shows the default shell and extension-provided direct-argv profiles; `:terminal profile <extension:id>` opens one. After an explicit `:container connect`, new ordinary terminals for files in that workspace run through `devcontainer exec` instead, including selected extension profiles. Terminal session restoration, when enabled, records only a pane's host working directory and starts a fresh local shell. It never restores commands, arguments, scrollback, process state, container connections, or credentials.

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

## Output links

Shed makes an existing local source location such as `src/Main.java:12:4` clickable, resolving a relative path against the terminal's latest shell-reported directory when available, otherwise its startup directory. Clicking opens that regular local file at the reported one-based line and column. An explicitly connected Dev Container maps only absolute paths beneath its verified mounted workspace to existing files beneath the matching host workspace. For a connected SSH/container/WSL workspace that declares a remote language-server root, an absolute terminal path under that remote root is also clickable only when the corresponding local-mirror file exists. It also makes `http://` and `https://` output clickable for an explicit system-browser handoff.

It does not link a missing file, map a remote path outside the declared mirror root, infer a current remote directory after a user changes it in an SSH shell, parse every compiler's diagnostic format, or open arbitrary URI schemes. Link detection itself makes no network request; a browser handoff occurs only after the user clicks an HTTP(S) link.
