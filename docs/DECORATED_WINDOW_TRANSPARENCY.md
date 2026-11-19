# Decorated-window transparency — Tranche 8A.1

## A. Executive verdict

**NO-GO for production integration of background transparency in Shed's existing decorated Java 21 window under the investigated approach.** The public API rejects the requested configuration; the existing JNA helper does not supply a separate supported route around it. No viable transparent decorated-window configuration was established. Keep the current opaque appearance and do not begin 8B or 8C.

The representative decorated prototype attempted alpha **218**, alpha **0** and whole-window opacity **0.85**, both before peer creation and after display. All six requests threw `java.awt.IllegalComponentStateException: The frame is decorated`. Background alpha remained 255, window opacity remained 1, native decorations remained enabled, and source revision/selection stayed unchanged. This is an explicit rejection, not alpha silently ignored by a compositor.

This verdict concerns the tested Java 21/AWT integration and production gate. It does not establish that native macOS applications cannot have decorated translucent windows. A new native AWT integration would be a separate architectural investigation, not an implementation justified by the rejected configuration or existing undecorated demonstrations. No maintained, compatible native backend has been demonstrated here.

The [8A report](APPEARANCE_FEASIBILITY.md) and its raw evidence were read before this experiment. The isolated undecorated alpha mechanism remains a diagnostic control only. The earlier conditional product interest in transparency is **not a production GO**. The documented Unicode, preview, startup, native-memory and public-release limitations remain open.

## B. Window architecture

Current production boundaries were inspected directly:

| Boundary | Actual implementation / implication |
|---|---|
| Main window | `Texteditor extends JFrame`; `EditorUiController.initialize` sets title, menu, layout, placement and close handling. No main-frame undecorated configuration or custom title bar was found. |
| Native versus Swing decoration | The prototype reports `isUndecorated() == false` and root decoration style `NONE`: native frame decorations, not a Swing-rendered replacement title bar. |
| Ancestors | Frame → root/layered/content panes → editor host/split containers → scroll pane → viewport → source area. Opaque painting at any layer can cover background transparency. |
| Sidebar / rows | `FileTreePanel`'s renderer calls `setOpaque(true)`; list/tree/table selections and renderers have their own fills. |
| Tabs / controls | `WorkbenchStyle` paints tab backgrounds, combo fields, scroll tracks/thumbs, buttons and borders. Making only a parent `JPanel` nonopaque does not remove these fills. |
| Source / gutter | `EditorPane`, `SourcePlainView`, `WorkbenchStyle.TextAreaUI`, `LineNumberPanel`; the source `PlainDocument` belongs to `FileBuffer`. Presentation cannot change that ownership. |
| Preview | `MarkdownPreviewPane` has opaque title/background setup and generated HTML/CSS backgrounds. Clearing Swing opacity does not establish transparent HTML painting. |
| Theme propagation | `EditorUiController.applyUiTheme` updates component UIs and recursively reapplies RGB surface, field, selection, foreground and scroll colors. A future effect would need an explicit paint-ownership policy across theme changes. |
| Other windows | Settings, theme gallery, help, outline and detached tool windows use `JDialog`; palette picker construction explicitly uses an undecorated dialog; signature help uses an owned `JWindow`. Their existing behavior does not change the main frame's decoration contract. |
| Floating UI | Existing `JPopupMenu`, tooltips and owned windows have independent paint/ownership paths. Preserve opaque readable floating surfaces rather than making all windows transparent indiscriminately. |
| Disposal | `Texteditor` and its controllers already close workbench resources and dispose windows. A new effect would require its own lifetime without replacing this ownership model. No effect resources were attached. |

Audit correction to the earlier inventory: the sidebar renderer is in **`FileTreePanel.java`**, not a `FileTreeController.java` file. The earlier evidence remains unchanged; this report uses the actual checkout path.

To expose the desktop, a successful implementation would first need a translucent native window/backing surface, then coordinated fills across eligible ancestors. Fixing only the second condition cannot bypass the first. No production ancestor, renderer or theme code was modified in this tranche.

## C. Platform investigation

### Public Java 21 behavior

The installed `Frame.java` overrides enforce the decoration guard in `setBackground` and `setOpacity` before delegating to `Window`. The prototype reproduced those guards before and after a native peer existed. A per-pixel graphics capability of `true` does not override this frame-level precondition. Decoration changes are also restricted by displayability; toggling decorations to manufacture a translucent peer and then restoring native chrome is not an established solution. See [Java 21 Frame](https://docs.oracle.com/en/java/javase/21/docs/api/java.desktop/java/awt/Frame.html).

Background alpha and overall opacity have distinct meanings. Even if uniform opacity were accepted, it would fade foreground text/controls and would not meet the requested feature. Public window translucency also has fullscreen and device-capability conditions. See [Java 21 Window](https://docs.oracle.com/en/java/javase/21/docs/api/java.desktop/java/awt/Window.html).

The host's default graphics device reported both uniform and per-pixel support, with a 2× transform. The failure therefore occurred before testing desktop composition, not because the default device lacked a translucency capability. Only Temurin Java 21.0.13+9 on this macOS host was exercised; other JDK vendors/versions were not qualified. Shed's manifest enforces Java 21, and no runtime upgrade was attempted.

### Existing macOS / JNA routes

The installed OpenJDK `CPlatformWindow` coordinates native window opacity with the AWT content layer through `contentView.setWindowLayerOpaque`. Its title-bar/full-content properties affect chrome; they are not evidence of app-wide background transparency. Its window-alpha property delegates to the same Java opacity method. Private peers, reflection into JDK internals, changing the native content view, or modifying only `NSWindow.opaque` do not establish a maintainable AWT/Metal solution.

The frozen packaged JAR already contains **JNA 5.14.0 and `jna-platform` WindowUtils**. The exact packaged bytecode was inspected using `javap`, rather than assuming that an advertised alpha capability bypasses Java restrictions:

- `MacWindowUtils.setWindowTransparent` ultimately calls virtual `Window.setBackground` with alpha zero. A `JFrame` reaches the tested `Frame` guard.
- Its whole-window alpha path uses legacy reflective peer access and catches exceptions. It is not a supported background-only integration and was not invoked as a workaround.

Those findings agree with the [pinned JNA 5.14.0 source](https://github.com/java-native-access/jna/blob/5.14.0/contrib/platform/src/com/sun/jna/platform/WindowUtils.java). Existing library presence is not proof of compatible rendering, and no dependency was added or upgraded.

JAWT documents native rendering integration, platform surfaces and delivery/interop complexity; it does not certify an arbitrary modification of AWT's window/layer ownership. See the [official JAWT specification](https://docs.oracle.com/en/java/javase/21/docs/specs/AWT_Native_Interface.html). A native capability could only be reconsidered after a concrete supported backend and its Java 21 lifetime contract are established. No JNI library, AppKit hook, peer mutation, native blur view or custom chrome was installed or prototyped here.

## D. Prototype and gate

Added an isolated [DecoratedTransparencyProbe](../src/benchmark/java/shed/DecoratedTransparencyProbe.java) and [bounded driver](../scripts/qualify-decorated-transparency.py), outside normal production source paths. It uses existing packaged components/dependencies only:

- A native-decorated `JFrame`, a representative source `JTextArea` with production `SourcePlainView`/text UI, a scroll pane, sidebar list and tabbed pane.
- Canonical `FileBuffer`/`PlainDocument`, normal Shed Light colors, bundled JetBrains Mono 16, a solid foreground icon-like marker and ordinary opaque control/selection colors.
- Explicit requested alpha values 218 and 0; a separate whole-window-opacity rejection check; restoration to alpha 255 / opacity 1.
- Disposable scratch home/config and source fixture; no user preference writes or production settings.

Two fresh JVMs were used: **opaque control**, then **decorated transparency attempt**. The attempted child tests the API before display and again after a completed source paint and fixed settle. Rejected requests are followed only by bounded opaque-state regression observations and disposal. No further transparency candidate, native workaround, large workload matrix or new undecorated experiment was pursued after the gate failed. The existing 8A undecorated control was reviewed instead of rerun.

| Request | Before peer | Displayed peer | Classification |
|---|---|---|---|
| Background alpha 218 | Rejected | Rejected | No transparent configuration |
| Background alpha 0 | Rejected | Rejected | No transparent configuration |
| Whole-window opacity 0.85 | Rejected | Rejected | Also fails the background-only product requirement |
| Restore alpha 255 / opacity 1 | Accepted | Accepted | Existing decorated opaque state retained |

Both JVMs completed their expected protocol with exit 0. The **driver exits 3 for the recorded NO-GO**; that is a failed feasibility gate, not a successful transparency benchmark or an unexpected harness crash. Tooling tests explicitly distinguish incomplete evidence from a verdict.

## E. Visual and interaction correctness

The [opaque control image](performance/appearance-tranche8a1/opaque-opaque-components.png) and [post-rejection image](performance/appearance-tranche8a1/attempt-opaque-components.png) are offscreen renders of only the disposable component hierarchy. They were visually inspected. Background and solid foreground-marker pixel alpha were both **255**. The record does not claim that every antialiased glyph edge has alpha 255.

These are not desktop screenshots, native-title-bar captures, transparent-window evidence or blur demonstrations. No unrelated applications or wallpaper were captured. Native decoration state was observed through the frame/root-pane configuration; traffic-light, title-bar drag and physical native-control behavior were not independently exercised.

| Requirement | Result |
|---|---|
| Desktop visible through decorated background | **Failed at configuration gate.** No live decorated alpha state was created. |
| Foreground remains opaque | Existing opaque foreground paint retained; full-window fade was never applied. No transparent-case claim. |
| Caret / selection source state | Targeted rejected requests left caret dot/mark and document revision unchanged. Broad native input/IME behavior was not qualified. |
| Scroll / resize | Bounded opaque-state repaint observations completed. No transparent clipping/ghosting result is available. |
| Exact opaque restoration | Tested frame background alpha, overall opacity, decoration status, selection and source revision retained; no full physical-pixel/window equivalence claim. |
| Theme switching | Light/dark colors were read for contrast calculations; live transparent workbench theme switching was not tested. |
| Tabs / sidebar / scroll coverage | Present in representative opaque prototype; no app-wide transparent coverage demonstrated. |
| Popups / tooltips / native focus | Production code unchanged. Transparent interaction qualification unavailable after rejection. |
| HiDPI / multiple scales | Default 2× device used. Different scales, monitor migration and multiple-window composition not qualified. |

Actual syntax highlighting, Markdown preview, all keymaps, split workbench and full production editor input were not reconstructed for this negative window gate. The production regressions remain separate evidence, not certification of a non-existent transparent configuration.

## F. Accessibility and contrast

Read-only AppKit observation reported **Reduce Transparency false / Increase Contrast false**. No system setting was changed. Since no effect could be enabled, reacting to those preferences in a transparent decorated window is **unverified**; ordinary opaque behavior remains the fallback.

Calculations used existing Shed Light and Shed Dark foreground colors and their normal backgrounds composited over white and black. They are hypothetical contrast checks, not proof of a displayed transparency effect or new theme behavior:

| Theme / backdrop | Text: opaque → alpha 218 | Muted: opaque → alpha 218 | Comment: opaque → alpha 218 |
|---|---:|---:|---:|
| Shed Light / black | 11.49 → 8.17 | 5.15 → 3.67 | 4.27 → 3.04 |
| Shed Dark / white | 10.57 → 6.69 | 6.26 → 3.96 | 5.47 → 3.46 |

At alpha 128, light-theme body text over black falls to 2.91:1 and dark-theme body text over white to 2.20:1. The opaque light comment baseline is already below a 4.5:1 small-text criterion; that is not newly caused by this experiment. Full-alpha foreground paint alone does not preserve contrast.

High-contrast patterns, actual light/dark wallpaper, selection contrast and native accessibility interactions were **not visually tested in transparency**, because the window could not enter that state. Black/white endpoint calculations expose risk, but do not qualify patterned backgrounds. No automatic contrast detector, palette redesign or color correction was added.

If a future supported backend appears, minimum theme integration would need a defined background tint/alpha policy, readable opaque islands for controls/floating UI, existing focus/selection colors, and an immediate opaque override for accessibility preferences. It must be tested per theme; this report does not prescribe an arbitrary universal opacity floor or permit foreground fading.

## G. Performance and memory

### Environment / measurement boundary

2026-11-19; Mac15,12 Apple M3, eight CPU cores, 16 GiB; macOS 26.5.2 arm64; Temurin OpenJDK 21.0.13+9, `-Xms64m -Xmx512m`. Default graphics transform 2×; built-in and external displays connected, only default device used. Initial load 3.42/2.93/3.05; pre-existing used swap 1,119.50 MiB. Full environment and raw timestamped RSS are retained in [raw report](performance/appearance-tranche8a1/report.json.gz).

Frozen packaged JAR SHA-256: `6ee56710a5c6961860d8533bd916f9deef4ca445df71dadf5a9fa550c12bf3ee`. Fixture: 4,096-byte ASCII mixed Markdown generated by existing tooling. Two children, one per configuration; no statistical equivalence or whole-product startup claim. They are fresh processes, not cold filesystem-cache launches.

Each child records first insertion separately, 20 warm typed actions, 21 Undo actions, four scrolls and four resizes. Timing begins before EDT dispatch and ends at the source component's paint callback. It is not physical-input latency, all-window composition or display scanout. Scroll/blit can paint synchronously within an EDT action, so callback time and full action duration are separate boundaries. The benchmark does not reinterpret these as complete workbench responsiveness.

### Observed opaque controls — no transparent comparison exists

| Metric | Opaque child | Rejected-attempt child, still opaque |
|---|---:|---:|
| Window creation → source paint | 3,285.63 ms | 1,400.44 ms |
| First opaque insertion → source paint, n=1 | 0.930 ms | 4.149 ms |
| Warm opaque insertion paint p95, n=20 | 0.522 ms | 0.647 ms |
| Warm insertion EDT action p95 | 0.086 ms | 0.096 ms |
| Opaque Undo paint p95, n=21 | 0.465 ms | 0.614 ms |
| Opaque scroll paint maximum, n=4 | 0.517 ms | 0.513 ms |
| Opaque resize paint maximum, n=4 | 3.676 ms | 4.372 ms |
| Used / committed heap after fixed 500 ms settle | 20.03 / 66 MiB | 20.03 / 66 MiB |
| Nearest settled RSS sample | 562.17 MiB | 561.89 MiB |
| Sampled peak RSS | 577.48 MiB | 579.48 MiB |
| Used / committed heap after disposal + diagnostic GC requests | 9.22 / 64 MiB | 9.23 / 64 MiB |
| Nearest post-disposal RSS sample | 577.41 MiB | 579.48 MiB |

The launch-order difference is not an effect speedup. RSS is sampled every ~250 ms; a single nearest observation is not a settled product idle budget or exhaustive process maximum. Heap snapshots and RSS have different accounting. Fixture/source and disposed components remain referenced by harness locals until process exit, so this is not a retired-object retained-heap test.

**Unavailable by design after the failed gate:** transparent editing/resize/scroll costs, actual desktop composition, repeated successful enable/disable, repeated transparent window lifecycle, native graphics allocation comparison and multi-monitor performance. No new NMT/vmmap campaign was justified for an effect that could not be enabled. Existing 8A native graphics observations remain limitations, not evidence of overhead from this rejected configuration. No claim is made that alpha overhead is low, that native resources were fully reclaimed, or that the known Unicode/startup/preview problems improved.

## H. Resource lifecycle

Both children detached the source view from the authoritative document, disposed their frame, and reported **zero displayable windows**, exact source after Undo and source document listeners back to the pre-attachment count (**one**). A `finally` path disposes all process-owned windows even if an assertion fails. The driver imposes a 30-second child timeout with bounded termination. No native resource was attached to the frame; no observer, permanent worker, cleanup timer or persistent cache was introduced.

Three explicit GC requests are isolated diagnostics after disposal, never production memory management. Post-disposal RSS did not fall in this short interval; that is not evidence of a new lifecycle leak or of complete native release. No repeated transparent lifecycle claim is possible. FileBuffer ownership, recovery, shared splits and production disposal are unchanged and covered separately by the regression suite.

## I. Cross-platform strategy

Portable Swing painting does not override `Frame`'s Java decoration precondition. The same Java 21 guard is present before platform delegation; this does not constitute Windows/Linux headful testing. The undecorated per-pixel mechanism can be device-dependent and remains outside the approved main-window design.

For now **all platforms retain the existing fully opaque appearance**. An unsupported optional effect must not affect startup, editing or rendering. Graphics capability flags alone are insufficient admission checks; a future backend must validate actual decorated-window support and fail closed before changing presentation.

A macOS-specific backend would require stable native-window identity, coordination with the AWT drawing layer, AppKit/EDT thread ownership, cleanup and package/runtime compatibility. Existing JNA convenience methods do not establish that backend. New native binaries would also require architecture-specific packaging and signing review; none was introduced, signed or notarized here. No private-peer flags, custom title bar, runtime upgrade or platform build change was attempted. Windows/Linux native effects would be separate optional implementations, not launch dependencies.

## J. Production recommendation

**Retain opaque rendering. Do not integrate the failed approach.** There is no smallest viable production patch demonstrated by this tranche. Adding alpha to theme colors, making panels nonopaque, using whole-window fading or recreating an undecorated main peer would not satisfy the acceptance criteria.

Do not create a native-platform abstraction speculatively in production. If a concrete maintained decorated-AWT backend is subsequently identified, first require an isolated proof of its public API, exact window/layer ownership, foreground rendering, opaque restoration, native lifetime and packaged compatibility. A narrowly scoped optional backend could then be considered; a generic native framework or broad UI replacement is not justified by current evidence.

## K. Tranche 8B handoff

**No 8B implementation handoff: the verdict is NO-GO.** The smallest justified next step is to record the supported opaque policy and close this transparency path. Further research should reopen only with specific new evidence of a supported decorated Java 21 integration, not another broad alpha experiment or an undecorated screenshot.

Other approved appearance ideas do not depend on desktop transparency; separately reviewed exact restoration of existing focus mode remains an independent option. Nothing here starts that work, native blur, 8B, 8C or a source-layout project.

## L. Repository verification

Starting HEAD: `2e3cfd8e7b5618d0cc330a77e1967d47a699651b`. The starting [inventory](performance/appearance-tranche8a1/baseline.json.gz) records 1,451 pre-existing files, including uncommitted production fixes and all prior 8A evidence. No pre-existing file was edited for 8A.1.

Added files only:

- `src/benchmark/java/shed/DecoratedTransparencyProbe.java` — disposable decorated public-API gate and opaque-state observations.
- `scripts/qualify-decorated-transparency.py` — bounded external-output driver; exit 3 records NO-GO.
- `scripts/test_decorated_transparency.py` — three fast admission/fixture/verdict tests; no desktop launch in routine tests.
- `docs/DECORATED_WINDOW_TRANSPARENCY.md` — this report.
- `docs/performance/appearance-tranche8a1/` — raw reports/logs, offscreen opaque images, bytecode/JDK audit, measurements, baseline, verification and per-file inventory.

The [file inventory](performance/appearance-tranche8a1/inventory.json) lists every added file and byte identity.

The [verification record](performance/appearance-tranche8a1/verification.json) contains final Maven/tooling/media counts, skips and repository hashes. No skipped/unavailable transparent or native qualification is counted as passing. The failed feature gate is preserved alongside passing harness assertions and regression results.

Final regression results: **1,565 Maven tests total, 1,560 passed, five existing skips, zero failures/errors**; **31 tooling tests passed**, including three new focused tests; packaged Mermaid/math/image smoke **PASS**. The five skips are the existing DOS-hidden-attribute, opt-in AI performance, isolated fish startup, explicit CTest preset and opt-in native AI acceptance cases; exact conditions are in the verification record. The full suite does not override the negative feasibility result.

```sh
source /tmp/shed-tranche2c-run/toolchain.env
export PYTHONDONTWRITEBYTECODE=1
python3 -m unittest discover -s scripts -p 'test_decorated_transparency.py'
python3 scripts/qualify-decorated-transparency.py --jar /tmp/shed-tranche8a1-run/production.jar --output /tmp/shed-tranche8a1-gate
# driver exit 3: expected recorded NO-GO; both child protocols exit 0
"$MVN" test
python3 -m unittest discover -s scripts -p 'test_*.py'
python3 scripts/test-packaged-markdown.py --jar /tmp/shed-tranche8a1-run/production.jar --output /tmp/shed-tranche8a1-run/packaged-smoke.json
git diff --check
shasum -a 256 README.md
```

README before/after SHA-256: **`48bf87f09ffa570b7fc65d305a15c3228bbc67e906cc992e4fe7108893e7501e`**. All pre-existing work and previous evidence were hash-compared; HEAD and packaged JAR remain unchanged. No production appearance, component, setting, dependency, native hook, source ownership, keyboard route or startup path changed. Shed Light, bundled JetBrains Mono and saved-preference precedence remain intact. No resets, cleans, reverts, commits or pushes were performed. The failed feasibility path stops here.
