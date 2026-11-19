# Appearance feasibility — Tranche 8A

## A. Executive verdict

**Do not begin production background transparency or native blur yet.** Background-only alpha painting is possible in an isolated, undecorated Swing window. That does not establish a compatible implementation in Shed's decorated workbench. The current Java 21 public window APIs reject both tested translucent-background and whole-window-opacity calls on a decorated frame. Removing decorations would violate this tranche's compatibility boundary. Contrast also deteriorates for existing muted and syntax colors over dark backgrounds.

| Approved feature | Verdict | Reason and next gate |
|---|---|---|
| A. App-wide background transparency | **CONDITIONAL GO** | Alpha-background mechanism demonstrated separately; decorated-window compatibility, readable backgrounds and complete surface coverage remain unproved. The public-JDK decorated-frame approach is **NO-GO**. |
| B. Native macOS blur/vibrancy | **CONDITIONAL GO** | AppKit provides the effect. Safe placement and lifetime inside an AWT-owned window have not been demonstrated. No blur prototype or blur performance result is claimed. |
| C. Focus mode / centered writing | **CONDITIONAL GO** | Much already exists. Exact prior layout, divider and focus restoration needs a bounded state contract and tests. |
| D. Automatic themes / theme preview | **CONDITIONAL GO** | Existing gallery already previews samples without committing; OS-following and transactional whole-workbench preview need event, preference and rollback contracts. |
| E. Density presets | **CONDITIONAL GO** | A shared scaling seam exists, but spacing density must be separated from font/editor zoom; dimensions remain distributed. |
| F. Active-pane emphasis | **GO** | A subtle active split border and accessible active-pane label already exist. Reuse and verify these before proposing additional treatment. |

GO means an architecture is suitable for a separately authorized implementation, not permission to ship or start it. The combined automatic-theme feature is conditional even though the existing sample preview is delivered. No production feature, setting, component, dependency or window behavior was changed by 8A.

The [7C readiness decisions](MARKDOWN_PRODUCT_READINESS.md) remain: conditional development use; **NO-GO for general public release and the original performance-first ambition**. Unicode first-edit stalls, 51 corpus mismatches, stock HTML-preview stalls, native-memory attribution and cross-platform qualification remain open. This study does not weaken those decisions.

## B. Current architecture inventory

The audit used the current checkout and frozen production JAR, including pre-existing uncommitted remediation work. It did not assume a clean checkout. Starting HEAD is `2e3cfd8e7b5618d0cc330a77e1967d47a699651b`; the [baseline inventory](performance/appearance-tranche8a/baseline.json.gz) records 1,422 pre-existing files and starting Git status.

| Area | Actual implementation | Reuse / constraint |
|---|---|---|
| Window | `Texteditor extends JFrame`; `EditorUiController.initialize`; window listener disposes workbench resources | Decorated AWT window, no production native blur/alpha backend or stable native-window mapping found. Retain title bar, resize and lifecycle. |
| Source | `EditorPane`, `SwingEditorSurface`, `WorkbenchStyle.TextAreaUI`, `SourcePlainView`, canonical `FileBuffer` / `PlainDocument` | Presentation-only effects must not touch source, undo, revision, recovery or key routing. Complex text and wrapping retain existing Swing paths. |
| Painting | `EditorUiController.applyUiTheme`, recursive component colors, `WorkbenchStyle` UI delegates and custom painting | RGB fills exist at many layers, not just the frame. Theme reapplication can reset visual state. |
| Scroll / rows | `JScrollPane`, `JViewport`, `FileTreeController` opaque row renderer, `LineNumberPanel`, tabs, status and tool windows | Ancestor transparency alone cannot expose the background. Renderers and custom fills need explicit coverage. |
| Preview | `MarkdownPreviewPane`, `MarkdownPreviewRenderer`, HTML/CSS backgrounds, opaque title/editor viewport | Making a Swing component nonopaque does not remove CSS background painting. Preview compatibility is a separate required test; no preview changes made. |
| Themes | `ConfigManager`, `ThemePreview`, `SessionConfigController.setThemeFromCommand`, `EditorUiController.applyUiTheme`, `ThemeGalleryDialog` | Reuse registry, colors and existing gallery. Current theme application updates component UIs and revalidates/repaints; it is not a cheap alpha-only transaction. |
| Preferences | Typed TOML defaults / runtime values / persisted configuration; `setOptionAndPersist`, config save, session restoration | Preserve saved preference precedence; temporary previews and OS notifications must not silently persist choices. |
| Fonts | `BundledEditorFont`, separate UI/editor/terminal font configuration, `UiZoom` | Default remains bundled JetBrains Mono, 16-point source font; density must not change font metrics as a shortcut. |
| Layout / focus mode | `FocusModeController`, `WindowLayoutNode`, `WorkbenchPlacementState`, `ToolWindowHost` | Existing Goyo/Zen hides chrome; writing width uses `zen.mode.width` and scroll-pane padding. No new layout framework needed. |
| Active split | `EditorUiController.refreshPaneFocus` | Existing two-pixel-scaled viewport top border, active/inactive theme colors, `shed.activePane` property and accessible pane label. |
| Platform / distribution | Java 21; macOS/Windows/Linux packaging scripts | Current macOS script targets arm64, makes a jlinked app, ad-hoc signs and verifies it, and records notarization as not requested. No new signed native payload was tested. |

No existing OS light/dark notification consumer was found in the inspected theme paths. An appearance-related AWT startup property is not evidence of a live notification service. No production `setOpacity`, per-pixel window setup or AppKit visual-effect integration was found.

`WorkbenchStyle.px` delegates to `UiZoom.scale`, and many borders/control dimensions reuse it. Other explicit dimensions and insets occur in `EditorUiController`, `WorkbenchHeader`, `OpenEditorsBar`, `SettingsEditor`, `ThemeGalleryDialog`, `ToolWindowHost` and layout classes. The current seam is useful, but it does not make a density change a one-line configuration switch.

## C. Transparency feasibility

### Mechanisms are distinct

| Mechanism | Foreground effect | Assessment |
|---|---|---|
| `Window.setOpacity` | Fades the entire window, including text, icons and controls | Rejected as an implementation of the requested background-only feature. |
| Alpha `Window.setBackground` | Enables per-pixel window translucency where supported | Necessary public-JDK experiment seam, but the Java 21 decorated-frame guard blocks using it directly in Shed. |
| Nonopaque Swing children | Skips selected background fills | Necessary, insufficient: an opaque ancestor, cell renderer or UI delegate still covers the effect. |
| One alpha background plus ordinary foreground painting | Alters background contribution without globally fading foreground | Demonstrated in the isolated experiment. Needs correct backing/compositing, clipping and repaint ownership. |
| Native desktop transparency | Lets the compositor combine the application surface with content behind the window | Requires working window/peer support, not merely an ARGB image rendered offscreen. End-to-end decorated Shed compatibility remains unverified. |
| Native blur / vibrancy | Samples and treats a background using platform materials | Separate feature; alpha alone does not implement it. |

Java 21 specifies translucency capability, fullscreen and decoration constraints; per-pixel alpha and whole-window opacity have different behavior. The disposable decorated-frame checks both threw `IllegalComponentStateException: The frame is decorated` on this runtime. Older Swing tutorial wording about decorated windows is not a substitute for the Java 21 contract and this observed rejection. See [Java 21 Window](https://docs.oracle.com/en/java/javase/21/docs/api/java.desktop/java/awt/Window.html) and [Frame](https://docs.oracle.com/en/java/javase/21/docs/api/java.desktop/java/awt/Frame.html).

Capability must be checked on the relevant graphics device, not assumed for every monitor or platform. This host reported uniform and per-pixel capability on the default device, with a 2× transform. No monitor migration test was performed. See [GraphicsDevice](https://docs.oracle.com/en/java/javase/21/docs/api/java.desktop/java/awt/GraphicsDevice.html).

### Proposed painting ownership, subject to the window gate

Use a single background owner for a surface and ordinary foreground paint above it. Merely assigning alpha colors to all ancestors stacks alpha layers, causing unintended opacity and color changes. Do not install a fading composite around a whole component subtree. Keep control surfaces, selected rows, popups and tooltips opaque where needed for readability. Do not promise that every panel has identical translucency: an explicit surface coverage inventory must identify opaque exceptions.

The disposable prototype used an alpha root fill, nonopaque editor/viewport/scroll-pane containers, normal opaque text colors and opaque selected-row/popup backgrounds. Its offscreen pixel assertion checked background alpha 218 or 255 and a solid foreground marker at 255. This is a paint contract, not proof that every antialiased glyph edge is opaque or that text has adequate contrast.

Production requires an inventory through the root pane, layered/content panes, workbench containers, tree/list/table renderers, gutter, tabs, status/tool windows, overlays, scrollbars and HTML preview backgrounds. Reusing existing theme color inputs is preferable to a second theme engine. Repeated theme changes must retain the effect state without making unrelated components transparent.

Decorations, native resizing, fullscreen, popup ownership, input hit testing at transparent pixels, selection/caret visibility, HiDPI/multiple monitors, preview, accessibility and supported native text input remain unverified for a decorated integration. Do not substitute an undecorated custom title bar: it changes the approved design and interaction contract.

## D. Native blur feasibility

AppKit's `NSVisualEffectView` supplies material-based background effects, with behind-window and within-window blending modes. Materials react to appearance/state; they are not a general public adjustable blur-radius API. Existing Swing text does not become an AppKit vibrancy-aware control merely by being drawn above a material. Treat foreground rendering separately. See [NSVisualEffectView](https://developer.apple.com/documentation/AppKit/NSVisualEffectView?language=objc).

The installed SDK marks the base class available from macOS 10.10, with several modern semantic materials from 10.14. That does not establish Shed's effective minimum supported OS: it must also satisfy the selected JDK, launcher and package requirements. No new minimum OS or binary compatibility promise is introduced here.

The hard boundary is safely associating a live AWT window with a supported native view/layer insertion point. The official [JAWT interface](https://docs.oracle.com/en/java/javase/21/docs/specs/AWT_Native_Interface.html) documents macOS layer-based integration. It does not provide a general stable Java `NSWindow` handle or permission to replace AWT's content view. The inspected JDK source uses `CPlatformWindow` / `CPlatformView` and coordinates native window and content-layer opacity. Changing just `NSWindow.opaque` would not establish that the Metal/AWT surface exposes the effect.

The installed OpenJDK sources also expose chrome-oriented properties such as `apple.awt.fullWindowContent`, `apple.awt.transparentTitleBar` and `apple.awt.windowTitleVisible`. These concern window chrome; they are not an app-wide background or blur API. Private peer reflection, `--add-exports`, internal selectors and replacement of an AWT-owned content view are not acceptable shortcuts.

JNA already exists transitively in the packaged JAR, and existing benchmark capture code uses it. This is neither a new dependency added by 8A nor evidence of a maintained AWT blur bridge. A future backend would need an explicit dependency/licensing/version decision rather than accidental reliance on terminal-stack transitive dependencies. No JNI library, native view, blur runtime or native window mutation was added.

**No native-blur experiment was built.** A standalone Cocoa blur window would demonstrate AppKit rather than its compatibility with Shed. No safely owned insertion point was established for the Swing window, so building a peer-manipulation experiment would exceed the supported-integration gate. The blur verdict is conditional, with performance, foreground preservation, resize and disposal costs **not measured**. Replacing major UI surfaces to gain blur is NO-GO under these constraints.

A separately approved bridge investigation must establish one-window identity, native view ownership, AppKit main-thread execution, safe EDT handoff, idempotent detach/disposal, observer removal and late-callback rejection. It must not hold locks while synchronously crossing EDT/AppKit boundaries. The backend should fail closed to the current opaque window, with zero required native effect for launch/editing. It must attach/remove only its own view, leave focus/accessibility/input ownership with AWT, and support disable without reconstructing the editor.

Independent background tint/opacity and native material enablement are plausible design inputs, **not demonstrated controls**. Native material choices must not be advertised as continuous blur intensity. Resize, scroll, active/inactive windows and theme changes need actual compositing tests with one/two windows. Reduce Transparency must force opaque rendering; AppKit provides both a current preference and a notification on the workspace notification center. See [Reduce Transparency](https://developer.apple.com/documentation/appkit/nsworkspace/accessibilitydisplayshouldreducetransparency?language=objc) and [display accessibility notification](https://developer.apple.com/documentation/AppKit/NSWorkspace/accessibilityDisplayOptionsDidChangeNotification).

Any native payload would require architecture-specific builds, runtime/JDK compatibility testing, nested signing/verification, distribution review and a notarization plan. The existing arm64 ad-hoc package is not proof of public distribution readiness for such a payload. No package/signing requirements or production dependencies were changed.

## E. Remaining feature architecture

### C — Focus mode and centered writing

Reuse `FocusModeController`'s Goyo/Zen path, existing panel visibility and scroll-pane padding. Existing functionality already centers writing and hides nonessential chrome. It does not prove the requested exact restoration: current exit logic unconditionally shows the tree, uses a limited set of saved visibility flags, and does not capture every divider/focus relationship.

The smallest extension is one bounded per-focus-session layout snapshot: pane identities/tree, active pane/focus target, visible panel state, tool selection, divider locations and writing insets. Capture once on entry; restore still-valid targets on exit; define behavior when files/splits are closed or manually changed during focus mode. Do not clone documents, destroy splits or persist temporary visibility. Reuse `WindowLayoutNode`, `WorkbenchPlacementState` and `ToolWindowHost` rather than inventing a workspace manager. Test entry/exit idempotence, pre-hidden panels, arbitrary divider ratios, shared buffers, keyboard focus and cancellation/disposal. No native dependency needed. Conditional until exact restoration is specified and verified.

### D — Automatic themes and temporary previews

Reuse the theme registry and existing gallery. The gallery currently previews code/swatches without changing the workbench; its explicit Apply and Save path persists the chosen theme. Preserve that distinction. For a future whole-workbench preview, capture one prior runtime theme and commit/cancel transaction; close/cancel restores runtime state without writing config. Explicit commit alone persists.

OS-following needs a narrow event source and a policy separating manual choice, follow-OS mode and preferred light/dark IDs. Respect existing saved theme choice by default. Do not overwrite it on each OS event, invent polling or infer live notifications from startup properties. A native macOS event adapter needs its own ownership/cleanup proof; Windows/Linux require tested platform signals or a manual opaque fallback. Coalesce events and apply changes on the EDT; avoid repeated full component-UI replacement. Existing theme application can invoke costly layout paths, so Unicode first-use and preview interaction must be measured during switching. No OS observer or theme settings added here.

### E — Density presets

Reuse `WorkbenchStyle` sizing seams and existing component layouts. Introduce independent spacing/dimension tokens only for a small, enumerated chrome surface set in a future tranche; default token values must equal today's dimensions. Do not relabel global `UiZoom` as density: it also affects fonts/metrics. Audit explicit row heights, control minimums, borders, icons, dividers and popup sizing before coverage claims. Keep source fonts, caret metrics, tab columns and document layout unchanged.

Test compact/default/comfortable geometry at multiple existing zoom levels, long localized labels, keyboard focus and control hit targets. Revalidate affected chrome once per explicit preset change, never per keystroke. A full audit might reveal more constants than justify a first implementation; narrow the covered surfaces instead of refactoring hundreds of layouts. Conditional; no framework/native dependency required.

### F — Active-pane emphasis

Reuse `refreshPaneFocus` and its theme colors, focus property and accessible label. Its existing border reserves the same geometry for active/inactive panes and is applied at pane-focus changes. This already meets the basic requested treatment. Verify one/two/multiple splits, light/dark themes, keyboard focus, high contrast, single-pane neutrality and disposal. Avoid foreground fading, document changes, animations or per-keystroke work. GO for this existing architecture; a new feature is unnecessary unless a concrete visibility deficiency is demonstrated.

## F. Isolated experiment and failures

One experiment was built: [AppearanceTransparencyProbe](../src/benchmark/java/shed/AppearanceTransparencyProbe.java), launched by [the opt-in runner](../scripts/benchmark-appearance-feasibility.py). It creates disposable **undecorated** Swing frames over its own striped backdrop window. It reuses a canonical `FileBuffer`, `PlainDocument`, production `SourcePlainView`/text UI, Shed Light and bundled JetBrains Mono. It is absent from the normal Maven production sources and frozen JAR. Two source panes/windows share one source document in the corresponding cases.

It does not construct Shed's workbench, native title bar, real syntax overlay, preview or input controller. It exercises the Swing typed action, Undo, resize, scroll and alpha enable/disable. It does not certify native keyboard/IME routing, full keymaps, every popup, Markdown preview or monitor migration. No actual Mermaid initialization or failed live-presentation prototype is used.

Per child: one separately reported first insertion; 50 warm insertion/Undo pairs; an additional first-insert Undo; ten resize and scroll samples each; five toggle and restore samples each. A signal records completion of the first source component's paint callback for editing and first root child paint for resize/toggle; it does not wait for every split/window to finish painting. These are scheduled Swing component paints, not physical display scanout or hardware-input measurements. Timing starts before EDT dispatch. No forced paint is substituted for editing. Source text is compared with fixture bytes after Undo, source listeners return to the pre-attachment count, and displayable windows are counted after disposal.

The matrix contains 20 fresh processes: 4 KiB, 1 MiB and 10 MiB single-pane; 1 MiB two-pane; 1 MiB two-window; each opaque and alpha 218, in two alternating paired runs. Ten MiB here is an isolated source-widget fixture, not expanded production editable-file support. Two further processes collect NMT/vmmap, and two verify optional-capture handling. The initial two-process pilot is retained separately, not pooled into final numbers.

**Failures are retained:** the matrix's first opaque 4 KiB run completed all 133 timing samples and the ARGB assertion, then failed an optional OS owned-window capture (`could not create image from window`). It has no successful post-disposal metrics and is not counted as a lifecycle pass. The other 19 matrix children, both NMT children and both capture-control children passed their lifecycle assertions. The final probe records an unavailable optional capture instead of treating it as a successful image or abandoning subsequent measurements. The original matrix probe is archived alongside the final source hash; two capture-control children passed after this diagnostic-only change.

An earlier pilot rectangle capture selected an occluding application rather than just the experiment. Both rectangle captures were removed; none is included in evidence. The final mechanism uses the repository's existing PID/title-qualified owned-window capture, with a read-only screen-capture permission preflight, after performance trials. It requests no new permission. Included images contain only the disposable experiment. An owned-window surface image may retain alpha and excludes the desktop behind it; it is **not proof of blur or a photographed composited desktop**. Offscreen alpha images establish only the stated pixel contract. No native-blur experiment was attempted for the reasons in D.

## G. Performance and memory

### Environment and boundaries

2026-11-19; Mac15,12, Apple M3 (8 CPU cores), 16 GiB; macOS 26.5.2 arm64; Temurin OpenJDK 21.0.13+9. Default display transform 2×. Built-in 2560×1664 and external 1920×1080 displays were connected; tests used the default device, not both-monitor coverage. JVM `-Xms64m -Xmx512m`; diagnostic pair alone adds `-XX:NativeMemoryTracking=summary`. Scratch homes contain only explicit light-theme/no-session-restore test preferences. User preferences and OS settings were not changed.

Frozen production JAR SHA-256: `6ee56710a5c6961860d8533bd916f9deef4ca445df71dadf5a9fa550c12bf3ee`. Each report records source/runner/capture hashes, commands, fixture identity, Java version, load, display, pressure, swap and raw timestamps. Matrix starting load was 4.79/3.54/3.20 with 61% reported memory free and 1,143.50 MiB already-used swap; ending/control conditions are retained. Existing host activity was not disrupted. These are fresh JVM launches, **not cold filesystem-cache tests**. Two paired runs cannot establish statistical equivalence.

### Same-host uninstrumented matrix

Ranges below are across the two children per cell. Warm p95 uses 50 edits per child; first edits are separate observations. Readiness is process launch to first completed disposable-window paint; it is not Shed workbench readiness. All times milliseconds.

| Fixture / panes / windows | Opaque warm paint p95 | Alpha 218 warm paint p95 | Opaque first edit | Alpha 218 first edit |
|---|---:|---:|---:|---:|
| 4 KiB / 1 / 1 | 0.80–1.63 | 0.96–1.16 | 1.82–5.23 | 4.33–4.83 |
| 1 MiB / 1 / 1 | 0.54–0.56 | 0.62–0.82 | 2.22–2.31 | 2.72–2.78 |
| 10 MiB / 1 / 1 | 0.53–0.62 | 0.56–0.63 | 5.30–6.06 | 5.81–6.07 |
| 1 MiB / 2 / 1 | 0.77–0.83 | 0.90–9.68 | 2.30–2.44 | 3.13–14.88 |
| 1 MiB / 1 / 2 | 0.57–0.94 | 0.78–8.94 | 2.45–2.65 | 2.52–6.95 |

The first opaque process needed 3,180 ms to initial paint; other opaque cases 1,644–1,965 ms, translucent cases 1,638–1,925 ms. The later capture-control pair also had a first opaque launch of 3,388 ms versus 1,587 ms alpha. This order-sensitive first-use difference is not a translucency speedup. Input was accepted by the scripted first typed action after the fixed 500 ms settle; raw launch-to-first-action timestamps are retained. Physical keyboard-to-acceptance was not measured.

For 1 MiB single-pane: EDT action p95 0.085–0.087 ms opaque / 0.110–0.119 ms alpha; Undo paint p95 0.505–0.542 / 0.636–0.702; resize p95 1.19–1.83 / 1.48–2.44; scroll p95 0.89–0.91 / 1.08–1.13; enable/disable p95 2.45–3.44 / 5.01–5.15. Switching includes both directions; these are not separate native-blur transitions. Split alpha outliers include resize 11.12 ms and Undo 10.09 ms. The full raw distribution, including maxima, is in [measurements](performance/appearance-tranche8a/measurements.json); do not pool it into a claim that production compositing costs are negligible.

### Memory, disposal and graphics diagnostics

Heap was sampled after a fixed 500 ms settle without forced GC. RSS is nearest 250 ms process sample; peak RSS is **sampled**, not an OS exhaustive maximum. Capture occurs later and can affect peak/disposed numbers. Short-window settled observations are not an idle product budget.

| Single-pane fixture | Opaque settled used heap | Alpha settled used heap | Opaque settled RSS | Alpha settled RSS | Opaque / alpha sampled peak RSS |
|---|---:|---:|---:|---:|---:|
| 4 KiB | ~18.0 MiB | ~18.0 MiB | 562.3–562.4 MiB | 562.4–562.6 MiB | 584.5–591.5 / 595.4–602.6 MiB |
| 1 MiB | 21.3–21.5 MiB | ~21.0 MiB | 603.1–604.3 MiB | 596.3–611.0 MiB | 615.4–617.0 / 607.4–619.2 MiB |
| 10 MiB | 167.5–171.4 MiB | 131.3–131.7 MiB | 737.2–753.2 MiB | 739.3–741.9 MiB | 762.4–788.6 / 771.9–782.4 MiB |

Different GC timing makes the 10 MiB used-heap difference unsuitable as an opacity benefit claim. After disposal and three diagnostic-only GC requests, used heap was ~9.4 MiB / ~20.4 MiB / ~84.7–84.9 MiB for the three sizes. The harness still holds its source buffer/document and local references to disposed components; these are not retired-document or retired-component retained-heap measurements. Successful children reported zero displayable windows, source exact and source listener count back to one. There are no new persistent layout caches or cleanup workers. These observations do not prove release of every native resource or sustained many-cycle leak freedom.

The separate NMT/vmmap pair, 1 MiB/two panes, provides attribution controls rather than additional uninstrumented performance samples:

| Metric | Opaque | Alpha 218 |
|---|---:|---:|
| Ready used / committed heap | 21.74 / 66 MiB | 20.61 / 66 MiB |
| Disposed used / committed heap | 20.52 / 66 MiB | 20.44 / 66 MiB |
| NMT committed ready → disposed | 231.46 → 241.86 MiB | 234.31 → 243.68 MiB |
| vmmap physical footprint ready → disposed | 401.0 → 485.9 MiB | 402.1 → 526.4 MiB |
| vmmap physical-footprint peak | 566.9 MiB | 581.0 MiB |
| IOSurface dirty ready → disposed | 57.2 → 48.0 MiB | 47.7 → 48.2 MiB |
| CoreAnimation dirty ready → disposed | 480 → 448 KiB | 480 → 448 KiB |
| Sampled process RSS peak | 625.91 MiB | 632.98 MiB |

NMT ready categories include a 66 MiB committed heap, ~66.6 MiB thread category, ~49.9 MiB GC, ~11–12 MiB metaspace and ~8 MiB code. Reserved class space is not physical RSS. `vmmap` shows ~8 MiB CG image and sizable IOSurface/allocator mappings even after window disposal. It reports physical footprint, which is not the same accounting as `ps` RSS or NMT committed bytes. Category columns are retained in the raw logs; they must not be blindly summed or subtracted to manufacture a leak attribution.

**A 40.5 MiB disposed physical-footprint difference in one diagnostic pair remains unexplained.** Source/capture/JIT/AWT/allocator lifetime and sampling differ, and both children retain substantial graphics allocations. Neither a native leak nor negligible alpha overhead is established. No blur resource cost is known. Longer matched lifecycle/native attribution is a prerequisite to shipping, not grounds for JVM tuning or forced GC in production. All GC requests here are confined to isolated diagnostic children.

No new eager application work exists because no production path references the probe. Full workbench theme-switch, preview, Unicode first-use and background-service interference were not rebenchmarked; previous production evidence remains authoritative. The alpha experiment is ASCII mixed Markdown and does not resolve complex-script first-edit stalls.

For context only, the separate 7C production baseline recorded ordinary typing p95 of 0.869/1.130/2.359 ms at 100 KiB/1 MiB/10 MiB, post-launch RSS 617.50–673.45 MiB, and one first desktop launch missing the 2 s window / 3 s painted-source targets. Production Unicode first-use remained multisecond; HTML layout remained capable of long EDT stalls. Those are different processes and workload boundaries, not matched alpha controls or improvements delivered by this tranche. Keep that release baseline separate from the disposable-window numbers above.

## H. Cross-platform compatibility

| Feature | Portable path | macOS-specific path | Windows/Linux fallback and tests |
|---|---|---|---|
| Transparency | Swing surface painting; device capability checks | Decorated AWT/peer integration unresolved | Opaque default when unavailable; no promise of decorated alpha on other JDK/window managers. Test selection, clipping, scaling, menus, resize and monitor migration separately. |
| Blur | No equivalent standard Swing blur-behind-window API identified | Optional AppKit material backend, not implemented | Opaque or independently qualified transparency; no native effect required for startup. DWM/compositor research is separate, not an approved dependency here. |
| Focus | Existing visibility/layout/focus controls | None necessary | Same restoration/keyboard/split tests on all platforms. |
| Themes | Existing registry/gallery, runtime commit/cancel | Native OS event source requires qualification | Tested OS signals if available; retain manual theme selection when not. |
| Density | Existing layouts and audited spacing tokens | None necessary | Font fallback, localization, zoom/HiDPI, row/popup geometry coverage. |
| Pane emphasis | Existing focus border and accessible state | None necessary | Keyboard/split/high-contrast tests without new rendering engine. |

Native availability must be discovered without loading an unsupported backend on other platforms. Failure must leave opaque editing functional. No Windows/Linux headful appearance experiment, macOS display migration, signed/notarized blur package or native observer lifecycle test ran. Cross-platform feasibility is not cross-platform qualification.

## I. Accessibility and usability

The prototype checked unchanged theme foreground colors against the normal light background composited over black and white. At alpha 218/255 over black, body contrast fell from **11.49:1 to 8.17:1**, muted text **5.15:1 to 3.67:1**, and comment color **4.27:1 to 3.04:1**. At alpha 128, body contrast was **2.91:1** and muted **1.31:1**. The opaque comment baseline already falls below a 4.5:1 small-text criterion; that is not a newly introduced transparency regression. These calculations do not cover all syntax tokens, selections or themes.

Preserving full-opacity foreground ink does not preserve contrast over arbitrary backgrounds. A future design needs a backdrop/tint and opaque-island policy, possibly narrower supported opacity ranges per theme; an arbitrary single minimum alpha is not a universal guarantee. Blur smooths detail but is not proof of adequate contrast. Preserve readable popups, focus rings, caret and selection, including inactive panes and high-contrast settings. No foreground fading is recommended.

Read-only AppKit observation reported Reduce Transparency **false** and Increase Contrast **false** on this host; no system preferences were changed. Future effects must respect both accessibility choices, offer a reliable opaque restore and cleanly handle changes while windows exist. Existing opaque Shed Light is the default, saved preferences win, and unsupported platforms keep ordinary rendering. Dark themes, color-sensitive backgrounds, physical text readability, accessibility tools, input method windows, high-contrast operation and native focus interactions remain test gates rather than inferred successes from screenshots.

## J. Revised 8B–8H roadmap

| Tranche | Boundary / ordering | Acceptance before proceeding |
|---|---|---|
| 8B — Background transparency | **Blocked pending the narrow prerequisite in K.** Do not ship an undecorated/faded substitute. | Decorated-window mechanism plus contrast/coverage/cleanup proof; explicit authorization for production integration. |
| 8C — Native blur | Depends on a safe background/window bridge and separate native approval. No JNI/JNA addition by implication. | True native background-only material, opaque restore, reduced-transparency handling, lifetime/signing/platform and measured compositing gates. |
| 8D — Focus / centering | Independent of blur; extend existing functionality only. | Exact state restoration, focus/split/source safety; no layout redesign. |
| 8E — Theme follow / preview | Reuse gallery; define runtime versus persisted ownership before OS adapter. | Manual preference precedence, cancel rollback, event lifecycle and no repeated UI/layout storms. |
| 8F — Density | Audit bounded chrome tokens first, separate from zoom. | Default geometry byte-for-value equivalent; input/caret/fonts unchanged; localization/HiDPI/hit-target tests. |
| 8G — Active-pane emphasis | Verify existing behavior first; optional changes only for a demonstrated deficiency. | Existing colors/geometry/accessibility retained; zero per-keystroke work. |
| 8H — Combined qualification | Last; tested feature combinations and opaque controls. | Startup, memory/native disposal, typing, preview, Unicode, platform/accessibility and rollback gates; no concealment of 7C blockers. |

These are separate scopes for review, not automatic work authorizations. The lowest-risk implementation opportunity is exact restoration of existing focus mode, or simply verifying existing pane emphasis. Neither requires transparency to be forced through an incompatible window API. Keep 7C's source-layout/JDK compatibility decision separate from appearance work.

## K. Next-tranche handoff — prerequisite only

Transparency is **conditional**, so **no production 8B implementation plan is authorized by this report**. The next appearance task, if approved, is one time-boxed **decorated AWT background-bridge feasibility** investigation, not full transparency implementation:

1. Keep the current decorated Swing window, source model, font and UI. Establish an explicitly supported native association/insertion point compatible with the shipped Java 21 runtime; document ownership and Metal layer behavior. If none is available without private peers, stop with NO-GO.
2. Obtain separate approval before adding a native payload or explicit interop dependency. Existing transitive JNA is not the API design. Keep a one-window, opt-in experiment outside production; no settings or startup hooks.
3. Show actual compositor background through that window while unmodified foreground, title bar, resize, selection/caret, popups and focus work. Demonstrate opaque restore on the same peer, not a replacement editor. Do not infer blur from alpha.
4. Validate one bounded contrast policy using existing theme colors and explicit opaque exceptions. Test black/white/patterned backgrounds and OS accessibility fallback before any app-wide surface migration.
5. Pair opaque/effect processes on the same fixtures and host: startup/input readiness, first and warm Unicode/ASCII paints, resize/scroll, one/two panes, native footprint and repeated disposal. Investigate the diagnostic footprint difference above. No sustained new memory growth or regression beyond a predeclared measurement noise budget; retain the ≤16 ms warm typing target and existing Unicode blocker unchanged.
6. Stop if native integration requires unsupported AWT internals, replacing chrome, changing glyph metrics, new UI frameworks or large memory overhead. Return evidence before asking for production implementation approval.

If preserving native decorations cannot be established, retain opaque appearance. Do not automatically relax the design constraints. No blur-radius slider, replacement title bar, new preference schema or broad component migration is part of this prerequisite.

## L. Regression verification

The new fast Python tests cover protected output locations/repetition bounds, exact bounded UTF-8 fixtures without launching Java, and separation of first-use versus warm/unmeasured paint summaries. Heavy experiments remain explicit commands, outside normal Maven tests. The isolated probe compiled against the frozen packaged production JAR; it is not production code.

Executed commands and final counts are recorded in [verification evidence](performance/appearance-tranche8a/verification.json). They include full Maven, all tooling tests, packaged Mermaid/math/image smoke, the paired matrix/diagnostics/capture controls and whitespace/hash checks. The original matrix's capture failure is retained, not silently relabeled a passing run. Passing regression tests do not prove decorated transparency, native blur, native input accessibility or cross-platform appearance correctness.

Final full Maven result: **1,565 total, 1,560 passed, five existing skips, zero failures/errors**. All **28 tooling tests passed**, including the three new tests. Packaged Mermaid/math/image smoke **PASS**. The five skips are the existing DOS-hidden attribute, opt-in AI performance, isolated fish startup, explicit CTest preset and opt-in native AI acceptance cases; exact names/conditions are retained in verification evidence. Existing theme/zoom, keyboard, split, Markdown, Unicode, persistence and lifecycle tests ran as part of the complete suite. No assertion was weakened.

```sh
source /tmp/shed-tranche2c-run/toolchain.env
export PYTHONDONTWRITEBYTECODE=1
python3 -m unittest discover -s scripts -p 'test_appearance_feasibility.py'
python3 scripts/benchmark-appearance-feasibility.py --jar /tmp/shed-tranche8a-run/production.jar --group matrix --runs 2 --output /tmp/shed-tranche8a-matrix
python3 scripts/benchmark-appearance-feasibility.py --jar /tmp/shed-tranche8a-run/production.jar --group native --output /tmp/shed-tranche8a-native
python3 scripts/benchmark-appearance-feasibility.py --jar /tmp/shed-tranche8a-run/production.jar --group pilot --output /tmp/shed-tranche8a-capture-control
"$MVN" test
python3 -m unittest discover -s scripts -p 'test_*.py'
python3 scripts/test-packaged-markdown.py --jar /tmp/shed-tranche8a-run/production.jar --output /tmp/shed-tranche8a-run/packaged-smoke.json
git diff --check
shasum -a 256 README.md
```

The `native` group means NMT/vmmap diagnostics of the alpha experiment, **not a native-blur prototype**. The initial matrix/native runs use the archived pre-capture-handling probe revision; their hashes remain in raw reports. All scratch output paths must be new and outside the checkout; fixture generation and failed admission tests do not start the application.

## M. Repository compliance and inventory

Added only:

- `src/benchmark/java/shed/AppearanceTransparencyProbe.java` — isolated Swing alpha/capability/lifecycle and diagnostic probe.
- `scripts/benchmark-appearance-feasibility.py` — opt-in bounded fixture/process driver, raw timing/RSS/provenance.
- `scripts/test_appearance_feasibility.py` — three fast tooling tests; no desktop launches in routine tests.
- `docs/APPEARANCE_FEASIBILITY.md` — this report.
- `docs/performance/appearance-tranche8a/` — raw compressed reports/logs, diagnostic outputs, audited screenshots, source revision snapshots, measurements and verification/provenance inventory. [Inventory](performance/appearance-tranche8a/inventory.json) enumerates every evidence file.

All 1,422 pre-existing files, including uncommitted production/test/remediation reports, were hash-compared with the starting inventory. Existing Markdown/Unicode/preview evidence and the rejected prototypes remain unchanged. No source ownership, document lifecycle, preferences, themes, fonts, keymaps or production component was modified. No production dependency, native library, worker, cache, cleanup timer or eager startup path was introduced. Saved user preferences and Shed Light / JetBrains Mono defaults remain intact.

Starting/final README SHA-256: **`48bf87f09ffa570b7fc65d305a15c3228bbc67e906cc992e4fe7108893e7501e`**. README is byte-for-byte unchanged. HEAD and frozen/current JAR identity are unchanged. No commits, pushes, resets, cleans or reverts were performed. This feasibility tranche stops here; 8B and all later implementation remain unstarted.
