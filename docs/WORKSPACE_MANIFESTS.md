# Portable Workspace Manifests

Shed can exchange a folder-only multi-root workspace through an explicit manifest:

```text
:workspace export /absolute/path/team.shed-workspace
:workspace import /absolute/path/team.shed-workspace
```

The import command also accepts a standard VS Code `.code-workspace` JSONC file; comments and trailing commas are accepted. Shed reads only its `folders` entries, where each entry is either a path string or an object with a `path` string. Other fields, including `settings`, `tasks`, `launch`, `extensions`, and arbitrary extension data, are ignored. Shed does not run commands or apply configuration while importing a manifest. VS Code documents workspace-level settings, tasks, and launch data inside the file; Shed deliberately does not apply them. [VS Code workspaces](https://code.visualstudio.com/docs/editing/workspaces/workspaces)

Exports use JSON with `folders` objects and relative paths where possible. The target must end in `.shed-workspace` or `.code-workspace`; its parent must already exist. An import requires one to one hundred existing local directories, resolves their symbolic links, removes duplicate resolved roots, and replaces the current folder set only after the complete manifest has been validated.

This is distinct from `:workspace save` profiles. Profiles are private Shed session snapshots under the configured session directory and may include buffers and layout. A portable manifest carries folder membership only, which makes it suitable for source control or sharing with a team. It does not provide VS Code workspace-setting, task, debug, extension-recommendation, virtual-filesystem, or remote-extension-host compatibility.
