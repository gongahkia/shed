# Portable Workspace Manifests

Shed can exchange a folder-only multi-root workspace through an explicit manifest:

```text
:workspace export /absolute/path/team.shed-workspace
:workspace import /absolute/path/team.shed-workspace
```

The import command also accepts a standard VS Code `.code-workspace` JSONC file; comments and trailing commas are accepted. Every manifest reads `folders`, where each entry is either a path string or an object with a `path` string. Shed settings, extensions, recommendations, and arbitrary extension data are always ignored. Import itself never runs a command or starts a debugger. [VS Code workspaces](https://code.visualstudio.com/docs/editing/workspaces/workspaces)

For an explicitly imported standard `.code-workspace` only, Shed also keeps its `tasks` and `launch` objects as a read-only, session-only compatibility source. While the manifest still declares exactly the same resolved folder set, `:task` and `:debug` re-read their existing strict subsets from that source: direct `process` tasks, POSIX `shell` tasks, only explicit sequential unambiguous `dependsOn` labels, and launches that use an adapter already configured in Shed. `${workspaceFolder}` resolves to the deepest configured folder containing the active file (or the selected folder outside one). This is intentionally narrower than VS Code workspace-variable and multi-root behavior. `.shed-workspace` files remain folders-only even if they contain similarly named fields.

The source path can be retained in a saved Shed session, but session restore revalidates the manifest and exact folder membership before exposing its task or launch values. Editing the manifest’s folder list requires another explicit `:workspace import`; no task or debugger starts as a result of that change. See [Workspace Tasks](TASKS.md) and [DAP Architecture](DAP.md) for the accepted fields and rejection rules.

Exports use JSON with `folders` objects and relative paths where possible. The target must end in `.shed-workspace` or `.code-workspace`; its parent must already exist. An import requires one to one hundred existing local directories, resolves their symbolic links, removes duplicate resolved roots, and replaces the current folder set only after the complete manifest has been validated.

This is distinct from `:workspace save` profiles. Profiles are private Shed session snapshots under the configured session directory and may include buffers and layout. A portable manifest carries folder membership only; the constrained `.code-workspace` bridge above is a read-only interoperability input, not a replacement for VS Code workspace settings, task/debug semantics, extension recommendations, virtual filesystems, or remote extension hosts.
