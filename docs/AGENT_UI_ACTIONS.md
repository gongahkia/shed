# Agent interactions with the real Shed UI

The first shared UI-action flow is Find. An authorized external client opens the actual Find bar, types its query, toggles the actual search options, navigates matches and closes the bar. Menus, keyboard shortcuts and the agent path use the existing Find handlers and controls. This is an explicit allowlist, not arbitrary keyboard injection or desktop automation.

The window-owned agent pointer has a rounded arrow, contrasting outline, provider logo and name. It glides between controls, shows a click pulse, and morphs into an insertion cursor while entering text. Its last location stays visible between requests in the attachment session. There is no idle animation timer. Stopping/revoking the attachment removes it. It does not move the system mouse or intercept clicks. Provider PNGs come from the existing bundled icon catalog; an unknown provider keeps its name without an invented logo.

The existing `shed.caretAnimation` preference controls reduced motion: direct positioning and insertion replace travel and morph frames. Short ASCII queries type rapidly; longer queries use bounded batches. Complex-script queries install as an intact chunk, avoiding repeated shaping of incomplete strings. This does not resolve the existing first-use Unicode layout or font fallback limitations.

## Try it

```sh
make demo-ui
```

This opens a disposable project in a real Shed window. Fixed, separate Java CLI processes demonstrate Find queries, buttons, navigation and the pointer in light/dark themes. It uses no model, native shell, personal repository or user preferences. Clicking or typing in that window during an action cancels the action; the tour does not silently retry a cancelled command.

For an already explicitly authorized editor-control attachment, use the existing CLI:

```sh
./scripts/shed ctl ui-actions
./scripts/shed ctl ui-state
./scripts/shed ctl ui-action find.open --ui-revision <observed-revision> --agent Codex
./scripts/shed ctl ui-state
printf %s 'your query' | ./scripts/shed ctl ui-text --ui-revision <observed-revision> --text-file - --agent Codex
```

Set `SHED_CONTROL` to that attachment's descriptor, or supply the existing `--connect` option. Credentials continue to use the existing protected credential file, not command-line secrets. An authorized source document must be active. The revision comes from `result.observation.revision`; responses also return the current document/pane context. Do not reuse the revision after a query, selection, visibility or option change. A client may chain the revisions returned by successful operations.

## Version 1 contract

The existing authenticated workspace transport gains three methods. All retain the existing complete context fields (`schemaVersion`, instance/window/project/pane/document identities, source and interaction revisions, caret and selection). Existing delegation approval, project authorization, disposal checks and the single-active-control-operation limit apply. Metadata/navigation-only and status-only grants do not acquire these methods.

| Method | Additional parameters | Observation |
| --- | --- | --- |
| `editor/ui/actions` | none | `kind: "ui-actions"`, schema version 1, ordered allowlist with `id`, `kind`, `enabled` |
| `editor/ui/state` | none | bounded current Find state |
| `editor/ui/invoke` | `action`, `expectedUiRevision`, `agent`, `text` | resulting Find state after animation/search settles |

Allowlisted actions: `find.open`, `find.query`, `find.next`, `find.previous`, `find.case`, `find.regex`, `find.word`, `find.close`. The three options toggle the real checkbox. Only `find.query` accepts nonempty `text`. The CLI exposes it as `ui-text`; other actions use `ui-action <id>`.

State has exactly these fields:

```json
{"kind":"ui","schemaVersion":1,"revision":12,"visible":true,"mode":"find","query":"needle","caret":6,"start":6,"end":6,"case":true,"regex":false,"word":false,"phase":"ready","matches":3,"selected":0,"truncated":false}
```

The query/configuration revision is monotonic within the attachment's Find surface. Background scan phase/result changes do not invalidate it. Query caret/selection coordinates are UTF-16 offsets in the Find field, not source-document coordinates. A query is bounded to 512 UTF-16 units, must be well-formed UTF-16 and contain no control characters. Agent labels are 1–40 nonblank, control-free units. State observation rejects an oversized human query rather than exposing unbounded text. A source caret movement still uses the existing source/navigation checks.

`phase` is `idle`, `searching`, `ready` or `error`. Invalid regex is an observable search error, not a reason to rewrite the query. Match counts are bounded by the existing 200,000-match scan cap; `truncated` reports the cap. No replacement, save, terminal, process-execution or arbitrary component lookup API is added. No source, Undo, dirty-state or save mutation is part of this flow.

Unknown actions/extra fields are rejected. Existing error codes include `INVALID_REQUEST`, `CONTEXT_CHANGED`, `BOUNDARY_UNAVAILABLE`, `BUSY`, `CANCELLED`, `AUTHORIZATION_REVOKED`, `LIMIT_EXCEEDED` and `TIMEOUT`. Clients must inspect errors rather than assuming UI actions are transactional: cancellation can leave a partially entered Find query or an already opened bar. There is no rollback over human input and no automatic retry.

## Ownership and performance

Transport waits stay off Swing's EDT. One action uses a 16 ms Swing timer while active, a bounded completion future and one temporary application event listener scoped to the owned window. No new worker pool, native library or persistent polling is added. Requests validate authorization and document/pane/project ownership on every tick. Source changes, UI revision changes, disposal, cancellation, or an actual user key press/click/wheel stop subsequent steps. Existing asynchronous Find scanning remains in `AsyncJobService`.

The action budget is three seconds, with a four-second off-EDT completion deadline. Typical movement/click feedback is 300 ms; query entry is capped to roughly 650 ms, followed by a brief return to the arrow. Reduced-motion requests skip those travel/morph delays. Intentional visual duration and new-JVM CLI startup must not be described as command-dispatch or EDT occupancy. Cold Swing complex-text shaping can still exceed latency goals; no hard real-time guarantee is made.

The idle pointer releases the target component reference. Its one window overlay persists only for the attachment's lifetime, with no animation timer or input listener between actions. Grant revocation/application shutdown removes it. The OS pointer, external windows and unrelated input are not controlled.

## Follow-up boundary

Find is the implemented interaction flow. The reusable surface/presentation adapter can support other explicitly reviewed controls later. This tranche does not expose every menu command, arbitrary shortcut execution, workspace-wide Replace, saves, VCS writes, builds, diagnostics actions or terminal execution through UI actions. Existing independently authorized terminal and source-edit APIs remain separate. Button flows beyond Find still need explicit permission and context contracts.

Verification and retained raw evidence are indexed in [the evidence index](performance/agent-ui-actions/INDEX.md).
