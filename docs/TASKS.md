# Workspace Tasks

Shed reads its canonical workspace developer commands from `<workspace>/.shedtasks`. Tasks are for build, test, run, lint, and debug commands. They do nothing until `:task run <name>` is entered. In a multi-root session, `<workspace>` is the deepest configured folder containing the active file; for a scratch or outside file it is the Explorer-selected folder.

A global, or explicitly trusted project, DAP configuration may name one of these tasks with `debug.configuration.<name>.prelaunch_task`. In that case an explicit `:debug start` runs the resolved local task before opening an adapter. A failed, cancelled, or timed-out task prevents the debug process from starting. This does not make tasks automatic at editor startup or file open.

## Canonical TOML

```toml
schema_version = 1

[task.check]
command = "./gradlew test ${relativeFile}"
cwd = "tools"
shell = "login"
problem_matcher = "generic"
presentation = "on_failure"
depends_on = ["compile"]

[task.check.env]
CI = "true"
```

`command` is required. `cwd` defaults to `${workspaceFolder}` and must resolve to an existing directory within the workspace. Environment names match `[A-Za-z_][A-Za-z0-9_]*`; values are strings. Unknown fields, invalid types, an unsupported schema version, or unsafe cwd prevent every task in that file from running and are shown by `:task list`.

| Field | Default | Values |
| :--- | :--- | :--- |
| `shell` | `login` | `login` uses the configured login shell; `shell` uses it without login startup files; `direct` parses command arguments and starts no shell. Both shell modes obey `shell.command.enabled`. |
| `problem_matcher` | `generic` | `generic` accepts `path:line[:column]: message`; `typescript`/`tsc` accepts regular `tsc` output; `eslint` accepts compact and stylish output; `mscompile`/`msvc` accepts parenthesized C#/VB/MSVC locations; `none` leaves quickfix unchanged. Relative paths resolve from task `cwd`. |
| `presentation` | `on_failure` | `always`, `on_failure`, or `never`; controls task-output scratch buffers, not the final status or quickfix update |
| `depends_on` | none | one to 100 distinct task names from the same `.shedtasks` file; they are validated, planned, and run once each in declared dependency-first order |

Before a task process starts, Shed resolves its complete dependency graph. An unknown task, duplicate dependency, cycle, unsafe command, or invalid cwd stops the request before any stage starts. Each stage receives the same selected local, remote, or connected Dev Container target. `:task dry-run` shows the complete order, and a DAP `prelaunch_task` runs the same prerequisites before its adapter is opened. Dependency stages run sequentially; Shed does not create a parallel scheduler, background/watch lifecycle, or dependency failure policy beyond stopping at the first non-zero result.

Supported variables in `command`, `cwd`, and environment values are `${workspaceFolder}`, `${workspaceFolderBasename}`, `${file}`, `${fileWorkspaceFolder}`, `${relativeFile}`, `${relativeFileDirname}`, `${fileBasename}`, `${fileBasenameNoExtension}`, `${fileExtname}`, `${fileDirname}`, and `${fileDirnameBasename}`. File variables require a file-backed active buffer and, where relevant, must remain inside the workspace. `${relativeFileDirname}` is `.` when the active file is at the workspace root. Quote `${file}` in a shell command when its path can contain spaces.

## VS Code task compatibility

Shed reads the same bounded JSONC subset either from a regular, non-symlink `.vscode/tasks.json`, or from the `tasks` object of an explicitly imported standard `.code-workspace` whose folder set still matches the active workspace. `:task list` includes compatible entries and `:task vscode` reports both sources. Imported names use a `vscode-` prefix, such as `vscode-check-source`, and are available only for the current process. `:task run <name>` and `:task dry-run <name>` remain explicit. Shed never changes either VS Code file, creates a `.shedtasks` file, or starts a task at workspace open/import. An accepted task can be used as a debug pre-launch task only when an accepted compatible launch profile names its exact label and the user explicitly starts that profile; labels duplicated across sources do not resolve as pre-launch work.

The accepted subset requires `"version": "2.0.0"` and an explicit `"type": "process"` or, on a POSIX host, `"type": "shell"`. It accepts a string `label` and `command`, up to 256 string `args`, `options.cwd`, string `options.env`, `presentation.reveal` of `always` or `never`, and an absent/empty `problemMatcher` or exactly one `$tsc`, `$eslint-compact`, `$eslint-stylish`, `$msCompile`, `$go`, or `$gcc` built-in matcher (as a string or one-element array). `$go` and `$gcc` use the generic location form. The other accepted names preserve their documented TypeScript, ESLint, or parenthesized Microsoft compiler location forms and carry error/warning severity into Shed's Problems view. It additionally accepts `dependsOn` as one string or an array of one to 100 distinct labels only when `dependsOrder` is exactly `"sequence"`. Each label must resolve to exactly one accepted task in the same imported source; a missing or ambiguous label rejects the dependent task. The same bounded workspace/file placeholders above are supported. A process command and each argument remain separate argv values through local, remote, and Dev Container routing. For a shell task with arguments, Shed expands each bounded value first, then POSIX-strong-quotes each value into a non-login shell command; spaces, apostrophes, and shell metacharacters therefore remain data. A shell task with no `args` preserves its single `command` as raw shell syntax, matching VS Code's single-command behavior. Shell tasks with `options.shell`, Windows shell semantics, argument quoting objects, or anything outside this subset are rejected rather than guessed.

Shed rejects extension/provider task types, `dependsOn` object forms and default/parallel dependency execution, groups, automatic `runOn` behavior, custom/modified/multiple problem matchers, task inputs, shell options, OS-specific overrides, and every unlisted field. `$tsc-watch` is rejected because Shed has no background-task lifecycle. Those constructs have execution, lifecycle, platform, or output semantics that this compatibility reader does not reproduce. VS Code supports task providers, parallel dependency graphs, auto-detected tasks, background tasks, and automatic-task policy; this is not `tasks.json` parity. [VS Code tasks](https://code.visualstudio.com/docs/debugtest/tasks), [tasks schema](https://code.visualstudio.com/docs/reference/tasks-appendix).

## Commands

| Command | Action |
| :--- | :--- |
| `:task`, `:task ui` | Open the docked Tasks/Jobs panel |
| `:task text`, `:task text list` | Show validated tasks in the legacy scratch buffer |
| `:task vscode` | Show the runtime-only `.vscode/tasks.json` and imported `.code-workspace` task compatibility report |
| `:task add <name> <command>` | Add a default login-shell task and write canonical TOML |
| `:task remove <name>` | Remove a task while preserving other task settings |
| `:task dry-run <name>` | Resolve every dependency, variable, policy, cwd, and environment key without starting a process |
| `:task run <name>` | Explicitly start a dependency-first task sequence; uses an active remote execution session or connected Dev Container only when the task root is inside one |
| `:task remote <connection-id> <name>` | Explicitly run a task through a connected remote workspace that contains this task's project root |
| `:task remote-dry-run <connection-id> <name>` | Resolve and show the remote command request without starting it |
| `:task container <name>` | Explicitly run a task through the project Dev Container after it has been started |
| `:task container-dry-run <name>` | Resolve and show the Dev Container task request without starting it |
| `:task cmake [dry-run] <configure\|build\|test\|package\|workflow> <preset>` | Explicitly run one CMake configure, build, test, package, or workflow preset as direct argv |
| `:task cancel <job-id>` | Cancel a running task; `:jobcancel <job-id>` also works |

`:jobs` reports the asynchronous task state. Cancellation destroys the running process, produces a cancelled task result, and does not parse or present partial output as a completed run.

## Conventional test and build fallbacks

When no saved or imported task has the requested name, `:task run test` and `:task run build` can use one conventional command from the workspace root: Maven (`pom.xml`), the checked-in Gradle Wrapper (`gradlew`, or `gradlew.bat` on Windows), npm (`package.json`), Make (`Makefile`), Cargo (`Cargo.toml`), Go (`go.mod`), one top-level .NET target (`.sln`, `.slnx`, `.csproj`, `.fsproj`, or `.vbproj`), or a sole conventional generated CMake tree. The .NET fallback runs direct `dotnet test` or `dotnet build` with the workspace as its current directory, so it does not choose a project when more than one target exists. The CMake fallback requires the existing tree markers described in [Testing](TESTS.md); it only runs `ctest --test-dir` or `cmake --build` and never configures a project or selects a preset.

For a project with `CMakePresets.json` or local `CMakeUserPresets.json` at its workspace root, `:task cmake configure <preset>`, `:task cmake build <preset>`, `:task cmake test <preset>`, `:task cmake package <preset>`, and `:task cmake workflow <preset>` run `cmake --preset`, `cmake --build --preset`, `ctest --preset`, `cpack --preset`, or `cmake --workflow --preset` directly. CPack package and workflow presets require CMake 3.25+ upstream. Add `dry-run` after `cmake` to inspect the exact direct argv without starting it. CMake, not Shed, resolves preset files, includes, inheritance, conditions, macros, package generators, workflow steps, and the local user-preset file. Shed does not list, parse, select, write, or persist presets, or infer a preset for ordinary `:task run`; Test Explorer can use one only after its own explicit session selection. [CMake Presets](https://cmake.org/cmake/help/latest/manual/cmake-presets.7.html)

The fallback order is fixed, an explicit task always wins, and Gradle never falls back to a globally installed executable. Define a named `.shedtasks` entry whenever a project needs target, profile, environment, or tool-selection choices.

## Explicit remote tasks and execution sessions

Remote tasks are never selected automatically. First open a remote workspace, then choose it by id:

```text
:remote open ssh://developer@host/absolute/project
:task remote <connection-id> check
```

Shed resolves the normal `.shedtasks` plan from the local mirror, maps its working directory to a path relative to the connection root, and sends that relative directory plus declared environment values to the provider. Docker receives `docker exec --workdir` and `--env`; SSH uses a safely quoted remote POSIX command; WSL uses `wsl.exe --cd` and `env`. A `direct` task remains direct argv. A `login` task runs `sh -lc` and a `shell` task runs `sh -c` in the remote environment because Shed cannot infer that environment's preferred shell.

To make only ordinary tasks for this connected workspace use the same remote provider, opt in separately:

```text
:remote use <connection-id>
:task run check
:remote unuse <connection-id>
```

`:remote use` is session-only and applies only to eligible connections that declare a distinct remote execution root. It does not pull/push, start a server, or change a task outside that local mirror. `:task dry-run` displays the remote request and starts nothing. An active remote execution session takes precedence over an overlapping connected Dev Container by execution-routing order.

Supported task diagnostics resolve against the local mirror, so remote tools should emit workspace-relative paths for quickfix entries. A remote task may outlive a locally cancelled job if its transport cannot stop the remote process; Shed does not claim remote process-tree cancellation.

## Explicit Dev Container tasks

For a project with `.devcontainer/devcontainer.json`, either run a single task through the container explicitly, or connect the workspace for the current application session:

```text
:container up
:task container check

# or: starts/verifies the container, then routes ordinary tasks for this workspace
:container connect
:task run check
```

`:container connect` is an explicit, cancellable `devcontainer up` followed by `devcontainer exec pwd`. On success, Shed retains only the validated host-workspace/container-workspace mapping in memory for that application process. New `:task run` invocations rooted in that workspace use the same container mapping without another root probe. `:container disconnect` removes that routing but does not stop, delete, or rebuild the container; restarting Shed also clears it. Declared task environment values become repeated `devcontainer exec --remote-env name=value` arguments. Shed does not start a container merely because a task exists or a workspace is opened.

The Dev Container CLI executes its command at the configured remote workspace root and has no working-directory argument. A `direct` task therefore remains direct argv only when its task cwd is that root. A `login` or `shell` task with a subdirectory runs an explicit, safely quoted `/bin/sh` wrapper to change directory before its inner shell. This is a compatibility boundary, not a general remote task scheduler.

## Legacy files

Existing simple `name=command` `.shedtasks` files continue to load as login-shell tasks with generic quickfix parsing and `on_failure` presentation. Saving or adding a task rewrites that file to the canonical schema.
