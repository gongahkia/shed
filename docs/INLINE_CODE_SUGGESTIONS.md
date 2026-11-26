# Optional inline code suggestions

Enable **View → Inline Code Suggestions**, or use the palette's **Inline Suggestions: Enable** action. It is off by default. Disable it through the same checkbox or **Inline Suggestions: Disable**. `:agent complete on|off` controls the same saved setting; `:agent complete` requests a suggestion now.

This reuses Shed's existing OpenCode-backed completion connection. Install and authenticate OpenCode separately and configure `agent.opencode.command`, `.args` and optionally `.model` for your installation. The Chat provider picker does not select the completion backend. Enabling this feature authorizes bounded source context to be sent to the model configured through OpenCode. Shed does not install a model or include a subscription. See [backend setup](AGENT_WORKSPACE.md#initial-backend-setup).

In Insert mode, type in the active source pane and pause. A suggestion appears as ghost text, including multiple lines, without modifying source or Undo history.

| Key | Behavior while ghost text is visible |
| --- | --- |
| Tab | Accept all remaining text through the existing editor insertion path. |
| Ctrl+Right or Command+Right | Accept leading whitespace and the next identifier word, or the next complete grapheme; keep the remaining ghost without another model request. |
| Escape | Dismiss and cancel pending work. |

Existing snippet/LSP completion and custom Vim mappings retain priority. With no suggestion the usual keys retain their behavior. Selection, caret movement, source edits, focus loss, document switching and disposal invalidate obsolete suggestions. Explicit acceptance checks document identity, revision and caret. Preview geometry uses the existing virtual display document; it does not replace authoritative source ownership. Undo/Redo uses the same buffer history as human insertion.

The default typing idle delay is **250 ms**; a saved value such as 500 ms is preserved. Supported `ai.completion.delay.ms` values are 100–5,000 ms; the existing default response timeout is 10 seconds. These settings describe scheduling and bounds, not model-response latency. Provider startup, model speed and authentication add time. The existing failure backoff is 30 seconds; explicit Complete retries immediately.

Context and replies remain bounded by the existing completion client. Previews reject source documents above 1,000,000 UTF-16 units, or 100,000 with wrapping. Large-file buffers, nonempty selections and multiple selections do not request suggestions. No additional worker pool, font cache, model service or dependency is introduced. While disabled, insertions no longer schedule deferred automatic-request callbacks or redundant source repaints.

## Try the presentation

```sh
make run .
make demo-chat
make demo-completion
```

`make demo-chat` uses a disposable project and fixed direct-Java CLI double to show the conversation, source reading/editing and cancellation. It does not invoke a cloud model.

`make demo-completion` adds an **interactive** fixed-response completion tour. Click its source editor when prompted, then watch without typing until the tour finishes. It checks automatic ghost presentation, word/full acceptance, exact buffer Undo/Redo and disabling through the actual menu. It does not force the application into the foreground. A focus timeout or concurrent input fails the fixture instead of relaxing source checks. Run it separately from other typing or workloads.

This implements fast insertion suggestions. Predictive rewrites, automatic imports and jumps to other edit locations/files are outside this pass. The scope follows familiar [Tab/Escape/word acceptance](https://cursor.com/help/ai-features/tab) interactions; it does not claim the proprietary prediction features described by [Windsurf/Devin Tab](https://docs.devin.ai/desktop/tab/overview).

## Qualification limits

See [raw evidence and results](performance/agent-chat-polish/INDEX.md). Headless component tests cover cancellation, source preservation, bounds, layout/pixel agreement, word/grapheme acceptance and key precedence. One controlled macOS fixed-response live run passed the complete workflow. Unattended native focus/input qualification remains open: forced-foreground experimentation admitted unrelated native keyboard input into a disposable document, so foreground activation was removed and the completion tour is explicitly interactive. Earlier non-instrumented live failures remain recorded without a definitive attribution.

No real provider latency/quality, Windows/Linux desktop, full OS key-to-screen latency, native-memory equivalence or complete multilingual glyph/layout qualification is claimed. Existing Unicode first-edit and HTML-preview limits remain unchanged. B0.1's unqualified native-shell harness is not used.
