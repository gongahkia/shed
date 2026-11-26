# Agent commands in the normal terminal

Agent output-producing commands use Shed's existing terminal widget, shell profile,
PTY, prompt, scrollback and animated cursor. The API opens an ordinary Terminal
split in the approved directory, or reuses its idle normal terminal when the shell
and directory match and the human has not interacted with it and visibly types the quoted arguments before
pressing Enter once. It does not create an agent-specific console or mirror a
separately executed process. The same shell execution supplies the visible output
and the result returned to the agent.

Use this route for lint, formatter, build, test and diagnostic commands where the
output matters. Continue using the existing source APIs for reading, searching,
editing and file operations. A formatter can produce both terminal output and
ordinary source-change visualization. There is no automatic interception or
classification of an external agent's shell commands: integrations request this
route explicitly.

## Built-in Assistant

The Assistant's existing command review now offers **Run in terminal once**. The
exact argv, working directory and deadline are still reviewed before execution.
After approval, the commands appear in normal terminal splits, and their results
feed the original conversation. Denial launches no command or terminal.

## Local CLI

In the editor, select the local workspace and explicitly start:

```text
:agent api start terminal
```

This enables reviewed terminal requests alongside editor control. Ordinary
`:agent api start control`, metadata, navigation and read-only attachments cannot
launch terminal commands. Use the private descriptor and matching rebuilt client
as described in [using Shed with agents](USING_SHED_WITH_AGENTS.md).

Create a command request file. For example, this repository's compile command is:

```json
{"reason":"Compile the saved project and inspect compiler output","commands":[{"argv":["mvn","-q","-DskipTests","compile"],"directory":".","timeoutSeconds":60}]}
```

Then submit, inspect or cancel a request:

```sh
shed ctl terminal-run --commands-file /absolute/path/checks.json --agent Codex --request-id compile-1
shed ctl terminal-status --request-id compile-1
shed ctl terminal-cancel --request-id compile-1
```

`SHED_CONTROL` or `--connect` supplies the private attachment descriptor. The first
call returns `awaiting-approval`; a visible **Run once / Reject** dialog shows the
exact request. Approval expires after 60 seconds. Reusing an ID with the same
request returns its existing state, rather than executing again. One request may
be active at a time, with eight retained request records per editor.

States are `awaiting-approval`, `running`, `completed`, `rejected`, `cancelled` or
`failed`. A completed request contains structured `result.results` with each
command's `exitCode`, `output` and `error`; completion does not imply that every
exit code is zero. Feedback is bounded and explicitly marked when shortened;
terminal scrollback remains available. Attachment revocation cancels its pending
requests and active work.

## Ownership and lifecycle

The provider logo, action label and spinner are painted beside the normal terminal
cursor, using a single terminal row. They follow the cursor without entering PTY
input, shell history or command output. The badge uses free space to the cursor's
right and compacts to a logo near the edge; it hides when that space contains
output, during selection/scrollback, IME composition or an alternate-screen app.
Known providers use their bundled logos; custom agents retain a text label.
There is no agent typing message or badge in the status bar.

The cursor, provider badge and existing terminal pane edge glow share the action
color: green while typing, amber while waiting or running. The cursor returns to
its normal appearance and the decorations stop when control ends. Cursor motion
remains the normal terminal animation, including while watched without keyboard
focus. Reduced-motion preferences keep the decoration static. The terminal and
its output remain available after execution.

Formatter changes in an already-visible source pane use the ordinary source edit
replay while the terminal remains active. Follow pauses when no suitable source
pane is visible, rather than replacing the terminal with a source file.

Human keyboard input takes over, interrupting the automated command and stopping
further typing. Cancellation and deadlines send the terminal's normal Ctrl+C;
the shell remains open with its output. This is foreground terminal interruption,
not containment of detached processes. Closing the terminal also ends automation.

Shell integration must be enabled. Bash, Zsh, Fish and PowerShell are supported;
unsupported profiles fail explicitly without changing the user's selected shell.
Arguments are quoted literally for that shell. Terminal control characters and
command lines over 4,096 UTF-16 units are rejected. The command plan accepts at
most four commands with workspace-contained directories and 1–600 second execution
deadlines. Unsaved workspace buffers must be saved before disk-based checks; Shed
does not save them automatically. Commands retain the account's normal shell and
OS access and require approval of their exact arguments.

## Demo and verification

```sh
make demo-terminal
make demo-terminal LOOP=1
```

The disposable demo uses the normal terminal with system Bash and an empty private
home. It sends real authenticated CLI requests, rejects one without execution,
approves a fixed harmless `printf` request through the real review control, and
checks the returned output/status and retained terminal. The one-shot run also
executes a fixed Java formatter fixture through a separately approved terminal
request, checking real output, ordinary source replay and unchanged terminal
pane/focus. Its Java user home and temporary directory remain inside the fixture. Loop mode then replays
only that fixed harmless fixture command in the same normal terminal without
repeating approval dialogs or growing additional panes. Each loop leaves the
completed output visible for 2.5 seconds before the next command. Successive loop
cycles exercise Codex, Claude Code and OpenCode provider badges. Human interaction or a
change in terminal reuse eligibility pauses the demo and leaves the IDE open; it
does not automatically resume after handoff. Close the window to finish.
It is scripted and makes
no model call. This feature demo does not qualify unrestricted native-shell
benchmarks. Headless command-driver and authenticated transport tests use the
portable fixture runner and do not launch a shell.

The subsequent [animation edge-case checks](performance/agent-animation-edges/INDEX.md)
exercise cancellation immediately before Enter, terminal closure at each phase,
queued completion after cancellation, repeated retirement, overlapping badge owners,
scrollback, selection, IME and alternate-screen suppression using in-memory terminal
fixtures. This coverage does not qualify real shell process confinement or detached
process cancellation.
