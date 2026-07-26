# Phase29 LSP checkpoint

Date: 2026-07-01

## Sources checked

- Microsoft LSP 3.17: `textDocument/documentSymbol` returns either `DocumentSymbol[]`, `SymbolInformation[]`, or `null`; hierarchy support is explicit client capability.
- Microsoft LSP 3.17: `workspace/symbol` returns `SymbolInformation[]`, `WorkspaceSymbol[]`, or `null`; `WorkspaceSymbol.location` may be URI-only when resolve support is in play.
- Microsoft LSP 3.17: diagnostics are pushed with `textDocument/publishDiagnostics`; empty diagnostics arrays replace prior diagnostics.

## Completed

- Added the LSP status bar surface and missing-server banner/status plumbing.
- Added `workspace/symbol` request/response models and client/session/process APIs.
- Routed command-palette `@` search through live LSP workspace symbols first, then WorkspaceIndex fallback.
- Added `textDocument/documentSymbol` request/response models and client/session/process APIs.
- Routed command-palette `#` file symbols through the active LSP document session when available, with regex fallback.
- Added symbol adapters for hierarchical `DocumentSymbol`, flat `SymbolInformation`, and ranged `WorkspaceSymbol`.
- Expanded bundled server configs for C, C++, Zig, Elixir, Kotlin, C#, Bash, Dockerfile, SQL, Dart, Haskell, Lua, Ruby, and Terraform.
- Added filename-based language detection for Dockerfile/Containerfile and Ruby project files.
- Added executable `bench/scripts/lsp_smoke.sh` for per-language diagnostics smoke QA.
- Generalized `bench/scripts/lsp_diagnostics_probe.rb` so regression keeps its sourcekit default and smoke QA can pass an arbitrary fixture/server/language.

## Verification

```sh
swift test --no-parallel --filter LSPTypesTests
swift test --no-parallel --filter LSPClientSessionTests
swift test --no-parallel --filter LSPSymbolAdapterTests
swift test --no-parallel --filter LSPServerRegistryTests
swift test --no-parallel --filter ItsyLSPTests
swift test --no-parallel --filter ItsyEditorTests
ruby -c bench/scripts/lsp_diagnostics_probe.rb
bash -n bench/scripts/lsp_smoke.sh
bench/scripts/lsp_smoke.sh
ITSY_LSP_DIAGNOSTICS_PROBE_LINES=1000 ruby bench/scripts/lsp_diagnostics_probe.rb
swift build
swift build -c release
git diff --check
```

Result:

- Focused LSP type/session/adapter/registry tests: pass.
- `ItsyLSPTests`: pass.
- `ItsyEditorTests`: pass.
- Probe and smoke script syntax: pass.
- LSP smoke local run: `ok=4 skipped=16 failed=0 limit_ms=5000`.
- Legacy sourcekit diagnostics probe with 1,000 lines: pass.
- Debug and release builds: pass.
- Whitespace check: pass.

Local smoke latencies:

| Language | Status | Latency ms | Diagnostics |
| --- | --- | ---: | ---: |
| swift | ok | 2174.6 | 1 |
| c | ok | 95.3 | 1 |
| cpp | ok | 479.1 | 2 |
| dart | ok | 413.4 | 1 |

## Residual gaps

- Workspace symbols currently skip URI-only `WorkspaceSymbol.location` values instead of issuing `workspaceSymbol/resolve`.
- Missing/uninstalled language servers are visible through registry hints and smoke skips, not auto-install.
- Smoke QA checks first diagnostics publication within the KPI for installed servers; it does not check semantic diagnostic quality for every fixture.
