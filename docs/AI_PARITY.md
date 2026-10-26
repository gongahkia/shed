# Focused workflow comparison with 99

Reference inspected: ThePrimeagen/99 commit `c17422457027c913c76c75a921fca1e623d2678e` (2026-10-26). This records code coverage for Complete, Edit selection, Ask/search and Run task. It does not claim equal model-output quality or latency.

## Requirements and evidence ledger

| Capability within the four workflows | Shed work | Evidence required |
| --- | --- | --- |
| Inline code completion | Existing ghost text, Tab/Escape, cancellation and stale guards; 99's completion is prompt references rather than ghost code | Existing completion transport, keymap and display tests |
| File/rule prompt completion | Implemented: `@file`, quoted paths, `#skill`, fuzzy suggestions, explicit custom skill directories | `AgentPromptContextTest` (5 passing) plus actual panel suggestion insertion/action test; existing context and panel regression cases pass |
| Project instruction discovery | Implemented: configurable ancestor instruction filenames, root-to-nearest order, visible source list | Root/ancestor ordering, unsaved references, external skills, limits and cancellation tests; actual panel action includes listed sources |
| Selection edits and code search | Existing reviewed edits and validated navigable references | Panel/ACP/review/navigation tests |
| Reopen previous results and clear history | Implemented in History; bounded per-workspace in-memory results, reusable requests, existing review/navigation controls | History retention/isolation tests and actual panel reopen/reuse/clear test |
| Work description reused for search/task follow-ups | Implemented; one private saved description per workspace, Find remaining work and Continue work prepare editable prompts | Private persistence/reload/clear/isolation and actual panel save/prepare tests |
| Test/command feedback and iterative task fixes | Implemented; explicit command approval in Requests and Check & continue after an applied proposal | Synthetic execution/failure/timeout/output/child cleanup tests; actual panel approval/rejection/cancel; apply → failed check → fresh proposal still requiring review |
| Model selection | Implemented: advertised ACP model selector, grouped config options and legacy model support; successful selection persisted for completion/future sessions | Actual subprocess model changes, next-request model, failed selection retains previous model, panel selection/persistence tests |
| Status, cancellation and error recovery | Existing, extend to added operations | Pending-action cancellation, preserved drafts, malformed/stale failures |

Excluded from this comparison: Neovim-specific completion adapters/keybinding APIs, alternate agent providers (OpenCode-only is explicit user scope), tutorial generation as a fifth workflow, dashboards, durable worktrees and MCP. 99's Worker is relevant only as a saved description for search/run, not a new platform roadmap.

Local model cleanup: removed the experiment's `/tmp/shed-ai-acceptance-tools/models`, `ollama`, `ollama-darwin.tgz` and local-provider configurations (about 6 GB). No running Ollama process, PATH installation, standard application bundle, or `~/.ollama/models` directory was found. This task uses no model downloads or inference services. Tests stay headless and use deterministic protocol peers.

Model selection follows [ACP session config options](https://agentclientprotocol.com/protocol/v1/session-config-options) and [OpenCode's advertised model selector](https://github.com/anomalyco/opencode/blob/dev/packages/cli/src/acp/config-option.ts). Only model selection is exposed; changing agent modes cannot bypass Shed's tool/review policy.
