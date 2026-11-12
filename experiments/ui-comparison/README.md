# Bounded workbench comparison

Two isolated presentations of the **same real Shed application**, not replacement editors.
The decision and evidence are in [UI_MODERNIZATION.md](../../docs/UI_MODERNIZATION.md#stage-3--bounded-swingcompose-comparison).
Nothing in this directory is loaded by Maven, `scripts/shed`, or the normal release JAR.

## Run

Requirements: the repository's Java 21 toolchain, Python 3, and a desktop display. Compose's
first build downloads its pinned dependencies and Gradle. The `gradlew` here is a small
checksum-pinned Python bootstrap for Gradle 8.14.3, not the standard Gradle wrapper and not
a system-wide installation. Downloads/build output stay ignored in this experiment.

From the repository root:

```sh
make build
python3 experiments/ui-comparison/run.py build swing
python3 experiments/ui-comparison/run.py build compose
python3 experiments/ui-comparison/run.py manual swing
# Close that process before starting the other implementation.
python3 experiments/ui-comparison/run.py manual compose
```

Each launch creates its own temporary home, config, and the same two source fixtures. Both pin embedded macOS menu placement to reserve identical client space.
Settings changes really persist there. Source editing uses the existing Swing document,
selection, undo, syntax, line numbers, menus, tools and status bar. The experimental host
replaces only document navigation and the appearance panel. It deliberately retains the
production source editor. The temporary home is process-local; personal Shed configuration
and project files are not used. Temporary fixtures are retained for diagnosis under
`$TMPDIR/shed-ui-comparison-*` (or the platform's Java temporary directory).

Use **Ctrl/Cmd+,** to show appearance, **F6** to focus its search field, **Tab/Shift+Tab**
for standard traversal, and **Escape** inside the panel to dismiss it and return to source.
The toolbar switches themes and docks/detaches the same panel. Search uses production
setting descriptors/queries; the slice intentionally contains only six appearance settings.
Apply or Enter in a value field submits to the production settings boundary.

The host has a scoped AWT key dispatcher for activation/F6 because the legacy source key
listener can consume a shortcut before root-pane bindings. It is removed on disposal.
This composition adapter is shared by both implementations; no production keymap is changed.

## Reproduce evidence

```sh
python3 experiments/ui-comparison/run.py capture swing --output /tmp/shed-ui-captures-swing
python3 experiments/ui-comparison/run.py capture compose --output /tmp/shed-ui-captures-compose
python3 experiments/ui-comparison/run.py keyboard swing --output /tmp/shed-ui-keys-swing
python3 experiments/ui-comparison/run.py keyboard compose --output /tmp/shed-ui-keys-compose
LAB_COMPOSE_OFFSCREEN=1 python3 experiments/ui-comparison/run.py capture compose --output /tmp/shed-ui-captures-offscreen
```

Native capture is implemented **only for the macOS evaluation host**: identify a window
owned by the prototype PID using CoreGraphics and capture that window ID. It does not
capture the whole desktop. Other platforms fail explicitly; add an equivalent owned-window
capture adapter before claiming their screenshot coverage. A second `*-awt.png` is a diagnostic
root-pane paint: the default heavyweight Compose renderer may be absent from that paint.
Separate owned popup/dialog windows have separate captures. `gallery.json` records actual
accessibility-bridge query/update/action results, clipboard transfer with an isolated local
clipboard, real editor popup hosting, and native focus/keyboard observations. The native
keyboard probe sends keys only while its own window has focus and stops if focus is lost.
Consequently, an interrupted or unavailable native check is not a pass (`keyboard` exits 2). Run `keyboard` in
isolation for the shortest native check. Native IME, external screen readers and the system
clipboard are not covered by these probes.

Build both variants first. Stop other prototype windows, builds and tests, then run:

```sh
python3 experiments/ui-comparison/run.py compare --runs 5 --output /tmp/shed-ui-metrics
python3 experiments/ui-comparison/run.py package swing --output /tmp/shed-ui-packages
python3 experiments/ui-comparison/run.py package compose --output /tmp/shed-ui-packages
```

The comparison alternates fresh JVMs with identical Java/heap/fixture/window/settings.
It hashes artifacts before timing and records every sample. Process-to-ready includes the
JVM launch; host-ready starts at host construction. Both include the same 500 ms settling
interval after staged startup has ended, both fixture documents are open, and the first navigation/settings paint.
Footprint is observed heap usage (no forced GC) and macOS `ps` RSS another 500 ms later:
a settled startup observation, not long-duration steady-state memory. Edit timing is the
same synchronous insertion into the existing source document followed by `paintImmediately`;
10 warmups, then 25 samples per process. Undo is outside the interval. This does not measure
event-queue delay, hardware presentation or asynchronous highlighting/Compose convergence.
These startup definitions differ from Shed's normal desktop benchmark and cannot be
substituted for its historical budgets.

Packages are like-for-like ZIPs with a shell launcher and **external Java 21**, not native
installers or bundled runtimes. Extract, put Java 21 on PATH and run `./run.sh`. Compose's
native artifact is host-specific; rebuild per target. The launch classpath deliberately puts
Kotlin 2.3.20 before Shed's fat JAR, which already contains an older Kotlin runtime for its
terminal dependency. Default Maven dependencies/classpath are untouched. `installDist`'s
generated classpath is not the tested launch path; use this runner/packager.

## Ownership and limits

`LabModel` is shared **presentation** state: six filtered setting descriptors, document
metadata, palette/font/zoom tokens, query, local drafts and feedback. The original Java
application owns validation, persistence, buffers and edits. No full document is copied by
the 200 ms metadata refresh timer. Ports remain `SettingsApplication` and
`DocumentWorkspace`/`EditorSurface`. Only the host/model adapter knows `Texteditor`; neither
view knows it. Kotlin is confined to `ComposeLabFactory`.

View construction, model publication and port actions run on the EDT. Compose callbacks
explicitly marshal to that thread. Initial subscription delivery is synchronous on the
registration thread (the EDT here); later delivery is synchronous during EDT publication.
Swing views close their subscriptions; Compose uses `DisposableEffect` and `ComposePanel.dispose`.
Detaching disposes the old view and creates another against the same query/draft state.
Closing the host stops its timer and clears subscriptions/actions; scoped key dispatch is
removed. There are no background query jobs or stale asynchronous results in this slice.

This is a bounded comparison. It does not establish cross-platform release support,
production accessibility certification, or migration of specialized tools and editors.
