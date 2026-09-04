# Workspace Tasks

Shed reads workspace developer commands from `<workspace>/.shedtasks`. Tasks are for build, test, run, lint, and debug commands. They do nothing until `:task run <name>` is entered. In a multi-root session, `<workspace>` is the deepest configured folder containing the active file; for a scratch or outside file it is the Explorer-selected folder.

## Canonical TOML

```toml
schema_version = 1

[task.check]
command = "./gradlew test ${relativeFile}"
cwd = "tools"
shell = "login"
problem_matcher = "generic"
presentation = "on_failure"

[task.check.env]
CI = "true"
```

`command` is required. `cwd` defaults to `${workspaceFolder}` and must resolve to an existing directory within the workspace. Environment names match `[A-Za-z_][A-Za-z0-9_]*`; values are strings. Unknown fields, invalid types, an unsupported schema version, or unsafe cwd prevent every task in that file from running and are shown by `:task list`.

| Field | Default | Values |
| :--- | :--- | :--- |
| `shell` | `login` | `login` uses the configured login shell and obeys `shell.command.enabled`; `direct` parses command arguments and starts no shell |
| `problem_matcher` | `generic` | `generic` accepts `path:line[:column]: message` and resolves relative paths from task `cwd`; `none` leaves quickfix unchanged |
| `presentation` | `on_failure` | `always`, `on_failure`, or `never`; controls task-output scratch buffers, not the final status or quickfix update |

Supported variables in `command`, `cwd`, and environment values are `${workspaceFolder}`, `${file}`, `${relativeFile}`, and `${fileBasename}`. File variables require a file-backed active buffer; `${relativeFile}` must remain inside the workspace. Quote `${file}` in a command when its path can contain spaces.

## Commands

| Command | Action |
| :--- | :--- |
| `:task`, `:task ui` | Open the docked Tasks/Jobs panel |
| `:task text`, `:task text list` | Show validated tasks in the legacy scratch buffer |
| `:task add <name> <command>` | Add a default login-shell task and write canonical TOML |
| `:task remove <name>` | Remove a task while preserving other task settings |
| `:task dry-run <name>` | Resolve variables and show the command, policy, cwd, and environment keys without starting a process |
| `:task run <name>` | Explicitly start a task |
| `:task remote <connection-id> <name>` | Explicitly run a task through a connected remote workspace that contains this task's project root |
| `:task remote-dry-run <connection-id> <name>` | Resolve and show the remote command request without starting it |
| `:task cancel <job-id>` | Cancel a running task; `:jobcancel <job-id>` also works |

`:jobs` reports the asynchronous task state. Cancellation destroys the running process, produces a cancelled task result, and does not parse or present partial output as a completed run.

## Explicit remote tasks

Remote tasks are never selected automatically. First open a remote workspace, then choose it by id:

```text
:remote open ssh://developer@host/absolute/project
:task remote <connection-id> check
```

Shed resolves the normal `.shedtasks` plan from the local mirror, maps its working directory to a path relative to the connection root, and sends that relative directory plus declared environment values to the provider. Docker receives `docker exec --workdir` and `--env`; SSH uses a safely quoted remote POSIX command; WSL uses `wsl.exe --cd` and `env`. A `direct` task remains direct argv. A `login` task runs `sh -lc` in the remote environment because Shed cannot infer that environment's preferred login shell.

Generic task diagnostics still resolve against the local mirror, so remote tools should emit workspace-relative paths for quickfix entries. A remote task may outlive a locally cancelled job if its transport cannot stop the remote process; Shed does not claim remote process-tree cancellation.

## Legacy files

Existing simple `name=command` `.shedtasks` files continue to load as login-shell tasks with generic quickfix parsing and `on_failure` presentation. Saving or adding a task rewrites that file to the canonical schema.
