# Phase 36 A1 Cold-Start Checkpoint

Date: 2026-07-07

Implemented slice:

- Added staged probes for initial document open, untitled document creation, window-controller init, pane install, document attach, window show, first display-link tick, and render begin.
- Split `EditorWindowController.installPane` into preferences, appearance, keymap, and callback sub-stages.
- Changed `EditorPreferences.load()` to validate the configured font directly instead of enumerating and sorting every installed font on startup.
- Copied SwiftPM resource bundles into `Itsy.app` so `Bundle.module` resolves packaged keymap/render/syntax resources without stalling under `NSWorkspace` launch.
- Added `first_window_visible_ms` to the regression baseline with a fixed `150 ms` limit.

Finding:

- Before the preference fix, direct stages showed `editor_pane_preferences_begin` to `editor_pane_preferences_end` at about `5.02 s`.
- After the fix, the same stage is about `2-4 ms`.

Direct `--bench-exit-after-initial-document` sample after the fix:

| Run | delegate_init ms | app_did_finish_launching ms | window_controller_init_begin ms | editor_pane_preferences_end ms | editor_pane_install_end ms | window_show_end ms | initial_document_opened ms |
|---:|---:|---:|---:|---:|---:|---:|---:|
| 1 | 98.882 | 178.192 | 254.358 | 326.304 | 327.735 | 400.781 | 401.390 |
| 2 | 104.116 | 183.527 | 270.197 | 356.065 | 358.097 | 444.626 | 445.363 |
| 3 | 107.945 | 185.036 | 288.532 | 371.639 | 373.092 | 459.944 | 460.744 |

Verification:

```sh
swift build --target ItsyApp
swift build -c release
bench/scripts/make_app.sh
.build/release/ItsyBench measure --staged --app Itsy.app --new-instance --timeout-ms 10000
bash -n bench/scripts/regression.sh bench/scripts/make_app.sh
```

Result:

- `swift build --target ItsyApp`: passed.
- `swift build -c release`: passed.
- `bench/scripts/make_app.sh`: passed.
- `bash -n bench/scripts/regression.sh bench/scripts/make_app.sh`: passed.
- staged external AX measurement now completes after bundling SwiftPM resources.
- current staged samples still fail the target: `355.908 ms`, `458.213 ms`, `522.621 ms`; one post-probe rebuild sample was `797.372 ms`.
- final verification sample put `process_start` at `672.206 ms`, `window_show_end` at `914.904 ms`, and `first_window_visible` at `948.651 ms`.

Remaining for #8:

- Reduce `NSWorkspace` launch-to-process-start variance or exclude it from the app-owned gate.
- Reduce `process_start` -> `app_did_finish_launching`, `window_controller_init_begin` -> `editor_pane_install_begin`, and `window_show_begin` -> `window_show_end`.
- Rerun 20 staged external-window measurements after the next optimization and close only after verified mean `first_window_visible < 150 ms`.
