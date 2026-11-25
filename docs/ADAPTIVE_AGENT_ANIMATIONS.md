# Adaptive agent change animations

Shed replays an observed diff in the followed source pane. The authoritative document already contains the completed external change: it is never temporarily retyped, reverted, normalized or replaced to produce an animation. Other splits and source queries continue to observe the real current document.

## Presentation rules

| Observed operation | Treatment |
| --- | --- |
| Small addition/replacement, at most 160 UTF-16 units and two newlines | Fast glyph reveal at grapheme boundaries, with a separate green agent cursor. Frames may coalesce under load; this is a visual replay, not a keystroke log. |
| Larger addition | Complete lines phase in, with an incoming-line fade and a left-to-right green sweep. |
| Larger replacement | Highlight a bounded preview of removed text, erase it right-to-left, then phase the replacement in left-to-right. Existing diff hunks determine the targets. |
| Text deletion | Scroll to the surviving source anchor, highlight an observed-removal preview, then erase its text right-to-left. The already-deleted source is not resurrected in the document. |
| Explicit agent read | Existing amber reading highlight, underline marker and top-to-bottom moving band. Requires `shed ctl read <file>` or an explicit reading progress signal. |

Small replacements skip the large removal preview. Removed text previews show at most eight lines and 1,024 UTF-16 units, with at most 200 units per drawn line and a count of the removed lines. An inline annotation identifies the content as an observed removal. Whole-file deletion retains the existing Explorer/Git deletion treatment and buffer-retention policy; it does not empty or recreate a human buffer to simulate deletion.

The existing 64 Ki UTF-16 follow limit and maximum 32 hunks remain. Each hunk first scrolls for approximately 300 ms. Small typing is bounded to 180–850 ms, large writing to 1,100 ms and a removal preview to 650 ms, including a short highlight hold before erasure. New source observations and human input can interrupt a replay instead of queueing fake keystrokes.

## Ownership and rendering

`LiveChangesPanel` supplies existing diff ranges plus their removed text to `AgentChangeTour`. The tour owns its existing finite Swing timer and context checks. `AgentChangePlayback` is a bounded paint-only frame object: no timer, worker, document model, mutation API or global registry. It is installed only on its active source component and retired with the tour or presentation.

The existing `EditorUiController` source paint path invokes the overlay after syntax, diagnostic and other source painting. Pending glyphs are masked or faded within the changed range, and the agent cursor is drawn over that mask. The underlying source, selection, caret offsets, hit-testing coordinates and scrollable document geometry stay authoritative. Clicking/typing interrupts the view replay and reveals the actual source. Source document events retire the overlay, rather than accumulating edits in Undo or notifying LSP for animation frames.

An explicit control-plane file open/read retires an active or pending automatic tour without disabling Follow. The presentation generation invalidates old observation/open callbacks, so they may update change metadata without retargeting the source after the explicit request. A subsequent filesystem change can still start a fresh tour. This uses the existing workbench and opening controllers; it adds no source-owner or navigation system.

`AgentEditPresentation` retains theme colors and the existing bounded highlight timer. Writing uses the success role, reading the warning role and removal the error role, with readable label colors. No font, line-height, density, theme preference, component hierarchy, settings panel or rendering framework is introduced.

Glyph masking is deliberately limited to unwrapped, non-complex layout and ranges whose endpoints are grapheme boundaries. Wrapped/bidi/complex documents and partial-cluster ranges retain directional highlights and ordinary reveal behavior; they do not receive speculative glyph masking or a new shaping engine. Reduced motion retains the existing static source/diff treatment without typewriter/removal animation. Unavailable geometry falls back to the real source. This does not resolve existing multilingual font coverage, Unicode first-edit or HTML-preview qualifications.

## Live demonstration

Use the existing JDK and rebuilt JAR, with a new output directory:

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

See [the evidence index](performance/adaptive-agent-animation/INDEX.md) for exact commands, source/artifact identities, tests, captures and measurements. The earlier broad live runs hit file-open/context timing failures; those remain recorded rather than bypassed. The paced earlier workflow completed before implementation began. The animation qualification is bounded to supported layouts and the safely exercised macOS direct-Java path; it is not general IDE release, Windows/Linux desktop or native-shell qualification.
