# Ongoing CLI chat inside Shed

Shed's normal agent surface is the Chat page in the right-hand Git/Chat sidebar for a conversation with an already-installed local coding CLI. The CLI owns its native conversation and tools; Shed supplies the chat UI and the existing authenticated visualization attachment. There is no new model service, daemon, API key store or document model.

## Use it

1. Open the intended project and choose **View → Agent Chat…**, or run `:agent chat`. `:agent delegate` opens the same surface.
2. Choose **Codex**, **Claude Code** or **OpenCode**. Shed uses the CLI's configured model, login and permissions. The provider picker sits in the composer. If needed, open the settings icon at the top to set its executable (not a shell command).
3. Type a message. **Enter** sends; **Shift+Enter** inserts a newline. Approve the existing control dialog before the first process starts. Save unsaved buffers and finish pending saves/reloads first.
4. Read the reply and send follow-ups in the same conversation. **Stop** or **Take Back Control** interrupts a turn. Drafting the next message remains possible while the current turn runs.
5. **Changes** opens the existing session summary and diffs after a turn; **Back to Chat** returns to the conversation. The **New chat** icon cancels any active turn and clears the visible conversation; the CLI's own stored session history is not deleted.

The editor stays visible and source input pauses only while a turn is running. It returns between replies; bridge commands are suspended during that interval. The approved lease lasts up to eight minutes. Expiration/revocation ends control, not the visible conversation: a later message needs a new approval and resumes the known native conversation ID. Changing projects requires New chat. A dirty buffer prevents the next turn until it is saved or otherwise resolved.

Run the controlled demonstration with `make demo-chat` or `make demo-delegation`. Both show two conversation turns, actual source reading/editing and cancellation with a fixed Java CLI double. This is a live application test, not a real cloud-model run. The general animation tour remains `make demo`.

The right sidebar and bottom tools are independent: opening Search/Problems/Tasks below does not move Chat. View → Agent Chat… toggles its sidebar page; hiding tools retains the conversation. During an approved active turn, only the Chat input surface and Take Back strip are exempt from the existing input shield; take back control before interacting with other tools. See [tool docking](INDEPENDENT_TOOL_DOCKS.md).

## Conversation presentation

The compact composer groups the provider picker with Send/Stop. Changes, New chat and CLI settings stay in the header; executable configuration is hidden until requested. Completed replies use selectable native styled text for paragraphs, emphasis, lists, inline code and fenced code blocks. Code uses the configured editor font; body text uses the configured UI font. No model HTML executes and images are not fetched. Streaming appends only new text; completion formats the current reply without rebuilding the entire conversation. Scrolling follows only near the end and without a transcript selection.

## Add selected source to a message

Select text in an editor, right-click, and choose **Add selection to chat**. Shed opens Chat and adds a removable context chip; it does not send the message or change source, caret/selection, revision, dirty state or Undo history. Type your instruction, then Send. The attachment carries the project-relative filename, observed document revision, end-exclusive UTF-16 range and exact selected text. It is a snapshot, so the agent is instructed to re-read before editing.

Up to four selections totaling 8,192 UTF-16 units may be attached to one message. The existing 32 KiB UTF-8 prompt cap is checked before approval/process launch. Another project's selection is rejected. Pending approval cancellation keeps the draft and attachments; successful submission consumes only that message's context. New chat clears it. Context isn't silently included again in later follow-ups.

Editor close icons now live inside each file tab. Closing an inactive tab restores the previously active document if it remains available; existing unsaved-change confirmation still applies. Claude's logo is orange in the shared provider renderer, including editor/terminal activity markers, with the bundled silhouette and transparency retained.

## Automatic visualization

Every turn receives the human's message plus generic instructions to use Shed's current CLI/API attachment. The injected context is not printed into the transcript. It includes:

- The current Java executable and absolute CLI classpath prefix, avoiding dependence on SHED_BIN or a stale JAVA_HOME.
- `SHED_CONTROL` and `SHED_DELEGATION` variable names, without embedding credential contents in prompts or argv.
- Visible, bounded `ctl read <path>` calls that open the real source and highlight reading ranges.
- Ordinary file editing through the agent's existing tools, observed by Shed's existing watcher.
- SHA-256 freshness acknowledgement for open files after writes, and stopping on conflicts/revocation.

These instructions are refreshed each turn. No agent-specific log watcher is required. A cooperative agent's reads can be visualized; an ordinary filesystem read alone still cannot produce a reading animation. Shed does not manufacture reading events from agent prose. Existing write/delete/change animations and source integrity contracts remain unchanged.

## Native conversations and protocol limits

| CLI | First turn | Follow-up |
| --- | --- | --- |
| Codex | `exec --json --sandbox workspace-write -` | Same flags, then `resume <thread-id> -` |
| Claude Code | `-p --output-format stream-json --verbose --include-partial-messages` | Add `--resume <session-id>` |
| OpenCode | `--standalone run --format json` | Add `--session <session-id>` |

Prompts use stdin, which is closed after writing. IDs come from the CLI's structured response; Shed never resumes an arbitrary latest session or replays a visible transcript as fake conversation context. A missing ID blocks follow-ups until New chat. Changed/invalid IDs and malformed or oversized JSON fail explicitly. Provider selection and the executable are fixed once a conversation ID is known. Tool details are summarized as working status; this surface is not a complete interactive terminal or permission-question protocol.

Claude/OpenCode can supply partial text; Codex text appears when its structured agent-message events arrive. Unknown provider output is not treated as a successful reply. Errors show a failed turn, not a synthetic assistant response. Login, interactive tool approval, CLI-version changes and provider outages can still prevent a turn from completing. Sign in/configure tools through their normal CLI first. No `--yolo`, `--dangerously-skip-permissions` or OpenCode `--auto` flag is added.

The command/event forms follow the providers' [Codex noninteractive](https://developers.openai.com/codex/noninteractive), [Claude Code programmatic](https://code.claude.com/docs/en/headless) and [OpenCode CLI](https://opencode.ai/docs/cli/) interfaces. Tests exercise recorded synthetic event shapes and fixed Java processes; actual installed provider versions are not qualified by those tests.

## Ownership and resource boundaries

`AgentCliChatPanel` runs one turn on the existing `AsyncJobService`. `ManagedAgentTask` supplies bounded stdin, streamed output, deadline/cancellation and tracked child cleanup. Parsing runs off the EDT; a 60 ms Swing timer installs bounded text updates only during a turn. Transcript retention is limited to 98,304 raw message characters and 128 completed messages, replies to 65,536, JSONL lines to 131,072 and prompts to 32 KiB. The existing eight-minute process/session bounds remain.

The existing delegation controller reuses consent and one change baseline across turns. A turn's input shield exempts the chat surface and Take Back strip. Machine navigation from those surfaces is admitted only while the approved chat owns source input; all existing source/context checks remain. This addresses the reproduced live failure in which the old focus guard rejected file opening with `CONTEXT_CHANGED` while focus was in Chat. Preparation failure, Stop, expiry, closure and stale callbacks release/cancel the turn rather than retargeting it.

Managed CLIs retain the user's normal environment and CLI configuration, with the selected Shed attachment and current Java runtime supplied explicitly. This is intentional user-approved agent execution, not a filesystem sandbox. The CLI's own tool policies still apply. Revocation cannot reverse completed disk writes or confine detached processes; these limits are unchanged from [delegation](AGENT_DELEGATION.md).

The normal surface has no Chat/Review/Live/Advanced tab strip and no duplicate AI Assistant menu entry. Legacy ACP tools open only through explicit commands such as `:agent edit`, `:agent ask` and `:agent run`, with **Back to Chat** to return. The original one-shot launcher is explicit through `:agent task`. Existing `delegate --connect` and other external control commands retain their behavior.

## Qualification

See [chat polish and inline-completion qualification](performance/agent-chat-polish/INDEX.md), [original chat commands, counts, failures and captures](performance/agent-cli-chat/INDEX.md) and [the unified Chat refinement checks](performance/agent-chat-ui/INDEX.md). Safe component/transport tests and the controlled macOS Swing workflow cover native-ID continuation, hidden context on every turn, real file opening/highlighting, exact disk/editor source, source ownership restoration, idle bridge suspension, draft preservation, Stop and child/IO retirement.

Real cloud CLI compatibility, Windows/Linux desktops, OS keyboard-to-screen latency, complete native-memory attribution and detached-service confinement remain unqualified. B0.1's native-shell harness remains NO-GO and was not used. The Unicode/glyph and HTML-preview limitations remain open. No README, theme/font default, document ownership, saved preference migration or new dependency is part of this change.
