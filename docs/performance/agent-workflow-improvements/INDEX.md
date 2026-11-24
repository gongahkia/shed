# Agent workflow improvements

## 1. Capture correctness

The source overprinting in earlier app-owned captures is reproducible when a component laid out on the macOS 2× display is painted into a 1× BufferedImage. Base text painting and model-positioned syntax overlays then use different device metrics. Capturing with the component graphics configuration’s default transform removes the visible doubling without changing the production source renderer. The bundled physical font’s separate multilingual coverage limitation remains open.

- `render-before.png`: preserved earlier 1× capture.
- `render-after.png`: corrected 2× capture of the same disposable source fixture.
- `render-live.json`: real workbench/external Java client acceptance, reviewed Apply, exact Undo/Redo, typing and deliberate save. Not an OS desktop screenshot or physical-keyboard qualification.

Commands use the existing guarded `scripts/test-agent-desktop.py` with the previously generated demo payload. JDK 21.0.13.1+2, macOS aarch64; disposable home/preferences/project and no interactive shell or real language server. Failed exploratory renderer comparisons remain in the task scratch evidence; the renderer change was removed after the faithful native component capture identified the mismatch.

## 2. Everyday CLI

`cli-tests.json`: explicit attachment, state, project file opening, bounded reads, revision-pinned proposals, oversized input rejection and unknown-command refusal. See `docs/USING_SHED_WITH_AGENTS.md`.

## 3. Agent activity

Named display sessions, bounded counts and phases, sticky completion/cancellation, session-aware proposal rejection, status-line feedback and a 30-second maximum progress-pulse lease reuse the existing source decoration and coalesced timer. No additional idle worker or source mutation is introduced.

`activity-cold-failure.json` preserves a failed cold component run and its EDT stack: hidden range repaint requested model-to-view geometry and entered `CFontManager.loadNativeFonts`. The correction avoids text geometry for hidden source views; tests retain the original Unicode fixture and deadlines. No retry or assertion relaxation was added. `activity-tests.json` records the corrected regression run, including a hidden component whose geometry method throws if called.
