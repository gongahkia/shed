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
| Overlapping requests | Implemented: up to eight independent conversation tabs with workspace/review binding, activity indicators and Stop all | Actual overlapping fixture actions across two workspaces, owner-bound proposal review, close-one cancellation isolation and Stop all tests |
| Status, cancellation and error recovery | Implemented across context, commands, review continuation and model selection | Panel tests cover pending approval cancellation, preserved drafts, provider refusal, stale review and malformed replies |

Excluded from this comparison: Neovim-specific completion adapters/keybinding APIs, alternate agent providers (OpenCode-only is explicit user scope), tutorial generation as a fifth workflow, dashboards, durable worktrees and MCP. 99's Worker is relevant only as a saved description for search/run, not a new platform roadmap.

Local model cleanup: removed the experiment's `/tmp/shed-ai-acceptance-tools/models`, `ollama`, `ollama-darwin.tgz` and local-provider configurations (about 6 GB). No running Ollama process, PATH installation, standard application bundle, or `~/.ollama/models` directory was found. This task uses no model downloads or inference services. Tests stay headless and use deterministic protocol peers.

Model selection follows [ACP session config options](https://agentclientprotocol.com/protocol/v1/session-config-options) and [OpenCode's advertised model selector](https://github.com/anomalyco/opencode/blob/dev/packages/cli/src/acp/config-option.ts). Only model selection is exposed; changing agent modes cannot bypass Shed's tool/review policy.

## Audit boundaries

The reviewed public operations in 99 are selection (`visual`), search, `vibe`, `open`, request cancellation, clearing previous results, logs, model selection, configurable instruction files and explicit file/rule references, plus the Worker's single work description. Shed implements these capabilities within its four workflows using its own UI/protocol and review controls. 99's `tutorial` is a separate workflow and remains excluded. Its unused LSP helper module and unused `auto_add_skills` option are not active workflow capabilities at the pinned revision. The quickfix parser ignores the advertised range-length field; both editors navigate file/line/column results.

Each connection serializes its own turns; independent conversations and completion use separate connections. Command steps are explicitly approved and bounded; 99 delegates more authority to the backend's build agent. Review remains explicit as required by the original scope.
