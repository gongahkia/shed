# Problems Gap

## Sources checked

- VS Code Code Navigation documentation: errors and warnings can come from tasks, language services, or linters; the Problems panel lists current errors and warnings.
- Microsoft LSP 3.17 specification: diagnostics are delivered through `textDocument/publishDiagnostics`, already modeled in `ItsyLSP`.

## Current implementation slice

- Added a generic workspace problem model and parser for compiler-style `path:line:column: severity: message` task output.
- Added a Problems panel with counts, list rows, and double-click open.
- Wired task output into the Problems panel.

## Not done yet

- No inline diagnostics or gutter marks.
- No LSP diagnostics bridge into the problem store.
- No next/previous problem navigation.
- No problem matcher configuration.

## Next slice

Bridge `ItsyLSP` publish-diagnostics events into the shared problem model, then add gutter/inline rendering.
