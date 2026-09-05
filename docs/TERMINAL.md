# Terminal

Shed opens an interactive shell in a PTY-backed bottom split. `:terminal split side` places a configured-default terminal beside the active pane, while `:terminal split bottom` makes the default placement explicit. `:terminal list` shows that default, detected standard shell profiles, extension-provided direct-argv profiles, and the resolved execution target for the current file; `:terminal profile <builtin:id>` or `:terminal profile <extension:id>` opens one. After an explicit `:remote use <id>`, new ordinary terminals for files in that connected SSH/Docker/WSL workspace run through that provider; after an explicit `:container connect`, they run through `devcontainer exec`. A selected remote execution session takes precedence when both apply. Terminal session restoration, when enabled, records only a pane's host working directory and starts a fresh local shell. It never restores commands, arguments, scrollback, process state, remote/container connections, or credentials.

On a native Windows host, a local default terminal resolves to the system Windows PowerShell executable when available, then `ComSpec`/`cmd.exe`. Shell-backed local task and command execution use PowerShell's `-NoProfile -Command` or `cmd.exe /d /s /c` direct argument form. SSH, Docker, WSL, and Dev Container defaults remain selected by their remote provider rather than inheriting the host shell.

## Shell profiles

`terminal.default.profile` defaults to `system`, preserving the host's existing default-shell behavior. `:terminal list` detects locally executable standard profiles without launching them: Bash, Zsh, Fish, and PowerShell 7 on POSIX; PowerShell 7, Windows PowerShell, and Command Prompt on Windows. Select one for future ordinary terminals with a qualified profile id:

```toml
"terminal.default.profile" = "builtin:bash"
```

An installed extension profile uses its full `<extension-id>:<id>` id. The setting does not accept arbitrary executable paths or arguments. If its selected built-in or extension profile is unavailable, Shed opens the system shell and reports that fallback. Detection checks only the configured shell and executable paths; it does not start a shell, probe versions, or edit shell configuration.

## Shell integration

For newly opened interactive Bash, Zsh, Fish, PowerShell, and PowerShell 7 terminals, `terminal.shell.integration` defaults to `true`. Shed creates startup files under `~/.shed/shell-integration/` for that terminal process only; it does not edit `.bashrc`, `.zshrc`, `config.fish`, a PowerShell profile, or another user dotfile. The generated hooks emit Jediterm's private custom-command OSC channel with command start, command completion status, and current-directory events. Fish gets an isolated `XDG_CONFIG_HOME`; its existing `config.fish`, when present, is sourced explicitly before Shed's hook. PowerShell starts with `-NoProfile`, explicitly sources its current-user profiles in their normal order, then loads Shed's hook. When the existing Enter binding is the stock PSReadLine `AcceptLine`, the hook emits a command event before submitting input; custom Enter bindings are retained and use a history-based post-execution command-event fallback instead.

Use `:terminal commands` to inspect the retained event history and `:terminal cwd` to show the last reported directory. Turn the feature off with:

```toml
"terminal.shell.integration" = false
```

The integration is unavailable for noninteractive commands and unsupported shells. It is command metadata, not a shell security boundary: command text may contain secrets typed at a prompt, so event history remains in memory only for the terminal's lifetime and should be treated as sensitive.

## Emulator boundary

`TerminalConformanceFixtureTest` feeds deterministic characters into Jediterm's emulator without launching a shell. It covers plain output, ANSI SGR, newline output, Up-arrow and Enter input codes, and an unsupported private CSI sequence. The fixture requires surrounding output to remain intact and escape control bytes not to leak into terminal text.

Shed retains Jediterm/PTy4J behavior for terminal emulation. The fixture is a regression boundary, not a claim of complete xterm-sequence support.

## Output links

Shed makes an existing local source location such as `src/Main.java:12:4` clickable, resolving a relative path against the terminal's latest shell-reported directory when available, otherwise its startup directory. Clicking opens that regular local file at the reported one-based line and column. An explicitly connected Dev Container maps only absolute paths beneath its verified mounted workspace to existing files beneath the matching host workspace. For a connected SSH/container/WSL workspace that declares a remote language-server root, an absolute terminal path under that remote root is also clickable only when the corresponding local-mirror file exists. An ordinary terminal opened through `:remote use` also resolves relative paths from its requested local-mirror subdirectory, and only accepts results that remain inside that mirror. It also makes `http://` and `https://` output clickable for an explicit system-browser handoff.

It does not link a missing file, map a remote path outside the declared mirror root, infer a current remote directory after a user changes it in an SSH shell, parse every compiler's diagnostic format, or open arbitrary URI schemes. Link detection itself makes no network request; a browser handoff occurs only after the user clicks an HTTP(S) link.
