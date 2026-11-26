# Tranche 8E — Theme preview and system appearance

## A. Summary and verdict

**The verified preview subset is delivered. Tranche 8E as a whole remains incomplete.** The existing Theme Gallery now offers an explicit **Preview in App / Cancel Preview** action. Once enabled, selecting another registered theme previews it in the existing workbench. Closing the gallery, Escape, cancelling, disposing its owner, or opening a second Shed workbench ends the temporary override. **Apply and Save** commits through the existing configuration writer. Browsing sample palettes alone retains its previous inexpensive behavior.

Automatic system following and preferred automatic light/dark themes were **not implemented**. No sufficiently verified live appearance-notification adapter was found in the existing application/JDK integration. The request explicitly permits a safe preview-only subset in this situation. There are no inactive auto-mode settings or controls implying that this capability works. Manual appearance remains the fallback on every platform.

Live workbench preview supports **one main Shed window**, including its editor splits and owned dialogs. With multiple main windows it rejects without changing the palette, and the existing sample preview remains available. Independent multi-window live preview is not claimed. Theme switching can still cost tens to over one hundred milliseconds; the implementation does not replace the existing theme propagation/layout path.

Evidence: [index](performance/appearance-tranche8e/INDEX.md), [verification](performance/appearance-tranche8e/verification.json), [measurements](performance/appearance-tranche8e/summary.json).

Subsequent [Open VSX theme discovery/import](OPEN_VSX_THEMES.md) adds offline imported palettes to this same gallery and preview lease. Its compatibility subset, installation safeguards and qualification are documented separately; automatic system appearance remains deferred.

## B. Existing infrastructure reuse

The audit found these existing owners and paths:

- `ConfigManager.config` and its typed runtime settings layer own the current authoritative configuration. `persistedConfig` owns the material serialized by the existing writer. Session/project overrides use these established layers.
- `ConfigManager` already registers all bundled palettes, aliases, color roles and user color overrides. No additional registry, palette parser or theme engine was needed.
- `SessionConfigController.setThemeFromCommand` performs existing manual selection. `persistCurrentConfig` is the existing full-runtime-configuration save convention used by the gallery.
- `ThemeGalleryDialog` already provides the palette list, sample code, swatches, Apply and Save, Close, Enter/double-click acceptance and Escape disposal. Only one additional button was added to this existing action row; no alternate picker or settings window was built.
- `SyntaxUiController.applyThemeColors` and `EditorUiController.applyUiTheme` already refresh workbench colors, editor foreground/caret/selection, syntax colors, tool components and existing Markdown previews. They remain unchanged.

There is an important existing boundary: `EditorUiController` visits other displayable windows, while `WorkbenchStyle` installs and paints some colors through process-wide `UIManager` defaults. Per-window `ConfigManager` instances alone therefore do not establish independent visual theme ownership. This tranche contains live preview to one main workbench rather than attempting a broad styling-engine refactor.

## C. File inventory

| File | Purpose |
|---|---|
| `src/main/java/shed/ConfigManager.java` | Small transient palette lease, authoritative-theme accessor, checked acceptance and restoration of in-memory preferences on save failure. |
| `src/main/java/shed/ThemeGalleryDialog.java` | Existing-gallery preview/cancel controls, single gallery per owner, guarded live preview, acceptance feedback and disposal cleanup. |
| `src/main/java/shed/SessionConfigController.java` | Settings-buffer reload preserves the authoritative theme instead of capturing a displayed preview as a runtime preference. |
| `src/main/java/shed/Texteditor.java` | Cancel an active preview before constructing another main workbench; the normal no-preview path is one weak-reference lookup. |
| `src/test/java/shed/ConfigManagerTest.java` | Five deterministic overlay, persistence, current-authority, stale-owner and failed-save regressions. |
| `src/test/java/shed/TexteditorSwingIntegrationTest.java` | Six headful preview/source/keymap/split/focus/font/reload/acceptance/disposal/multi-window regressions. |
| `src/benchmark/java/shed/ThemePreviewPerformanceProbe.java` | Explicitly invoked packaged-artifact probe, compatible with the old JAR through reflection. Not part of production startup or routine tests. |
| `scripts/benchmark-theme-preview.py` | Bounded paired-process orchestration, scratch homes, exact fixtures, timeouts, host/RSS records and artifact identities. |
| `scripts/test_theme_preview_tooling.py` | Three fast output-safety, fixture and bounded-matrix checks. |
| This report and `docs/performance/appearance-tranche8e/` | State contract, findings, raw results, source patch, verification and preservation evidence. |

The evidence [inventory](performance/appearance-tranche8e/inventory.json) enumerates individual evidence files and hashes. Existing 8D working changes were present at entry and were not authored or altered by this tranche.

## D. State ownership and precedence

The effective palette resolves in this order:

1. The current temporary preview lease, if any.
2. The current authoritative runtime `theme`, including established session/project/preference precedence.
3. Existing palette alias resolution and Shed Light fallback.

Existing explicit color overrides continue to win for their corresponding color roles. This is palette resolution within the existing manager, not a second configuration format.

A lease contains one theme ID and its owning manager. It is separate from both serializable settings maps. `getThemeId()` describes the displayed palette; `getAuthoritativeThemeId()` describes the configured palette. Reading `get("theme")`, saving unrelated settings, capturing session configuration and saving the whole current configuration cannot acquire the temporary ID from this overlay. Settings-buffer reload explicitly uses the authoritative accessor.

Only the current lease may change or accept its selection. Unknown IDs, duplicate owners and stale leases reject. Closing a stale lease cannot clear a newer lease. Closing the current lease discards the override and resolves the manager's **current** configuration; it does not restore a saved color snapshot or undo intervening font, color or manual-theme changes. All production lease operations occur in existing EDT gallery/window routes. There is no asynchronous mutation, timer or background appearance job.

Automatic mode is absent, so no precedence or acceptance semantics for a working automatic mode are claimed. A future adapter must extend this one authority contract; it must not independently persist OS-event selections.

## E. System appearance handling

No existing Shed consumer of live OS light/dark appearance notifications was found. A read-only scan of 217 relevant Java desktop Mac/Aqua/platform source files in the installed JDK source archive found no matches for the inspected appearance notification/property names. That archive excludes native sources: this is bounded investigation evidence, **not proof that all native interop is impossible**. See the [audit](performance/appearance-tranche8e/system-appearance-audit.json).

Java 21's documented `SystemEventListener` subinterfaces cover application/session/sleep events and do not expose an appearance-change listener. `Toolkit` supports named desktop-property listeners, but this does not establish that an arbitrary guessed property is a supported, current light/dark signal. No startup property, cached initial value, `defaults` invocation or undocumented distributed-notification name was treated as live notification support. [Java desktop event API](https://docs.oracle.com/en/java/javase/21/docs/api/java.desktop/java/awt/desktop/SystemEventListener.html), [Toolkit properties](https://docs.oracle.com/en/java/javase/21/docs/api/java.desktop/java/awt/Toolkit.html#getDesktopProperty(java.lang.String)).

AppKit exposes `effectiveAppearance` on its own appearance-customization objects. Mapping that native state and its changes into AWT would require a separately verified bridge with main-thread, callback and disposal ownership; that bridge does not currently exist in the inspected Shed paths. No native runtime code, new dependency, OS notification registration or polling service was added. [AppKit effective appearance](https://developer.apple.com/documentation/appkit/nsappearancecustomization/effectiveappearance).

**Automatic switching is unavailable in this delivery on macOS, Windows and Linux.** Manual selection continues unchanged. Windows/Linux execution was not available for qualification here; the delivered preview uses portable Swing APIs but cross-platform behavior remains unverified. No OS appearance or accessibility setting was changed.

## F. Preview lifecycle and cross-window policy

| Event | Behavior |
|---|---|
| Open gallery / browse samples | Existing sample code/swatches only; no workbench or preference change. |
| Preview in App | Acquire the palette lease and reuse existing theme application. |
| Select A, then B during preview | Change that lease, then refresh the existing components; identical palette selections avoid another application. |
| Cancel, Close, Escape or disposal | Release the lease and refresh the current authoritative theme if the owner is still displayable and the palette differs. |
| Manual theme or settings change during preview | Update existing authoritative settings; the preview stays on top until ended. Cancellation resolves the latest settings. |
| Apply and Save | Commit the selected registered theme through `persistCurrentConfig`, end the preview and display success only after the writer succeeds. This retains the existing convention of saving other current runtime settings as well. |
| Writer throws `IOException` | Restore the prior in-memory theme and persisted map, retain an existing live preview so it can still be cancelled, and show the existing-style save error. A non-preview acceptance failure releases its temporary lease. |
| Reopen gallery | Bring the current displayable gallery forward rather than registering another live transaction. |
| Additional main workbench construction | Cancel the live preview before the new workbench configures its Swing appearance. |
| Multiple main workbenches already displayable | Reject live workbench preview with feedback; retain sample browsing and existing manual behavior. |
| Owner disposal | Release preview state through the owned dialog's disposal path; no retained global strong reference to the owner or gallery. |

The one process-wide gallery reference is **weak** and exists only to end an active preview before another main window initializes. It is not another theme authority. There are no delayed restoration callbacks that could install a theme after disposal. Existing manual cross-window theme propagation was not redesigned.

## G. Configuration compatibility

No new persisted keys, mode migration or default changes were introduced. Existing theme aliases, missing/invalid theme fallback, project/session configuration, explicit palette overrides and saved font preferences retain their existing mechanisms. Fresh installations still use Shed Light and JetBrains Mono; automatic following is not imposed on existing installations.

Tests verify that an unrelated save during a Dracula preview reloads as Shed Light, that a subsequent manual choice survives cancellation, and that an explicit acceptance reloads as the accepted theme. A blocked configuration path produces a reported error and leaves the preview cancellable without promoting its theme to authority. The underlying configuration writer itself was not replaced or made transactional on disk; its existing I/O/durability limitations are unchanged. This change restores in-memory state after a reported save failure and does not claim recovery from arbitrary partial filesystem writes.

## H. Appearance and source correctness

The existing theme application path reaches source editor foreground, selection/caret colors, syntax colors, pane focus borders, header/tabs/status/tools/pickers and already-open Markdown previews. It does not create a preview renderer when none is open. No hardcoded component palette was added.

New headful tests verify two splits, active focus mode, directed selection, unchanged editor font, Plain/Vim/Emacs profile state, unchanged exact Unicode source/snapshot/revision/dirty state/Undo availability, and **zero document events** during preview/cancellation. Separate tests cover intervening font changes, settings reload, acceptance, close/disposal, failed save and second-window containment. The current source file remains byte-for-byte unchanged. [Inference] The existing LSP document-listener route therefore receives no text change from these tested theme operations; a live external language server was not independently instrumented for this tranche.

The full suite covers previous focus/centering, Unicode, buffer lifecycle, key routing, Markdown commands/navigation, highlighting, preview, persistence and recovery. Passing those tests is regression evidence, not new qualification of IME candidates, every platform picker, assistive technology, every custom color combination, or preview during arbitrary project replacement. No new input handling or source transaction path was added.

## I. Performance and memory

### Method and boundaries

Host: Mac15,12, Apple M3, eight CPUs, 16 GiB RAM; macOS 26.5.2 arm64; Temurin OpenJDK 21.0.13+9. Built-in Retina and external 1920×1080 displays were attached; this is not a two-monitor/HiDPI qualification. JVM: `-Xms64m -Xmx512m`, headful. Scratch homes explicitly select Shed Light, Plain mode and no session restoration. Real user preferences were not read or written by the probe. Host load, memory pressure, swap and display records are in the raw report; the host was not quiescent and existing swap usage was present.

The frozen pre-change packaged JAR is SHA-256 `d448ccae866a592c5e1aee6cbea352c7289cb0fb1780ef8ae65896bec3578dbc`. The measured candidate JAR is `64c07ac73d9663d39d79450fdce800253134d518e0896fe4283cd1bcb554fc80`.

Eight bounded fresh processes compare existing manual switching/restoration against candidate live preview/cancellation: 4 KiB, 100 KiB with two splits and focus active, 1 MiB, and 4 KiB with the existing HTML preview open. Pair order alternates. Both sides perform the same numbers of switches, restoration operations, acceptance/saves and gallery closures. Each process records one first switch separately, nine repeat switches/restorations, three A→B→cancel sequences, one accept/save, and nine closed galleries. There are 51 typing samples before and 51 after, with the first input separate from 50 warm samples in each phase. Document revision/snapshot/dirty identity and document listener count are checked around appearance operations.

**Typing** uses the production `PerfService` input-to-completed-source-paint metric. **Theme operation** numbers below include synchronous theme application, validation and forced paint of the existing root and gallery, with those components separately recorded. They are not native compositor presentation times, complete queued-EDT occupancy or background highlight/HTML convergence times. New native profiling was not performed. EDT allocation totals are allocated bytes, not retained heap. Requested GC is confined to the disposable diagnostic processes.

An exploratory run used fewer acceptance operations in its baseline. It is preserved separately and excluded from the final paired memory/latency table; the final run corrects that mismatch. Final results are noisy bounded observations, not statistically strong startup or universal theme-performance guarantees.

### Final paired observations

| Workload | First switch, baseline / preview ms | First restore/cancel, baseline / preview ms | Warm typing after operations p95, baseline / preview ms |
|---|---:|---:|---:|
| 4 KiB | 48.26 / 74.29 | 36.73 / 59.49 | 1.97 / 2.67 |
| 100 KiB, splits + focus | 69.67 / **148.10** | 50.09 / **101.97** | 4.39 / **7.40** |
| 1 MiB | 68.82 / 59.42 | 93.75 / 51.33 | 2.20 / **1.19** |
| 4 KiB, HTML preview open | 106.58 / **115.68** | 76.08 / 82.33 | 2.97 / **4.71** |

All measured warm typing p95 values remain below 16 ms. Candidate before/after typing p95: 4 KiB **6.12→2.67 ms**, 100 KiB splits/focus **7.52→7.40 ms**, 1 MiB **1.95→1.19 ms**, HTML-open case **4.53→4.71 ms**. There is no within-process material typing degradation demonstrated by these samples, but pair variation and first-switch costs remain visible. Do not interpret the faster 1 MiB result as a new source-view optimization.

Candidate repeat-switch p95 (nine samples): **75.62, 51.59, 34.99 and 83.96 ms**, respectively. Explicit accept/save plus forced paint: **68.66, 30.44, 28.34 and 41.32 ms**. Rapid selections still invoke the existing whole-workbench refresh; this is an opt-in explicit operation, not an advertised sub-frame interaction. The split/focus first switch is a significant **148 ms** EDT interval and is not hidden in warmup. The earlier exploratory first switch for that same case was 39.64 ms; host/first-use variability prevents a precise causal slowdown claim from a single pair. There is no automatic OS-event stream to amplify this work into a notification storm.

Fresh-process probe launch-to-loaded-editor observations were **2.03–3.46 s baseline / 1.98–3.56 s candidate**. This includes the probe's constructor/file-load/staged-font readiness boundary, not launch-to-native-visible-window, first accepted input, or true cold-cache startup. There is no defensible statistically significant startup conclusion from one process per case. Production startup adds no worker, detection query or eager renderer. Its new inactive-preview check does initialize the gallery class and its small static swatch/weak-reference metadata on first use; it does not construct or show a dialog. No claim of literally zero added startup initialization is made.

### Memory and disposal

| Workload | Post-cycle requested-GC used heap, baseline / preview MiB | Sampled peak RSS, baseline / preview MiB |
|---|---:|---:|
| 4 KiB | 13.38 / 13.36 | 668.58 / 669.94 |
| 100 KiB, splits + focus | 14.33 / 14.35 | 690.80 / 691.14 |
| 1 MiB | 24.45 / 24.39 | 751.27 / **797.00** |
| 4 KiB, HTML preview open | 49.37 / 49.38 | 955.41 / 960.52 |

The 1 MiB candidate's no-GC used heap reached **181.26 MiB**, then **24.39 MiB** after the requested-GC control. The baseline values were **113.25→24.45 MiB**. Committed heap and collection timing differ; this is not evidence of a retained 68 MiB theme cache. It also does not establish that process RSS is low or fully attributable. The candidate's higher sampled peak RSS is retained in the evidence, not dismissed as a proven non-regression. RSS is sampled every 250 ms, may miss peaks and is not settled idle RSS. Some final samples observe process termination; zero values are not meaningful disposal measurements.

All **36 candidate closed galleries** and all **36 baseline closed galleries** became unreachable across the bounded five-GC/EDT-drain controls. Document listener counts did not accumulate. The new state retains one lease and one weak gallery reference, independent of document length. Existing theme application still allocates approximately **4 MiB per source-only switch** and **18 MiB with the small HTML preview open** on this probe's EDT; these are not new document-sized caches. Exact distributions, used/committed heap, thread counts and after-disposal observations are retained in the summary/raw report.

The probe intentionally keeps its local buffer/snapshot variables through the main routine, so its post-window-disposal heap is **not a new buffer-reclamation qualification**. It does verify gallery reachability and source/listener invariants; prior 7B.2 buffer-release evidence remains separate. No promise is made that all native graphics resources were attributed or released by these Java heap controls.

Large wrapped sources, 1 MiB complex-script first-use, large HTML preview refresh and low-system-RAM behavior were not requalified. Existing Unicode multisecond first-edit stalls, HTML-layout stalls, 51 Unicode corpus mismatches and native-memory uncertainty remain open.

## J. Regression results

Toolchain: `source /tmp/shed-tranche2c-run/toolchain.env` supplies the existing Java 21 and Maven 3.9.11 paths. Exact retained commands and skips are in [verification.json](performance/appearance-tranche8e/verification.json).

```sh
"$MVN" test
# 1,592 total; 1,587 passed; five existing skips; zero failures/errors

"$MVN" -q '-Dtest=ConfigManagerTest,ThemePreviewTest,TexteditorSwingIntegrationTest#liveThemePreview*+livePreviewCancellation*+galleryAcceptance*+openingAnotherWorkbench*+savingSettingsDuringPreview*+gallerySaveFailure*+themeGallerySaves*' test
# 70 passed; zero failures/errors/skips

PYTHONDONTWRITEBYTECODE=1 python3 -m unittest discover -s scripts -p 'test_*.py'
# 37 passed

"$MVN" -q -DskipTests package
python3 scripts/test-packaged-markdown.py --jar /tmp/shed-tranche8e-run/candidate.jar --output /tmp/shed-tranche8e-run/packaged-smoke.json
# packaged Mermaid/math/image smoke PASS

python3 scripts/benchmark-theme-preview.py --jar /tmp/shed-tranche8e-run/candidate.jar --baseline-jar /tmp/shed-tranche8e-run/baseline.jar --output /tmp/shed-tranche8e-run/paired-final
# eight bounded child processes completed successfully

git diff --check
# PASS
```

The five skips remain DOS-hidden attributes unavailable on this host; opt-in AI benchmark; unavailable fish; unavailable CMake/CTest/CPack; opt-in native AI acceptance. No assertion was removed or weakened. Early new-test runs failed two ConfigManager assertions and one Swing assertion because the fixture used nonexistent `gruvbox` instead of registered `gruvbox-dark`; corrected fixtures passed, and the original failure logs are preserved. No production change was used to hide those failures.

## K. UI/UX compliance

Existing colors, color roles, typography, default themes/fonts, layout, source editor, preview, menus, command palette, pickers, focus/centering and keybindings remain their established components. Only the approved preview/cancel button and related feedback were added to the existing gallery. Enter/double-click acceptance, Close and Escape retain their existing routes. No global shortcut, new dialog, automatic-follow control, background service, native hook, dependency or alternate rendering system was introduced.

No theme operation edits authoritative source. No source-view, highlighting, Markdown parser, media-service, backlink-index or document-ownership implementation was changed.

## L. README and repository safety

Before and after SHA-256:

```text
48bf87f09ffa570b7fc65d305a15c3228bbc67e906cc992e4fe7108893e7501e
48bf87f09ffa570b7fc65d305a15c3228bbc67e906cc992e4fe7108893e7501e
```

`README.md` was not modified. Starting HEAD was `b1aacc16fe3cc364e1d1fcc7bb76e41fd6d94fb9`. An external commit advanced HEAD to `5487625cf7f27fd0fa2048e1aaf5b38c71f67ccf` and captured the then-current working changes while this task ran. **The agent created no commit or push and did not reset/revert that external work.** Final HEAD/status are recorded in preservation evidence; HEAD identity must not be substituted for file-level preservation.

Every pre-existing inventoried file was compared with its starting hash. Only the six intended production/test files listed above changed from the starting contents; no starting file disappeared. Pre-existing 8D report/evidence modifications and all earlier Markdown/performance evidence remained unchanged. The retained source patch compares against the actual starting source archive, so the implementation remains reviewable despite the external commit.

## M. Remaining limitations

- Automatic mode, preferred automatic light/dark themes and live system-event rollback are **not delivered**. This is a partial 8E delivery, not full acceptance of its automatic-theme requirements.
- Independent multi-main-window live preview is explicitly unsupported. Existing manual global Swing styling remains as before.
- Theme application still rebuilds component UIs and refreshes existing previews. Explicit switching/preview costs can exceed 100 ms. No universal latency or native-resource guarantee is made.
- Windows/Linux, assistive technologies, IME popups, display-scale transitions, every existing dialog and project replacement during live preview were not exhaustively requalified. The existing routes are reused, not newly asserted correct in all environments.
- Configuration writing retains its established disk-write semantics. Arbitrary partial-write durability is not solved here.
- The Unicode/layout, HTML-preview, native-memory and release-readiness blockers from prior qualification remain unchanged. No transparency, blur, density or active-pane work was attempted.

## N. Next recommendation

The smallest remaining theme prerequisite is an **isolated system-appearance event-adapter feasibility task**: establish a supported current value and live change signal on the shipped JDK/macOS combination, prove one subscription owner, EDT delivery/coalescing and deterministic unsubscribe, and test changes during a lease without polling or altering real user preferences. Only after that should follow-system and preferred-light/dark persistence be implemented. Independent multi-window theme ownership is a separate bounded prerequisite if multi-window live preview is required; do not casually refactor the existing styling engine inside an OS adapter.

Tranche 8F can be considered independently, but requires its own spacing audit first. Density must use named **component spacing/dimension inputs**, with a default identical to today's layout; it must not reuse editor font zoom, UI font changes or a source-document/layout cache as a density mechanism. Verify tab/tree/tool/footer minimum sizes, focus/centering borders and split source caret geometry before changing any spacing. No density implementation or settings were begun here.
