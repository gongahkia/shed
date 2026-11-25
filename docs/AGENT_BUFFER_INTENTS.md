# Agent intentions in the source buffer

Shed can show what an external tool is targeting in its actual source pane. This is a vendor-neutral presentation API: Codex, Claude Code, OpenCode, a custom harness or a script can send the same authenticated CLI requests. It does not parse vendor logs or require a vendor SDK, cloud model, plugin, startup hook or new background service.

Filesystem watching still supplies ordinary change/diff replay. It cannot reliably identify a search, definition jump, intended rename, check result or conflict. An agent must explicitly report these intentions. A label identifies the reporting client's chosen display name, not an authenticated model identity.

## Use

Start an explicitly authorized control attachment as described in [Using Shed with agents](USING_SHED_WITH_AGENTS.md), or use the attachment created by `shed visualize`. Keep its private connection descriptor in `SHED_CONTROL`. Use matching client/application builds and relaunch old windows after upgrading.

```sh
shed ctl read src/App.java --offset 0 --limit 2048 --agent "My agent"
shed ctl intent src/App.java --kind search --ranges 20:25,80:85 --focus 1 --agent "My agent"
shed ctl intent --kind navigate --start 80 --end 85 --agent "My agent"
shed ctl intent --kind target --start 100 --end 150 --agent "My agent"
shed ctl intent --kind move --ranges 100:150,300:300 --focus 0 --agent "My agent"
shed ctl intent --kind move --ranges 100:150,300:300 --focus 1 --agent "My agent"
shed ctl intent src/Other.java --kind move --status destination --start 50 --end 50 --agent "My agent"
shed ctl intent --kind rename --ranges 20:25,80:85,200:205 --agent "My agent"
shed ctl intent --kind format --start 0 --end 150 --agent "My agent"
shed ctl intent --kind undo --start 100 --end 150 --agent "My agent"
shed ctl intent --kind verify --status checking --start 100 --end 150 --agent "My agent"
shed ctl intent --kind verify --status passed --start 100 --end 150 --agent "My agent"
shed ctl intent --kind conflict --start 100 --end 150 --agent "My agent"
```

The example ranges are illustrative. Supply ranges from the source you actually observed. Each range is half-open `[start,end)` in **UTF-16 code units**, zero-based, document-relative, with valid grapheme boundaries. `length:length` represents EOF. Byte offsets, code-point indices, visual columns and grapheme indices are not interchangeable with these offsets. There are at most 32 ranges and 65,536 UTF-16 source units; existing wrapped/complex layout availability restrictions also apply. An `active` move requires exactly two ranges: source first, destination second. For cross-file moves, report one range per request with `--status source` or `--status destination` and the corresponding file. A destination may be an empty insertion point. `--focus` is the zero-based range to reveal and defaults to zero.

Use either `--ranges start:end,...` or both `--start` and `--end`, never both forms. For events derived from a retained read, also supply **both** `--document DOCUMENT_ID --revision REVISION` from that read. Mismatches reject the annotation. Without them, the CLI acquires the current active context immediately before dispatch; the server still validates document, source revision, pane, selection and context. This is useful for immediate presentation but does not prove that old inferred offsets still describe the client's intended text.

An optional project-relative path uses the existing checked file-opening controller. Opening and subsequent presentation are separate checked operations. A stale document/revision or human context change may reject the intent after opening; it does not undo the authorized open or silently retry. No path means the already-active source file. Project/path policy, protected files, explicit attachment, mutual authentication and existing control grant remain required. Read-only status/metadata/navigation grants cannot emit intents.

## Visual meanings

| Kind | Presentation |
| --- | --- |
| `search` | Outline/tint bounded matches; reveal the focused result and label its index/count. No implicit project search is executed. |
| `navigate` | Smoothly reveal the destination and show a distinct agent marker. The human caret is not moved. A path may open the destination buffer. |
| `target` | Outline the exact range the agent intends to edit. |
| `move` | Source is amber; destination green. Report focus 0 then focus 1 to show both ends in sequence, including separate files via separate checked requests. This does not perform the move. |
| `rename` | Highlight linked occurrences together; the focused occurrence is labelled. Repeated events can tour other files. |
| `format` | Highlight the coherent formatting/import region with the existing directional pulse. The subsequent actual source change uses normal diff replay. |
| `undo` | Label the reverting range with a reverse sweep. The agent still performs its normal revert, or the human uses existing Undo; this endpoint has no Undo command. |
| `verify` | `checking` is amber; `passed` green; `failed` red. The label explicitly says the agent **reports** a check outcome. Shed does not run a tool or replace cached diagnostics in response. |
| `conflict` | Red range and “needs attention · reported conflict” label. It preserves the human's draft. This is a notification, not a lock or permission to overwrite source. |

Other kinds reject. Status defaults to `active` except for `verify`, which defaults to `checking`; only verify accepts `checking`, `passed` or `failed`, and only move accepts `source` or `destination`. No arbitrary label text, arbitrary executable or source payload is accepted. Names are bounded to 40 non-control characters.

The human caret, selection, source, dirty state, source revisions and Undo remain authoritative. Decorations use existing theme roles, finite highlight timing and the separate agent marker. Intent navigation changes only the visible presentation; opening a different file retains existing file/navigation behavior. The existing status line provides the label when there is no free line margin. No new settings, panel, editor model, font or theme is introduced.

Intents supersede current/pending automatic change tours without disabling Follow. A later actual source change may supersede the intent and start normal diff replay. Human key/mouse/wheel/focus activity, document changes/replacement, hiding, disposal and attachment retirement clear the decoration. Animated intents expire after approximately six seconds; viewport reveal takes approximately 300 ms. Reduced motion reveals immediately with a static annotation until replacement or cleanup, following the existing presentation policy. Signals do not pause or cancel an external agent process.

## Machine contract

The existing authenticated `editor-control` attachment accepts `editor/agent/intent`, with the same schemaVersion 1 instance/window/project/pane/document/revision/context/caret/selection fields as other control operations, plus:

```json
{
  "intent": {
    "kind": "search",
    "status": "active",
    "agent": "Any harness",
    "ranges": [{"start": 20, "end": 25}, {"start": 80, "end": 85}],
    "focus": 1
  }
}
```

Successful replies use the existing context response with `operation: "editor/agent/intent"`, `outcome: "shown"` and the bounded echoed `intent`. Unknown/missing/extra fields, malformed ranges, statuses or names fail closed. Existing structured errors apply: `INVALID_REQUEST`, `INVALID_OFFSET`, `LIMIT_EXCEEDED`, `STALE_REVISION`, `CONTEXT_CHANGED`, authorization failures, `BUSY`, cancellation, timeout and unavailable boundaries. Successful “shown” means installation was accepted; it is not a promise the human will leave the view unchanged until expiry. Transport timeouts retain existing outcome-unknown semantics; do not automatically replay uncertain UI actions.

The transport waits off the EDT. Snapshot/range validation uses the existing background dispatch; installation and scrolling respect Swing ownership. There is no persistent event log or unbounded queue. Each new event replaces the prior decoration. UI scrolling updates the existing context revision, so clients should capture fresh context for subsequent operations rather than replay old request envelopes.

## Harness instruction

Give any agent the same instruction, substituting the executable/attachment selected by the user:

> Keep your normal editing workflow. Use Shed's authenticated CLI for visualization. Read through `shed ctl read <file>` when practical. Before an operation, report only ranges and intentions you actually know using `shed ctl intent`; retain read document IDs/revisions for delayed signals. Report check outcomes only after observing them. Use search/target/move/rename/format/undo/verify/conflict as appropriate. Intents display state and do not execute edits or tools. Preserve human context; on stale/busy/transport errors, leave the editor alone and reassess instead of retrying blindly. Never expose private attachment keys. Do not infer agent reads from filesystem writes or treat a screenshot as exact source text.

No Codex-specific modification is necessary. Integrations may translate their own tool events into this contract later, but unsupported or ambiguous vendor logs should not manufacture semantic events. This release does not install a universal intercept of third-party tools.

## Demo and qualification

After qualified checks, `make demo` includes the original reads/change replay/file operations/Git view plus all nine intention kinds, both move anchors, and all three check outcomes. The scripted clients use several vendor names to exercise the shared API; no actual model is called. The demo's pass/fail compiler outcomes are real in-process compiler results on disposable positive/negative source fixtures. The conflict stage creates a disposable human draft and verifies that presentation preserves it and ordinary Undo restores it.

See [qualification evidence](performance/agent-buffer-intents/INDEX.md) for actual results, first/warm timings, exclusions and screenshots. Existing Unicode/glyph, preview latency, native-shell and cross-platform qualifications remain separate. No native-shell admission safeguard is bypassed.
