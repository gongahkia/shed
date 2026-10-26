# Focused workflow comparison with 99

Reference inspected: ThePrimeagen/99 commit `c17422457027c913c76c75a921fca1e623d2678e` (2026-10-26). This records code coverage for Complete, Edit selection, Ask/search and Run task. It does not claim equal model-output quality or latency.

## Requirements and evidence ledger

| Capability within the four workflows | Shed work | Evidence required |
| --- | --- | --- |
| Inline code completion | Existing ghost text, Tab/Escape, cancellation and stale guards; 99's completion is prompt references rather than ghost code | Existing completion transport, keymap and display tests |
| File/rule prompt completion | Implemented: `@file`, quoted paths, `#skill`, fuzzy suggestions, explicit custom skill directories | `AgentPromptContextTest` (5 passing) plus actual panel suggestion insertion/action test; existing context and panel regression cases pass |
| Project instruction discovery | Implemented: configurable ancestor instruction filenames, root-to-nearest order, visible source list | Root/ancestor ordering, unsaved references, external skills, limits and cancellation tests; actual panel action includes listed sources |
| Selection edits and code search | Existing reviewed edits and validated navigable references | Panel/ACP/review/navigation tests |
| Reopen previous results and clear history | Pending | Previous answer locations and proposal restoration, workspace isolation, clear behavior |
| Work description reused for search/task follow-ups | Pending; one workspace-scoped description, no task dashboard | Persistence, workspace isolation, search/run prompt integration |
| Test/command feedback and iterative task fixes | Pending; preserve human review and explicit command approval | Synthetic background command execution, cancellation, bounded output, fresh baselines and feedback |
| Model selection | Pending inspection of ACP configuration and OpenCode model discovery | Correct configured invocation, selection and subsequent requests |
| Status, cancellation and error recovery | Existing, extend to added operations | Pending-action cancellation, preserved drafts, malformed/stale failures |

Excluded from this comparison: Neovim-specific completion adapters/keybinding APIs, alternate agent providers (OpenCode-only is explicit user scope), tutorial generation as a fifth workflow, dashboards, durable worktrees and MCP. 99's Worker is relevant only as a saved description for search/run, not a new platform roadmap.

Local model cleanup: removed the experiment's `/tmp/shed-ai-acceptance-tools/models`, `ollama`, `ollama-darwin.tgz` and local-provider configurations (about 6 GB). No running Ollama process, PATH installation, standard application bundle, or `~/.ollama/models` directory was found. This task uses no model downloads or inference services. Tests stay headless and use deterministic protocol peers.
