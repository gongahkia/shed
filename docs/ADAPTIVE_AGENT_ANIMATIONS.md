# Adaptive agent change animations

Shed replays an observed diff in the followed source pane. The authoritative document already contains the completed external change: it is never temporarily retyped, reverted, normalized or replaced to produce an animation. Other splits and source queries continue to observe the real current document.

## Presentation rules

| Observed operation | Treatment |
| --- | --- |
| Small addition/replacement, at most 160 UTF-16 units and two newlines | Additions reveal glyphs at grapheme boundaries with a green agent cursor; replacements erase removed glyphs in red before writing in green. Frames may coalesce under load; this is a visual replay, not a keystroke log. |
| Larger addition | Complete lines phase in, with an incoming-line fade and a left-to-right green sweep. |
| Larger replacement | Highlight a bounded preview of removed text, erase it right-to-left, then phase the replacement in left-to-right. Existing diff hunks determine the targets. |
| Text deletion | Scroll to the surviving source anchor, highlight an observed-removal preview, then erase its text right-to-left. The already-deleted source is not resurrected in the document. |
| Explicit agent read | Existing amber reading highlight, underline marker and top-to-bottom moving band. Requires `shed ctl read <file>` or an explicit reading progress signal. |

Small inline replacements use compact red glyph erasure before green writing. Nearby edits on separate visible lines with matching phase durations share the same replay clock and each draw an agent cursor; this applies to ordinary batch edits without semantic rename detection. Removed text previews show at most eight lines and 1,024 UTF-16 units, with at most 200 units per drawn line. The removed text starts directly at its source anchor without a caption row. Whole-file deletion retains the existing Explorer/Git deletion treatment and buffer-retention policy; it does not empty or recreate a human buffer to simulate deletion.

Small inline edits trim unchanged prefixes and suffixes at grapheme boundaries.
For supported single-line geometry, the unchanged tail moves alongside the new
characters instead of reserving the completed edit's full width at frame zero.

The existing 64 Ki UTF-16 follow limit and maximum 32 hunks remain. Each hunk first scrolls for approximately 300 ms. Small typing is bounded to 700–850 ms, large writing to 1,100 ms and a removal preview to 650 ms, including a short highlight hold before erasure. New source observations and human input can interrupt a replay instead of queueing fake keystrokes.

## Ownership and rendering

`LiveChangesPanel` supplies existing diff ranges plus their removed text to `AgentChangeTour`. The tour owns its existing finite Swing timer and context checks. `AgentChangePlayback` is a bounded paint-only frame object: no timer, worker, document model, mutation API or global registry. It is installed only on its active source component and retired with the tour or presentation.

The existing `EditorUiController` source paint path invokes the overlay after syntax, diagnostic and other source painting. Pending glyphs are masked or faded within the changed range, and the agent cursor is drawn over that mask. The underlying source, selection, caret offsets, hit-testing coordinates and scrollable document geometry stay authoritative. Clicking/typing interrupts the view replay and reveals the actual source. Source document events retire the overlay, rather than accumulating edits in Undo or notifying LSP for animation frames.

Agent handoffs continue from the current source viewport. Following a change in
the already-active file does not reopen that buffer. External reloads preserve
each attached view's selection and viewport, while internal document updates
suppress both immediate and queued automatic caret scrolling. This keeps an
examining-to-edit handoff from returning to the human caret before the next tour.
Ordinary human caret navigation continues to reveal its destination.

While a followed file reloads, a transient paint of the previous viewport bridges
the interval before the diff replay is ready. This is a single bounded image
(at most four million pixels), not an old source model: queries see the completed
document immediately. Replay installation, another activity, source changes,
human interaction, viewport changes or hiding release it; a one-second expiry
also releases it if replay preparation does not complete. Unsupported capture
geometry falls back to the current source.

An explicit control-plane file open/read retires an active or pending automatic tour without disabling Follow. The presentation generation invalidates old observation/open callbacks, so they may update change metadata without retargeting the source after the explicit request. A subsequent filesystem change can still start a fresh tour. This uses the existing workbench and opening controllers; it adds no source-owner or navigation system.

`AgentEditPresentation` retains theme colors and the existing bounded highlight timer. Writing uses the success role, reading the warning role and removal the error role, with readable label colors. No font, line-height, density, theme preference, component hierarchy, settings panel or rendering framework is introduced.

Glyph masking is deliberately limited to unwrapped, non-complex layout and ranges whose endpoints are grapheme boundaries. Wrapped/bidi/complex documents and partial-cluster ranges retain directional highlights and ordinary reveal behavior; they do not receive speculative glyph masking or a new shaping engine. Reduced motion retains the existing static source/diff treatment without typewriter/removal animation. Unavailable geometry falls back to the real source. This does not resolve existing multilingual font coverage, Unicode first-edit or HTML-preview qualifications.

## Live demonstration

From the repository root, run:

```sh
make demo
```

This rebuilds the JAR if needed, resolves the existing JDK 21 through `scripts/resolve-toolchain.sh`, and runs the paced demo below. Watch the Shed window for roughly two minutes; it closes when the demo finishes. Each run saves its report and captures to a fresh `target/agent-demo-*` directory printed in the terminal. Python 3, a working desktop session and the existing fixed `/usr/bin/git` fixture are required. It does not start a real coding model. If Java is not installed in a discoverable location, set `JAVA=/absolute/path/to/jdk-21/bin/java` before `make demo`; a rebuild also needs Maven (`MVN` may select its executable).

For individual visual audits, run `make demo-list` to see all targets, then run one:

```sh
make demo-reading
make demo-typing
make demo-insertion
make demo-deletion
make demo-rewrite
make demo-search
make demo-checking
make demo-file-rename
make demo-terminal
```

To audit deletion followed by creation at a new location in the same window, run
`make demo ANIMATIONS=deletion,insertion LOOP=1`. This composes the existing
animations and reuses the deleted block; there is no separate move primitive or
`demo-move` target. The default `make demo` flow remains unchanged when no sequence
is selected, apart from showing relocation as deletion followed by creation.

For continuous auditing, use `make demo-reading LOOP=1` (or any individual target
with `LOOP=1`). It replays in the same window until you close it. Each cycle resets
only the disposable demo fixture; captures are overwritten and timing samples are
cleared between cycles. The full tour remains one-shot unless an explicit sequence is selected. This is continuous replay,
not Java hot reload: source changes need a rebuild/restart.

Annotation-only loops reset the viewport without reloading unchanged documents.
Intent requests wait for 250 ms of stable editor context before capturing their
request state, so deferred reset/layout events can settle. This is fixture pacing;
production context checks remain active and rejected requests are not retried.

Each `make demo-<name>` starts a fresh disposable project and shows only that scenario,
then closes after a five-second final hold. Run the same command again to replay it.
`make demo` retains the complete tour. Reports and PNG captures are saved under
`target/agent-demo-<name>-*`; the report records the selected animation.
Use `demo-typing` for ordinary batch modifications (the fixture changes multiple
occurrences of a word with the standard deletion/insertion animations), and
`demo-file-rename` for a filesystem rename. The source `rename` intent and
`demo-rename` target have been removed. `demo-checking`, `demo-passed`, and `demo-failed` isolate each check state;
the latter two perform a local compiler check on valid or deliberately invalid fixture
source. `demo-search` visits three matches in sequence using animated scrolling
between them. `demo-target` shows purple examining followed by an actual watched
source edit and its green writing replay. `demo-interrupt` lets the writing replay remain visible for 700 ms before
an explicit read takes over. Small writing replays last at least 700 ms. Edit replays generally hold their completed appearance
for 450 ms before advancing; incoming activity and human input can still interrupt
that visual hold immediately.
Formatting and agent-driven reverts use the ordinary watcher and edit replay. The
full demo applies a formatting-style rewrite, then restores the original source
through the same deletion/creation path. There are no separate `format` or `undo`
intents or demo targets. Human Undo retains normal editor behavior.
On X11 desktops the GUI process receives `DISPLAY` and, when set, `XAUTHORITY` from
the invoking session; fixture homes, Git configuration and client environments remain
isolated. No model or authentication is required.

The demo also includes [agent buffer intentions](AGENT_BUFFER_INTENTS.md): search matches, navigation, targets, check outcomes and conflict notification. Several client labels exercise the same vendor-neutral API; these are scripted clients, not real model calls.

To select an output location directly, use the existing JDK and rebuilt JAR with a new output directory:

```sh
python3 scripts/test-agent-desktop.py \
  --jdk /path/to/existing/jdk-21 \
  --jar /absolute/path/to/shed/target/shed-2.0.0.jar \
  --output /private/tmp/new-shed-animation-demo \
  --git-fixture --visual-only --animation-demo
```

This opt-in flow owns a disposable project, home, preferences and Git configuration. It opens the real workbench, sends real authenticated CLI reads, and makes paced filesystem edits, including multiline removal, rewrite, creation, modification, rename and deletion. It captures only its own root pane. PNG encoding occurs off the EDT so capture tooling does not freeze the animation. The final Git view stays visible briefly before cleanup. This is an automated client, not a new model call or universal observation of arbitrary filesystem reads.

The probe asserts source/revision/dirty/Undo/caret/selection stability during captures, records the actual animation modes and directions, and checks that synthetic human typing cancels playback with ordinary Undo available. It separately measures synthetic edit-to-completed-component-paint latency. Missing completion markers, failed requests or timing gates are failures, not success-shaped demo results.

## Qualification

Subsequent [edge-case qualification](performance/agent-animation-edges/INDEX.md) covers overlapping owners, theme/highlighter replacement, geometry changes, bounded repeated disposal and terminal handoff. The opt-in `--edge-cases` desktop probe uses source-only fixtures; it does not launch the terminal shell demo.

See [the evidence index](performance/adaptive-agent-animation/INDEX.md) for exact commands, source/artifact identities, tests, captures and measurements. The earlier broad live runs hit file-open/context timing failures; those remain recorded rather than bypassed. The paced earlier workflow completed before implementation began. The animation qualification is bounded to supported layouts and the safely exercised macOS direct-Java path; it is not general IDE release, Windows/Linux desktop or native-shell qualification.
