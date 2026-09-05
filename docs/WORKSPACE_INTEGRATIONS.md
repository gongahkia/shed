# Workspace Integrations

Database clients, deployment targets, collaboration systems, and container providers have incompatible credential, protocol, and lifecycle requirements. Shed provides a typed Java-extension integration point instead of embedding a single unsafe or vendor-specific implementation.

An extension registers `WorkspaceToolContribution` with one of these kinds:

- `DATABASE`
- `DEPLOYMENT`
- `COLLABORATION`
- `CONTAINER`

It declares whether it supports the active workspace and supplies a fixed list of `WorkspaceToolAction` values. Shed exposes only those actions:

```text
:integration
:integration example.database help
:integration example.database query "select 1"
```

The controller does not background-scan workspaces, connect to a service, store credentials, or turn actions into shell commands. The extension receives the current file's deepest configured workspace folder (or the selected folder for a scratch/outside file) and explicitly selected action/arguments; it owns its driver, authentication, network policy, cancellation, UI, and output format. A `ToolViewContribution` can add the matching docked Swing workbench view.

Shed has two narrow built-in local CLI bridges outside this extension boundary: `:database` invokes user-managed PostgreSQL `psql`, workspace-local SQLite `sqlite3`, and MySQL `mysql` clients, and `:compose` invokes the installed Docker Compose CLI for a workspace-local Compose file. The MySQL bridge relies on the client's normal option/login configuration and does not inspect it. These bridges do not add database drivers, credential storage, deployment vendors, live-share, CRDT, or collaboration protocols. All other database, deployment, and collaboration integrations remain extension-provided. This is deliberately not a claim of VS Code Marketplace parity.
