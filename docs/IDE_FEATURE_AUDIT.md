# IDE Feature Audit

This is a product-scope map, not a promise to reproduce other editors. Comparators are current Zed, VS Code, and JetBrains IDE documentation. Shed keeps its local-first, explicit-action model: no background network, extension marketplace, remote runtime, or project-wide index is introduced solely for feature parity.

| Product area | Shed now | Difference from larger IDEs | Decision |
| :--- | :--- | :--- | :--- |
| Editing, panes, modal input, snippets | Built in | Narrower UI surface; Vim is first-class instead of optional | Keep |
| Completion, hover, signature help, diagnostics | Capability-gated LSP plus local words/snippets | VS Code/Zed bundle more managed language support; JetBrains has language-aware engines | Keep local LSP model |
| Navigation | Definition/type-definition peek, references, symbols, breadcrumbs, jump/change lists, lazy call/type hierarchy dialogs | No persistent peek stack or fully indexed hierarchy | Keep on-demand LSP requests |
| Refactoring and actions | Previewed LSP rename/code actions; safe workspace edits | Fewer IDE-native refactorings and no action lightbulb | Keep preview-first; defer native refactor catalog |
| Formatting/linting | LSP/direct stdin-stdout formatter policy, opt-in non-blocking save formatting, diagnostics | No formatter marketplace or project-default inference | Keep explicit per-extension policy |
| Syntax/language support | Incremental lexical/grammar highlighting, semantic tokens, inlay hints | No Tree-sitter language/extension ecosystem | Defer: high ongoing grammar cost |
| Project/workspace/search | Multi-root local workspaces, tree, file finder, Git-ignore-aware search/index | No remote/multi-machine workspace abstraction | Keep local scope |
| Source control | Local Git changes, hunks, graph/history, worktrees/stashes, explicit GitHub CLI review | VS Code/Zed have broader hosted-provider and collaboration flows; JetBrains has deeper VCS UI | Keep local Git/explicit GitHub boundary |
| Terminal, tasks, REPL | PTY terminal, direct async tasks, quickfix | No language REPL protocol or terminal multiplexing | Keep; defer protocol-specific REPLs |
| Testing/debugging | Explicit-refresh cross-language Test Explorer, coverage import/gutter, capability-gated DAP, explicit test-debug mappings | No framework auto-debugging or coverage generation | Keep explicit adapters/reports |
| Extensions | Local declarative/Lua plugins and explicit package install | No general marketplace or UI/model extension API | Keep narrow local plugin boundary |
| Collaboration | Not implemented | Zed supports channels/calls and shared sessions; VS Code/JetBrains offer remote collaboration paths | Defer: conflicts with simple local state/trust model |
| Remote/dev containers | Not implemented | VS Code and Zed support SSH/containers; JetBrains supports remote development | Defer: runtime/process trust and support cost |
| AI/agents | Not implemented | Zed and VS Code foreground agent workflows; JetBrains provides AI integrations | Defer: privacy, provider, and permission model needed first |
| Notebooks/integrated browser | Not implemented | Present in selected larger-IDE workflows | Defer: weak fit for Shed's Swing/local-editor core |
| Reliability, performance, accessibility | Recovery journal, atomic writes, backups, large-file mode, bounded histories, local perf diagnostics, accessible tool panels | Less language-specific analysis; stronger explicit local boundaries | Keep and extend only behind measured limits |

## Selected increment

Navigation remains on-demand: `:lsp peek definition|type` opens a temporary read-only split, while `:lsp calls incoming|outgoing` and `:lsp typehierarchy supertypes|subtypes` prepare one current-caret item and load descendants only when expanded. Formatter selection defaults to LSP and can opt into a workspace-cwd direct external stdin/stdout process; format-on-save rejects stale or failed results before writing. Test debugging only flows through an explicit `.shedtests` adapter mapping to a global DAP configuration.

## Next candidates

1. Peek-history and call/type hierarchy result persistence without retaining a project index.
2. Per-language formatter diagnostics and formatter availability checks without probing executables automatically.
3. Explicit test-debug target templates for adapters that need more than `program`, `cwd`, and `args`.

Remote development, collaboration, AI, and a marketplace require separate trust and lifecycle designs before implementation.
