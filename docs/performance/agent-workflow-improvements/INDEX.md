# Agent workflow improvements

## 1. Capture correctness

The source overprinting in earlier app-owned captures is reproducible when a component laid out on the macOS 2× display is painted into a 1× BufferedImage. Base text painting and model-positioned syntax overlays then use different device metrics. Capturing with the component graphics configuration’s default transform removes the visible doubling without changing the production source renderer. The bundled physical font’s separate multilingual coverage limitation remains open.

- `render-before.png`: preserved earlier 1× capture.
- `render-after.png`: corrected 2× capture of the same disposable source fixture.
- `render-live.json`: real workbench/external Java client acceptance, reviewed Apply, exact Undo/Redo, typing and deliberate save. Not an OS desktop screenshot or physical-keyboard qualification.

Commands use the existing guarded `scripts/test-agent-desktop.py` with the previously generated demo payload. JDK 21.0.13.1+2, macOS aarch64; disposable home/preferences/project and no interactive shell or real language server. Failed exploratory renderer comparisons remain in the task scratch evidence; the renderer change was removed after the faithful native component capture identified the mismatch.
