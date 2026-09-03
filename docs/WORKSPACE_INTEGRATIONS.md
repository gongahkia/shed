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

The controller does not background-scan workspaces, connect to a service, store credentials, or turn actions into shell commands. The extension receives the active workspace root and explicitly selected action/arguments; it owns its driver, authentication, network policy, cancellation, UI, and output format. A `ToolViewContribution` can add the matching docked Swing workbench view.

There are no built-in database drivers, deployment vendors, live-share server, CRDT implementation, credential vault, or collaboration protocol. This is deliberately an extension boundary, not a claim of VS Code Marketplace parity.
