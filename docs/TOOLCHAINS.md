# Local Toolchains

Shed can use one explicitly selected existing Python, Node, Go, Java, C, and C++ executable per local workspace. This is a process-environment selection feature, not a runtime installer, version manager, or remote host.

Start by inspecting candidates:

```text
:toolchain detect
```

Detection lists conventional Python environments at `.venv`, `venv`, and `env`, plus the first `python`/`python3`, `node`, `go`, `java`, `gcc`/`clang`, and `g++`/`clang++` executable on Shed's inherited `PATH`. Detection never chooses or runs a candidate. Select an absolute executable only after reviewing it:

```text
:toolchain select python /absolute/project/.venv/bin/python
:toolchain select node /absolute/runtime/bin/node
:toolchain select go /absolute/go/bin/go
:toolchain select java /absolute/jdk/bin/java
:toolchain select c /absolute/llvm/bin/clang
:toolchain select cpp /absolute/llvm/bin/clang++
```

Use `:toolchain status` to inspect the current workspace selection and `:toolchain clear <python|node|go|java|c|cpp>` to remove one selection. `c++`, `cplusplus`, and `cxx` are accepted aliases for `cpp`. Selections are stored privately under the configured session directory, keyed to the absolute workspace path. Shed validates each stored executable when it reads the selection; an unavailable executable is reported rather than used.

For new local processes, Shed prepends selected executable directories to `PATH`. A selected Python virtual environment also contributes `VIRTUAL_ENV` only when its parent contains `pyvenv.cfg`; a selected conventional Java `bin/java` contributes `JAVA_HOME`. This applies to local LSP servers, terminals, tasks, Test Explorer runs, and local DAP adapter processes. Restart an already-running LSP client after changing its selection. An explicit `.shedtests` command and a task's explicit environment still take precedence; only automatically inferred pytest and unittest commands are rewritten to the selected Python executable.

Remote workspaces and Dev Containers keep their own environment and do not consume these local selections. Shed does not install Python, Node, Go, debug adapters, package managers, or language servers; it does not interpret `nvm`, `asdf`, `pyenv`, or similar version-manager configuration.
