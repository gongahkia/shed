# Open VSX theme gallery qualification

Scope: data-only Open VSX discovery and theme import through the existing Theme Gallery, ConfigManager palettes and preview lease. No source editor, document model, extension runtime, font, theme default, setting window or dependency was replaced. Starting HEAD was `4e97831ecc8a6b31833956b820982c660bba8ce9`, with a clean tree.

## Verification

The raw reports in this directory preserve command arrays, toolchain/artifact identities, fixture boundaries and outcomes. `identities.json` records current production/test/tooling sources and the packaged JAR. All child processes use disposable homes/preferences/projects, closed stdin and bounded direct Java execution. The unqualified native-shell benchmark path was not run; no real language server or coding agent was launched.

- `safe-suite.json`: **522 run, 515 passed, seven existing skips**, zero failures/errors. This includes the 15 new OpenVsxThemeTest cases, two existing theme-preview cases and seven existing density cases. The hash-reviewed portable manifest is used with the pre-existing ProjectFileOpenPrerequisiteTest exclusion. The unrestricted full Maven/native suite and unreviewed ConfigManagerTest were not run.
- `tooling.json`: **104 passed**, including three new marketplace-probe admission/acceptance tests. Failure-shaped results, failed compilation, retained fixtures and uncaught GUI errors cannot count as acceptance. Existing output/noncanonical paths and missing toolchains reject before fixture creation.
- `package.json`: packaged release JAR, tests skipped only in the separate packaging step after the safe suite.
- `desktop.json`: actual Java/Swing workbench and gallery. Deterministic synthetic registry package; no public network in this desktop test. Search/download/install/preview/cancel, acceptance/persistence/offline reload, active-package removal guard, real removal confirmation, close-during-request cancellation, and source/revision/dirty/Undo/font invariants.
- `registry.json`: actual public HTTPS search, pinned download, CDN redirect, registry SHA-256 comparison and translation of Catppuccin's four variants. No installation into the normal profile. Declared MIT license; package version and archive SHA-256 are in the report. A one-host first request is not a universal network-latency claim.
- `animation-regression.json`: existing source-only three-split/density/theme/typing edge probe on the release artifact, including authenticated separate-Java passive read and cleanup. It does not start the shell demo.
- `packaged-media.json`: packaged Mermaid/math/image smoke on the same release JAR, **14 events**, PASS. Expected invalid-Mermaid diagnostics remain.

Owned gallery-root captures cover [Discover](discover.png), [a live dark-theme workbench preview with the imported selection visible](preview-dark.png), and [the narrow Installed view](installed-narrow.png). These are owned Swing component captures on macOS, not unrestricted desktop screenshots or Windows/Linux acceptance.

Final host: Apple M3, 16 GiB, arm64 macOS 26.5.2; JDK 21.0.13.1+2 and Maven 3.9.11. The deterministic desktop flow took **3.132 s**, including process/probe overhead. The separate real registry search/download/checksum/translation took **1,250.662459 ms** inside its JVM (one public-network observation, not a percentile or cold-cache benchmark). The source-only animation regression measured **21 synthetic edit-to-completed-component-paint samples**: first **3.845042 ms**, warm p95 across twenty samples **2.387791 ms**, below the existing 16 ms target for that small ASCII fixture. These processes ran sequentially after Maven/tooling execution. No native RSS, physical input, all-theme typing latency, cold-cache startup equivalence or large-file speedup is claimed.

## Regression detail and failed attempts

The new deterministic tests cover JSONC syntax and includes, multiple variants and stable IDs, alpha composition and syntax mapping, metadata sanitization, checksums/identities/allowed hosts, traversal/cycles/unsupported formats, expansion limits, offline catalog lifecycle, stored preference precedence, preview rollback/acceptance/save failure, corrupt catalogs, external symlink sentinels, replacement rather than version accumulation, saved display-name aliases and cancellable off-EDT work. Synthetic executable archive content is discarded, never executed.

The first focused run (`initial-focused.json`) passed 23 tests before the saved-name regression was added. Two attempted focused commands containing unreviewed ConfigManagerTest were rejected by admission before execution; the gate was not bypassed. The manifest admits only the reviewed new class, and the existing explicit Python allowlist was updated accordingly.

The first saved-alias fixture omitted the required configuration schema version and failed earlier than intended; `saved-alias-fixture-error.json` preserves it. After correcting that fixture, `saved-alias-red.json` reproduced the real defect: a saved imported display name remained required for restart but the removal guard missed it after a runtime theme change. ConfigManager now resolves that saved alias through the same theme lookup as ordinary selection. The final 15-case class passes inside the full safe run. No assertion was removed or exception suppressed.

Rendered inspection also found the newly installed theme selected below the visible list. `selection-red.json` reproduces that as a desktop assertion. Loading the installed list and selecting an installed variant now reveal the selected row with the existing JList scrolling operation. The final desktop probe requires the installed selection to intersect the viewport before previewing it.

## Architecture and safety limits

OpenVsxThemeService performs explicit, bounded HTTPS reads; VsCodeThemeImporter reads archive data in memory and maps supported JSON colors; ImportedThemeStore persists only validated palettes/provenance through AtomicFileWriter; ConfigManager owns per-instance installed palettes; ThemeGalleryDialog uses the existing async service and preview/accept/cancel infrastructure. UI-thread code never waits for a network response. The native fixture asserts registry work is off the EDT and that merely opening the gallery does not search.

Compatibility is deliberately partial: no `.tmTheme`/plist import, exact TextMate scope evaluation, semantic-token theming, font-style changes, icon themes or terminal ANSI import. Packages must declare a license; declared license metadata does not establish that every listed package is open-source. No publisher signature verification is claimed. Management and workbench preview are limited to one main window; concurrent catalog management across independent Shed processes is unqualified. Catalog preconditions and path checks do not claim protection against a fully capable same-user filesystem attacker.

Startup loads only the bounded local palette catalog; absent storage remains absent. The network client creates no startup service or persistent polling worker. Deterministic cancellation and owned window/job cleanup are checked; there is no long-session heap/GC-root/native-memory qualification. Typing observations are synthetic completed-component-paint measurements, not physical input/scanout measurements or an unmatched historical speedup claim.

Windows/Linux desktop, physical HiDPI, arbitrary community-theme contrast, all script/font combinations, long sessions, native-memory attribution and native-shell confinement remain unqualified. Existing source/glyph/preview limitations and the earlier checked multi-split open/read CONTEXT_CHANGED finding remain open.

## Reproduction

Use the existing JDK 21 and Maven installation, with a complete offline cache. The dependency cache used here is the previously bounded disposable copy from the animation edge work; no dependency, user Maven cache or personal tool configuration was changed.

```sh
python3 scripts/run-portable-tests.py --jdk <JDK-21> --maven <Maven> \
  --repository <offline-cache> --tests OpenVsxThemeTest,ThemePreviewTest,UiDensityTest \
  --output <new-focused.json>
```

For the broader safe suite, use the exact manifest class list in `safe-suite.json`. Tooling runs `python3 -B -m unittest discover -s scripts -p 'test_*.py' -v` under the validated disposable Fixture environment, disabled user-site imports, closed stdin and a 120-second bound. Package using the portable runner's `--package-only`, independently of test execution.

```sh
python3 scripts/test-theme-marketplace.py --jdk <JDK-21> --jar target/shed-2.0.0.jar \
  --output <new-canonical-directory>
python3 scripts/test-theme-marketplace.py --jdk <JDK-21> --jar target/shed-2.0.0.jar \
  --output <another-new-directory> --registry-smoke
python3 scripts/test-agent-desktop.py --jdk <JDK-21> --jar target/shed-2.0.0.jar \
  --output <new-animation-directory> --animation-demo --git-fixture --visual-only --edge-cases
```

The registry flag explicitly enables public HTTPS requests without installation or a native window. The default marketplace probe uses only deterministic fixture data, an owned Swing window and no shell/terminal/model. Packaged smoke uses scripts/test-packaged-markdown.py with explicit JDK/JAR/output under the same disposable environment; it does not invoke toolchain-resolution shells.

## Repository compliance

New production files: ImportedTheme.java, ImportedThemeStore.java, OpenVsxThemeService.java and VsCodeThemeImporter.java. Existing production changes: ConfigManager.java and ThemeGalleryDialog.java. Tests/admission: OpenVsxThemeTest.java, portable-fixture-tests.properties and test_native_fixture.py. Opt-in verification: ThemeMarketplaceProbe.java, test-theme-marketplace.py and test_theme_marketplace_tooling.py. Documentation: OPEN_VSX_THEMES.md, the theme-preview report cross-reference and this evidence directory.

README before/after SHA-256: `48bf87f09ffa570b7fc65d305a15c3228bbc67e906cc992e4fe7108893e7501e`. Existing user commits, Created.txt, earlier evidence, preferences and source/UI defaults are preserved. Local atomic commits only; no push, reset, clean or revert. `git diff --check` is required before handoff. This qualifies the covered theme-import functionality; it is not a general IDE release verdict.

Production/tests/admission commit: `a42b3fccd`. Gallery commit: `e75340450`. The following tooling/documentation commit contains this index and raw evidence. Exact full implementation commit hashes and source/artifact hashes are recorded in `identities.json`.
