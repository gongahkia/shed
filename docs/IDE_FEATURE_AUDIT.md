# IDE Feature Audit

This is a product-scope map, not a promise to reproduce other editors. Comparators are current Zed, VS Code, and JetBrains IDE documentation. Shed keeps its local-first, explicit-action model: no background network, extension marketplace, remote runtime, or project-wide index is introduced solely for feature parity.

| Product area | Shed now | Difference from larger IDEs | Decision |
| :--- | :--- | :--- | :--- |
| Editing, panes, modal input, snippets | Built in | Narrower UI surface; Vim is first-class instead of optional | Keep |
| Completion, hover, signature help, diagnostics | Capability-gated LSP plus local words/snippets | VS Code/Zed bundle more managed language support; JetBrains has language-aware engines | Keep local LSP model |
| Navigation | Definition, type definition, references, symbols, breadcrumbs, jump/change lists | No peek/embedded definition view or call/type hierarchy | Implemented type definition; defer hierarchy/peek |
| Refactoring and actions | Previewed LSP rename/code actions; safe workspace edits | Fewer IDE-native refactorings and no action lightbulb | Keep preview-first; defer native refactor catalog |
| Formatting/linting | Explicit LSP document formatting and diagnostics | No language-scoped formatter selection or built-in format-on-save | Defer: needs non-blocking save semantics and formatter policy |
| Syntax/language support | Incremental lexical/grammar highlighting, semantic tokens, inlay hints | No Tree-sitter language/extension ecosystem | Defer: high ongoing grammar cost |
| Project/workspace/search | Multi-root local workspaces, tree, file finder, Git-ignore-aware search/index | No remote/multi-machine workspace abstraction | Keep local scope |
| Source control | Local Git changes, hunks, graph/history, worktrees/stashes, explicit GitHub CLI review | VS Code/Zed have broader hosted-provider and collaboration flows; JetBrains has deeper VCS UI | Keep local Git/explicit GitHub boundary |
| Terminal, tasks, REPL | PTY terminal, direct async tasks, quickfix | No language REPL protocol or terminal multiplexing | Keep; defer protocol-specific REPLs |
| Testing/debugging | Explicit-refresh cross-language Test Explorer and capability-gated DAP | No coverage UI, test debugging, or framework-specific runners | Keep; defer coverage/test-debug targets |
| Extensions | Local declarative/Lua plugins and explicit package install | No general marketplace or UI/model extension API | Keep narrow local plugin boundary |
| Collaboration | Not implemented | Zed supports channels/calls and shared sessions; VS Code/JetBrains offer remote collaboration paths | Defer: conflicts with simple local state/trust model |
| Remote/dev containers | Not implemented | VS Code and Zed support SSH/containers; JetBrains supports remote development | Defer: runtime/process trust and support cost |
| AI/agents | Not implemented | Zed and VS Code foreground agent workflows; JetBrains provides AI integrations | Defer: privacy, provider, and permission model needed first |
| Notebooks/integrated browser | Not implemented | Present in selected larger-IDE workflows | Defer: weak fit for Shed's Swing/local-editor core |
| Reliability, performance, accessibility | Recovery journal, atomic writes, backups, large-file mode, bounded histories, local perf diagnostics, accessible tool panels | Less language-specific analysis; stronger explicit local boundaries | Keep and extend only behind measured limits |

## Selected increment

Shed now sends `textDocument/typeDefinition` only when the current language server advertises `typeDefinitionProvider` and `lsp.type.definition.enabled` is true. `:typedefinition`, `:typedef`, `:lsp typedefinition`, `:lsp type`, and `:lsp typedef` use the same current-buffer and LSP capability checks as definition navigation. The request is on demand; it creates no index, watcher, process, or network path.

## Next candidates

1. Non-blocking, opt-in format-on-save through existing LSP formatting, with stale-document rejection.
2. A compact interactive code-action picker anchored to current diagnostics.
3. Coverage report import for the existing Tests panel.

The first two fit Shed's local model; coverage has lower cross-language consistency. Remote development, collaboration, AI, and a marketplace require separate trust and lifecycle designs before implementation.
