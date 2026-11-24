# Five-step agent workflow delivery

All five requested steps are implemented for their documented supported scope, using Shed's canonical source, existing review, checked transactions, Undo/recovery and renderer.

| Step | Delivered | Local commit |
|---|---|---|
| 1 | HiDPI capture correction; ASCII doubling was a 1× capture of a 2× component. Source renderer unchanged. | `60450c455` |
| 2 | Explicit-attachment `shed ctl`, automatic context construction, bounded reads and revision-pinned proposals. | `b02b4fff6` |
| 3 | Named sessions, phases/progress counts, distinct agent marker, bounded pulses and cooperative cancellation. Hidden panes avoid repaint geometry. | `2b9e9c165`, fixture correction `1105e6185` |
| 4 | Project-relative reads and reviewed batches of up to eight text/create/rename/delete operations. Existing Apply, transaction Undo and recovery. | `e78722d65` |
| 5 | Cached current-file diagnostics, aggregate job feedback, existing Markdown preview opening/state and bounded viewport PNG capture. | `34a1bb285` |

See [usage](USING_SHED_WITH_AGENTS.md) and [raw evidence, commands and captures](performance/agent-workflow-improvements/INDEX.md).

## Verification

369 safe Maven passes, six opt-in skips, zero failures/errors; 91 tooling passes; packaged media smoke passed. Real-workbench acceptance passed reviewed Apply, exact Undo/Redo, completed save, activity, feedback and preview capture. Warm typing p95 was 1.2505 ms on the small demo. Friendly CLI syntax is tested in-process; the separate Java-process client exercises the same bridge with the existing low-level CLI. Native-shell/full native GUI suites remain excluded. No Windows/Linux desktop acceptance is claimed.

Failed investigation evidence is preserved. The renderer hypothesis was rejected after a faithful HiDPI capture isolated the artifact. A cold CLI run found hidden-view font discovery; that path now avoids hidden geometry. The fixture uses the production bundled font and retains its Unicode content, original deadlines and exact-result assertions. One activity commit was made before noticing its final failed run; the following fixture/evidence commit records the correction. A preview positive control refused the default remote-image policy; agent-created previews now disable remote fetching for that preview without changing saved preferences, and the final live run passes.

## Limits

Cancellation is cooperative: it does not kill an external agent or retract durable proposals. Session-aware clients pass their session ID; humans retain Reject/Apply. Names are labels, not additional authenticated identities. No remote save, arbitrary tool execution, desktop screenshot or unrestricted capture endpoint exists. Feedback means cached diagnostics and aggregate job states, not raw process output or build invocation.

Preview observation supports sources up to 4,096 UTF-16 units, crops up to 256×160 logical units, and rejects captures beyond 262,144 pixels or 20,000 PNG bytes. Captures are not authoritative text. Existing physical-font multilingual coverage, Unicode first-edit stalls, HTML-preview EDT stalls and native-memory qualifications remain open. Project reads use existing immutable snapshots; many large open buffers are not newly qualified. The trusted-local path/authentication model is not a hostile same-user filesystem sandbox.

## Repository compliance

Starting HEAD was `f0ff7fc7102719f45f90050aafd67b07f8287f1b`, with a clean tree. Existing work/evidence was preserved. Requested local atomic commits were created; no push was made. No dependency upgrade, UI redesign, font-default change or preference migration occurred.

README.md SHA-256 before/after: `48bf87f09ffa570b7fc65d305a15c3228bbc67e906cc992e4fe7108893e7501e`. `git diff --check` passed.
