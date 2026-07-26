# Olly Alfred Workflow

Alfred Script Filter workflow for `ollyctl`. Type `olly` and search the top IPC commands.

## Commands

The workflow exposes 15 actions: state, reload, focus, move-window, switch-tag,
move-to-tag, set-engine, and cycle-engine.

## Build

```sh
cd extensions/alfred
./package.sh
open Olly.alfredworkflow
```

The workflow expects `ollyctl` on `PATH`. Override with `OLLYCTL=/path/to/ollyctl` in
Alfred's workflow environment variables.
