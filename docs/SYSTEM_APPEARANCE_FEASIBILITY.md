# Tranche 8E.1 — System appearance notification feasibility

## A. Event mechanisms and evidence

**Two credible paths were investigated: the existing Java 21/AWT integration, and AppKit `NSApplication.effectiveAppearance` observation.** Existing bundled JNA was also inspected as the possible transport for the latter, not as a third theme mechanism. No undocumented distributed-notification name, defaults polling loop or guessed property was accepted as an OS event API.

The decisive result is that the supported AppKit property describes the **application's effective appearance**, which can differ from the global system appearance. In Shed's ordinary Java 21 launch, OpenJDK explicitly sets the native application appearance to Aqua. Its effective appearance consequently remains Aqua when macOS changes to dark. A native observer can receive an invalidation with the same Aqua value; that is not an accurate global light/dark state.

The exact upstream Java 21.0.13 code in `NSApplicationAWT.finishLaunching` reads `apple.awt.application.appearance` once. If it is not `system`, it selects Aqua by default, or an AppKit-recognized named appearance. With `system`, it leaves native appearance inheritance enabled. It does not implement a Java appearance listener or reread the property for each window. The actual Temurin runtime controls confirmed these distinctions. [Pinned OpenJDK implementation](https://github.com/openjdk/jdk21u/blob/jdk-21.0.13-ga/src/java.desktop/macosx/native/libosxapp/NSApplicationAWT.m).

Apple documents both this application-versus-system distinction and observation of `effectiveAppearance` for non-view code. The KVO experiment used those public AppKit APIs without method replacement, private notifications or modifications to AWT-owned objects. [Application appearance](https://developer.apple.com/documentation/appkit/nsapplication/appearance), [supporting Dark Mode](https://developer.apple.com/documentation/uikit/supporting-dark-mode-in-your-interface).

### Actual runtime experiment

A single bounded run made **eight real macOS transitions**, including repeated light→dark→light cycles. These were finite test inputs driven through System Events, not a shell-based detector or production polling mechanism. Original dark mode was `false`; the automatic-appearance preference key was absent. Both were restored and reread afterward: **`false` and absent**, respectively. Maximum simultaneous experiment processes: one native helper and two Java controls.

| Control | Real behavior |
|---|---|
| Standalone AppKit observer, inherited appearance | Six expected OS-driven KVO events reported the correct light/dark value across repeated transitions and re-registration. Initial KVO callbacks and local pinning callbacks are recorded separately. |
| AppKit observer explicitly pinned to Aqua, matching the JDK policy | During a real system dark transition, its effective value remained Aqua. A redundant Aqua callback occurred; a callback alone did not establish the global theme. |
| Java 21 with the ordinary unset startup property | Initial light and initial dark processes both reported native explicit/effective Aqua. The light-started process stayed Aqua through subsequent OS transitions. |
| Java 21 with `-Dapple.awt.application.appearance=system` | Initial light and initial dark processes inherited the matching native appearance; subsequent native reads tracked OS transitions. These reads were commanded diagnostic queries, **not Java notification delivery**. |
| Late `System.setProperty(..., "system")` | The Java string changed, but native explicit/effective Aqua remained. Closing and reopening the control frame did not unpin the native application. |
| Existing public Java listener controls | Observed property events were frame `font` and `foreground` setup events, including reopening. No appearance-bearing Java property event was observed for the tested OS transitions. The named appearance desktop property remained `null`. |

The Java probe subscribes to the literal property being investigated, published desktop-property names when available, a font-hint control, `UIManager` and its frame. It does not claim exhaustive observation of every internal AWT event. Source inspection independently found no light/dark desktop property among the macOS toolkit initialization entries. The native system-color path updates Java system colors; it does not expose a supported light/dark event contract that Shed can consume. [Toolkit source](https://github.com/openjdk/jdk21u/blob/jdk-21.0.13-ga/src/java.desktop/macosx/classes/sun/lwawt/macosx/LWCToolkit.java), [native color path](https://github.com/openjdk/jdk21u/blob/jdk-21.0.13-ga/src/java.desktop/macosx/native/libawt_lwawt/awt/CSystemColors.m), [public desktop listeners](https://docs.oracle.com/en/java/javase/21/docs/api/java.desktop/java/awt/desktop/SystemEventListener.html).

The native controls and all **21 Java native-state queries** ran their AppKit operations on the main AppKit thread. The read-only Java diagnostic uses already-bundled JNA and libdispatch to query existing objects there. That proves a bounded read path, not an owned Java KVO subscription. No Java-side Objective-C observer class, runtime class injection, native shim or production callback bridge was implemented.

See [runtime evidence](performance/appearance-tranche8e1/summary.json), [raw runtime report](performance/appearance-tranche8e1/runtime-report.json.gz) and [versioned source audit](performance/appearance-tranche8e1/jdk-source-audit.json).

## B. Verdict

**NO-GO — Automatic following is deferred. Tranche 8E remains limited to its verified previews.**

AppKit observation itself works in the standalone inherited-appearance control. That is not sufficient for the requested production integration. Under Shed's existing native appearance policy it observes Aqua rather than the actual global dark/light choice. Unpinning the AWT-owned application with a startup property or native appearance mutation would change the native appearance behavior merely to enable observation, which this tranche explicitly forbids. The `system` control is diagnostic evidence, not an accepted production change.

The existing JNA facilities do not already provide the owned AppKit observer required here. A new callback/lifetime bridge would still need proof of native ownership, exception containment, disposal and EDT delivery; it would not by itself solve the pinned-appearance state problem. No such bridge was built after the gate failed. This is a constrained architectural decision, **not a claim that macOS lacks appearance notifications or that all future Java/native implementations are impossible**.

No speculative automatic configuration or UI was added. Do not interpret successful probe exit as a GO: it means the bounded experiment and restoration completed successfully.

## C. Architecture and notification ownership

Production ownership remains exactly as delivered in 8E: `ConfigManager` owns authoritative settings and a transient preview lease; `ThemeGalleryDialog` owns its live preview lifecycle; existing theme application performs workbench refresh. The single-main-window preview guard remains unchanged.

The isolated native helper owns one `AppearanceObserver` on its main thread. Registration is idempotent; it unregisters on command and exit. Callback count remained **7→7** across a real transition while unregistered. Re-registration supplied its expected initial callback; registering twice did not add another subscription. Later real transitions still delivered correct inherited appearances. All five measured children exited with code zero.

The Java controls remove their frame, `UIManager` and toolkit listeners before disposal and re-registration. Each light-started control reopened once; each dark-started control opened once. Native read callbacks are retained in a diagnostic-only bounded list until that process exits so callback memory cannot be freed while a native invocation returns. The request limit is 64. This is deliberately not proposed as a production observer design. There is no new listener, worker, native hook or polling loop in Shed.

## D. Files changed

Only new opt-in diagnostic/tooling/report files were authored:

| File | Purpose |
|---|---|
| `src/benchmark/native/SystemAppearanceObserver.m` | Disposable public-AppKit KVO control, inherited/pinned states, registration/removal and JSON events. Never linked or packaged into Shed. |
| `src/benchmark/java/shed/SystemAppearanceAwtProbe.java` | Java 21 default/system controls, public property listeners, read-only main-thread native queries, late-property and frame-reopening controls. |
| `scripts/qualify-system-appearance.py` | Explicit opt-in bounded experiment, new scratch output directory, real transitions, finally-based preference restoration, child ownership and evidence capture. |
| `scripts/test_system_appearance_tooling.py` | Four fast tooling tests: explicit transition opt-in, checkout/existing-output protection, plan-only operation without native builds or OS changes, and failed-readiness cleanup of an owned child. |
| `docs/SYSTEM_APPEARANCE_FEASIBILITY.md` | This A–N report. |
| `docs/performance/appearance-tranche8e1/` | Baseline hashes, source audit, raw runtime/test logs, summaries, artifact identities and verification inventory. |

No production Java, theme manager, gallery, default, preference schema, build/packaging script, dependency, font asset or prior report was modified. The exact evidence inventory is listed in [inventory.json](performance/appearance-tranche8e1/inventory.json).

## E. Manual/automatic precedence

There is no new automatic state. Manual theme selection and existing runtime/project/session preference precedence are unchanged. Follow System remains unavailable; light/dark automatic preference keys were not added. The tested `system` JVM flag is not set by Shed, its launcher or its packaging as part of this tranche.

## F. Preview rollback

The existing preview remains the highest-priority transient palette. Cancellation resolves the current authoritative manual configuration, including intervening settings changes, rather than restoring stale colors. Apply and Save, failed-save rollback, Escape/Close, owner disposal and second-main-window containment retain their tested 8E behavior. System-event-during-preview tests are **not applicable to a delivered automatic feature**, because no such feature or subscription was installed. Mocking that missing capability would not complete the native gate.

## G. Saved preference compatibility

Shed Light and bundled JetBrains Mono remain the fresh-install defaults; saved manual themes and font preferences continue through the existing configuration loader. No new persisted appearance key, automatic mode migration or preview write was introduced. The OS experiment restored only the original dark-mode value and automatic-appearance key presence/value; it did not restore an entire preferences domain or touch a personal Shed configuration. Scratch Java controls used disposable homes.

## H. Native runtime and cross-platform verification

Runtime host: **Mac15,12 / Apple M3, eight CPUs, 16 GiB RAM; macOS 26.5.2 arm64; Temurin OpenJDK 21.0.13+9**. Native probe built with installed Xcode clang/AppKit and ARC. Both built-in Retina and external 1920×1080 displays were connected; this was not a multi-monitor/HiDPI rendering qualification. Java controls used `-Xms64m -Xmx256m`; their artifact SHA-256 was **`64c07ac73d9663d39d79450fdce800253134d518e0896fe4283cd1bcb554fc80`**. Host load was approximately **4.52 / 5.91 / 5.23** and swap was already in use. Full host/runtime/compiler metadata is retained in [environment.json](performance/appearance-tranche8e1/environment.json).

The successful runtime run covered initial light, initial dark, both transition directions, repeated events, removal, duplicate registration, re-registration, Java frame reopening and child shutdown. It did not qualify a production multi-window appearance adapter, native crash recovery, arbitrary macOS/JDK versions, or every high-contrast appearance name.

Windows/Linux automatic detection was neither implemented nor claimed. Production manual selection remains its existing fallback; those platforms were not executed here. Unsupported platforms are rejected by the explicitly invoked macOS diagnostic without changing production startup.

## I. Performance and resource observations

These are **feasibility-control measurements**, not automatic-theme product performance:

| Boundary | Observed result | Meaning / limit |
|---|---:|---|
| Standalone native process launch→ready | 182.72 ms, one sample | Includes process/AppKit initialization. Not Shed startup. |
| Native KVO registration including initial callback/logging | 0.151 ms initial; 0.018 ms re-registration | Supported standalone observer cost; not Java adapter registration. |
| OS mutation request→native KVO | Six samples; 118.18–214.75 ms; median 175.09 ms | Starts before the AppleScript request. Includes OS control/delivery; **not** notification→completed-paint latency. |
| Java control launch→ready | Four processes; 1.65–2.10 s | Includes small JFrame, JNA setup and initial native read. Not a Shed startup comparison. |
| Initial diagnostic JNA native read | 188.10–206.89 ms | Includes first native-library/callback/dispatch setup. Kept separate from warm reads. |
| Later diagnostic reads | Most 0.36–0.77 ms; reopening 7.60/9.63 ms; one **63.13 ms** outlier | Commanded reads only. No polling-based implementation is proposed. |
| Java used / committed heap at status checkpoints | Approximately 15.03 / 66 MiB | No requested GC: used heap is **not retained-heap qualification**. |
| Java process RSS at sampled checkpoints | Approximately 551–562.5 MiB | Small controls, not Shed idle memory or an attributable adapter overhead. |
| Standalone native helper RSS at checkpoints | Approximately 30–31.5 MiB | Separate native process; cannot be subtracted from Java RSS as bridge cost. |

RSS was sampled at experiment checkpoints, not continuously. These values are neither a definitive peak nor settled-idle memory. No new NMT, native allocation attribution, heap dump or forced-GC retention qualification was performed. Listener teardown was verified through callback counts, reopening and clean child exit, not inferred solely from RSS.

No production adapter registration, automatic notification→paint, automatic theme-switch EDT cost, typing during automatic transitions, or post-adapter-disposal retained heap exists to measure. Those gates remain **unqualified**, rather than receiving fabricated passing values. Production startup and source input were not changed. The prior **1.19 ms 1 MiB warm typing p95** and **up to 148 ms first theme switch** remain historical 8E evidence and were not remeasured or reclassified here. Existing Unicode first-edit, stock HTML layout and native-memory limitations remain open.

## J. Regression verification

All checks used the existing toolchain environment in `/tmp/shed-tranche2c-run/toolchain.env`. No production changes occurred, but the full suite was practical and was rerun.

```sh
"$MVN" test
# 1,592 total; 1,587 passed; five existing skips; zero failures/errors

"$MVN" -q '-Dtest=ConfigManagerTest,ThemePreviewTest,TexteditorSwingIntegrationTest#liveThemePreview*+livePreviewCancellation*+galleryAcceptance*+openingAnotherWorkbench*+savingSettingsDuringPreview*+gallerySaveFailure*+themeGallerySaves*' test
# 70 focused tests passed; zero failures/errors/skips

PYTHONDONTWRITEBYTECODE=1 python3 -m unittest discover -s scripts -p 'test_*.py'
# 41 passed, including four new diagnostic-tool admission/lifecycle tests

python3 scripts/test-packaged-markdown.py --jar target/shed-2.0.0.jar --output /tmp/shed-tranche8e1-run/packaged-smoke.json
# packaged Mermaid/math/image smoke PASS

python3 scripts/qualify-system-appearance.py --execute-transitions --output /tmp/shed-tranche8e1-run/runtime
# eight real transitions; five children exited zero; original OS preferences restored

git diff --check
# PASS
```

The full suite includes existing 8D focus/centering, window/lifecycle, Swing, keymap, Markdown/preview, Unicode, save/recovery and buffer regressions. The five existing skips remain DOS-hidden attribute support, opt-in AI performance, unavailable fish, unavailable CMake/CTest/CPack and opt-in native AI acceptance. Exact names and reasons are retained in [verification.json](performance/appearance-tranche8e1/verification.json).

Diagnostic setup issues were corrected before the real-transition measurement: the first OpenJDK source URL used the wrong directory and returned 404; the JBS page returned 403, so the versioned source and actual runtime were used; the first Java probe compile used nonexistent JNA `Function.invokeByte` and was corrected to the existing `Function.invoke(byte.class, ...)` API. The corrected probes compiled and all measured children exited zero. Setup logs are retained. No production change, weakened assertion, test sleep or mocked OS event was used to obtain a GO.

After the runtime run, tooling-only safeguards were added for failed-readiness child cleanup, stripping ambient JVM override variables and checking child exit status. No ambient overrides were present in the measured run; compiled native/Java probe sources remain hash-identical to that run. Repeating global OS transitions merely to retest failure-path cleanup was unnecessary; final tooling checks passed, including a real disposable Python child whose startup-readiness failure exercises owned-process cleanup (not a mocked appearance event).

## K. UI/UX and defaults

Production design, components, native window appearance policy/decorations, layouts, typography, themes, menus, palette, focus/centering, IDE/Markdown/LSP behavior and Plain/Vim/Emacs routes remain unchanged. Shed Light, bundled JetBrains Mono and saved-preference precedence are preserved by unchanged production files and regression tests. The only extra windows were disposable diagnostic controls. No settings, controls, framework, appearance engine, startup hook, permanent worker, native library dependency, transparency, blur, density or active-pane implementation was added to Shed.

## L. README and repository preservation

Before and after `README.md` SHA-256:

```text
48bf87f09ffa570b7fc65d305a15c3228bbc67e906cc992e4fe7108893e7501e
48bf87f09ffa570b7fc65d305a15c3228bbc67e906cc992e4fe7108893e7501e
```

README was not modified. Entry HEAD was `bc2438d4b5881a451f64b28c23846eb99b160190`, with clean `git status --short`. File-level before/after hashes, final status and HEAD are in [preservation.json](performance/appearance-tranche8e1/preservation.json). No existing source or evidence file was overwritten, reset, cleaned, reverted or discarded. The agent created no commit or push; any external HEAD advancement is preserved and recorded separately.

## M. Remaining limitations

Automatic theme following and separate automatic light/dark preferences remain unavailable. AppKit KVO in an inherited native application must not be advertised as working automatic following inside the pinned Java application. No claim is made that mocked state changes, native reads, a changed property string or title-bar inheritance complete the feature.

Independent multi-main-window previews remain guarded as in 8E. Significant explicit theme-switch costs, Unicode first-edit stalls, the 51 known Unicode corpus mismatches, HTML-preview stalls, native-memory attribution and incomplete platform qualification are unchanged. Notification→paint and automatic-mode preference/preview races were not tested because no automatic adapter passed the gate.

## N. Readiness for 8F

**Proceed to a separately authorized Tranche 8F rather than another speculative native-theme investigation.** Automatic following stays deferred; existing manual themes and verified previews remain usable. No 8E.2 bridge project is recommended by this final bounded attempt.

The minimum 8F prerequisite remains an audit of shared component spacing/dimension inputs and the existing `UiZoom`/`WorkbenchStyle.px` seam. Density must preserve today's default dimensions, remain separate from editor font zoom, and avoid changing font metrics or adding source-layout invalidation/caches merely to alter chrome spacing. Carry forward focus/center geometry, split/caret, source preservation, typing and memory gates. No density settings or implementation were started here.
