# Task Runner Gap

## Sources checked

- VS Code Tasks documentation: competitive baseline includes workspace tasks, auto-detection, custom tasks, output behavior, keyboard/command-palette entry points, problem matchers, and compound/background tasks.
- Zed Tasks documentation: competitive baseline includes static tasks, variables, tags, reveal behavior, current-file tasks, and command-palette execution.

## Current implementation slice

- Added task discovery for SwiftPM packages, `package.json` scripts, Makefile targets, root shell scripts, and `scripts/*.sh`.
- Added a process runner that captures stdout, stderr, exit status, and avoids pipe backpressure deadlocks.
- Added a minimal task picker/output panel with refresh and run actions.
- Added discovery and runner tests.

## Not done yet

- No task cancellation.
- No compound/background/watch tasks.
- No configurable problem matchers.

## Next slice

Add task cancellation, then support configurable problem matchers and compound/background/watch tasks.
