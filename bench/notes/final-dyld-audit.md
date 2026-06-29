# Final dyld audit 2026-06-29

## Commands

- `swift build -c release`
- `bench/scripts/dyld_audit.sh`
- `dyld_info -fixups .build/release/ItsyApp`
- `dyld_info -symbolic_fixups .build/release/ItsyApp`
- `otool -l .build/release/ItsyApp`

## Result

- `DYLD_PRINT_STATISTICS=1 DYLD_PRINT_STATISTICS_DETAILS=1 .build/release/ItsyApp --bench-exit-on-ready` emits no rebase fixup count in this local environment.
- `bench/scripts/dyld_audit.sh` now falls back to static chained-fixup counting with `dyld_info -fixups`.
- Static rebase fixups: `8137`; target: `<2000`; result: fail.
- Static bind fixups: `1011`.
- `otool -l` reports `LC_DYLD_CHAINED_FIXUPS`, `nextrel 0`, and `nlocrel 0`; the binary is using chained fixups, not classic relocation records.

## Rebase distribution

- `5196` `__DATA_CONST/__const`
- `1721` `__DATA/__objc_const`
- `822` `__DATA/__objc_selrefs`
- `157` `__DATA/__objc_data`
- `124` `__DATA/__data`
- `39` `__DATA_CONST/__got`
- `36` `__DATA_CONST/__objc_classlist`
- `21` `__DATA_CONST/__objc_protolist`
- `21` `__DATA/__objc_protorefs`

## Cause

- [Inference] The growth above the `<2000` target is dominated by Swift/ObjC metadata and generated parser metadata, not hand-written mutable globals.
- Evidence: `dyld_info -symbolic_fixups` shows Swift type metadata rebases for `ItsyEditor`, `ItsyRender`, `ItsySyntax`, `ItsyKeymap`, and `ItsyApp` types, plus repeated tree-sitter symbol/name metadata rebases.
- Evidence: ObjC metadata sections account for at least `2757` rebases: `__objc_const`, `__objc_selrefs`, `__objc_data`, `__objc_classlist`, `__objc_protolist`, and `__objc_protorefs`.
- The TODO-linked Emerge writeup says Swift classes on Apple platforms produce ObjC metadata and rebase work, including for pure Swift classes: https://www.emergetools.com/blog/posts/SwiftReferenceTypes

## Follow-up

- Keep id105 lazy grammar loading high priority; [Inference] it is the largest plausible reduction for `__DATA_CONST/__const` and the 9.4 MB `__TEXT,__const` table footprint.
- Convert reference types only where semantics are already value-like. AppKit-facing controllers/views still need reference semantics.

## 2026-06-29 update

- `ItsySyntax` no longer links `CTSGrammars`; grammar symbols resolve with `dlopen`/`dlsym`.
- `bench/scripts/build_grammar_dylibs.sh` builds one dylib per grammar group, and `bench/scripts/make_app.sh` places them under `Itsy.app/Contents/Frameworks/ItsyGrammars`.
- Tests still link `CTSGrammars` so parser coverage runs without prebuilt local dylibs.
- Release binary grammar symbol audit: `nm -gU .build/release/ItsyApp | rg 'tree_sitter_'` returns no matches.
- Static rebase fixups after grammar split: `3816`; still above `<2000`, now tracked by the Swift reference-type and library-linkage follow-ups.

## Swift reference-type audit update

- Converted value-like pure Swift references to structs: `CommandRegistry`, `KillRing`, `KeymapEngine`, `LineShaper`, and `SyntaxPipeline`.
- Left C-resource wrappers as classes: `Parser`, `Tree`, `HighlightQuery`, and `GlyphAtlas`; they own pointers/resources with `deinit`.
- `RopeInput` and `RopeNode` were converted in later cleanups below.
- Static rebase fixups after these conversions: `3759`; remaining gap is dominated by value metadata plus AppKit/ObjC classes and needs the library/linker work next.

## Library linkage update

- `otool -L .build/release/ItsyApp` shows no internal `Itsy*` dynamic libraries in the launch image list.
- SwiftPM library products are now explicitly `.static` so release builds cannot accidentally flip these modules into dylib products.
- Mergeable-library Xcode settings are not applied because this repo is SwiftPM-only and has no Xcode project build setting surface.
- Static rebase fixups after explicit static products: `3759`; no change, confirming internal dynamic-library loading was not a current contributor.

## Current failure breakdown

After the native integration, memory, and release-pipeline changes, `bench/scripts/dyld_audit.sh` reports `3859` static rebase fixups. Target remains `<2000`; result remains fail.

Current section distribution:

- `1753` `__DATA/__objc_const`
- `877` `__DATA/__objc_selrefs`
- `812` `__DATA_CONST/__const`
- `187` `__DATA/__objc_data`
- `105` `__DATA/__data`
- `43` `__DATA_CONST/__got`
- `36` `__DATA_CONST/__objc_classlist`
- `23` `__DATA_CONST/__objc_protolist`
- `23` `__DATA/__objc_protorefs`

[Inference] Remaining rebase work is now dominated by AppKit/ObjC-facing classes and selectors, not statically linked grammar tables. Further reduction requires shrinking the AppKit subclass/controller surface or splitting non-launch UI out of the launch binary; converting additional pure Swift structs/classes is unlikely to close the full gap alone.

## Quick Look linkage update

- `FileTreeSidebar` no longer imports `QuickLookUI` at compile time; it loads `QLPreviewPanel` lazily with `dlopen` and Objective-C selectors when Space preview is requested.
- `otool -L .build/release/ItsyApp | rg 'QuickLook|QuickLookUI|swiftQuickLook'` now returns no matches.
- `swift -e` verification confirms `dlopen("/System/Library/Frameworks/QuickLookUI.framework/QuickLookUI", RTLD_LAZY | RTLD_LOCAL)` succeeds and `QLPreviewPanel` responds to `sharedPreviewPanel`.
- Static rebase fixups after lazy Quick Look: `3829`; target remains `<2000`; result remains fail.

## Singleton reference cleanup

- Converted value-like app singleton wrappers to static namespaces: `ItsyAppKeymap`, `ItsyCommandPaletteBridge`, `ItsyTabCoordinator`, and `ItsyWorkspaceController`.
- Removed unnecessary `NSObject` inheritance from `FileTreeNode`; `NSOutlineView` still accepts it as the outline item object.
- Static rebase fixups after singleton cleanup: `3762`; target remains `<2000`; result remains fail.

## Project find delegate cleanup

- Removed unused `NSObject` inheritance and empty `NSWindowDelegate` conformance from `ProjectFindController`.
- Static rebase fixups after project-find cleanup: `3758`; target remains `<2000`; result remains fail.

## Services provider cleanup

- Folded service selectors into the existing `AppDelegate` and removed the separate `ItsyServicesProvider` `NSObject` subclass.
- Static rebase fixups after services-provider cleanup: `3742`; target remains `<2000`; result remains fail.

## Grammar loader cleanup

- Converted `GrammarLoader` from a singleton class to a static namespace while keeping the same lock and `dlopen` handle cache.
- Static rebase fixups after grammar-loader cleanup: `3727`; target remains `<2000`; result remains fail.

## Editor pane coordinator cleanup

- Converted `EditorPaneCoordinator` from a private class to a private struct owned mutably by `EditorWindowController`.
- Static rebase fixups after editor-pane coordinator cleanup: `3719`; target remains `<2000`; result remains fail.

## Project find cancel cleanup

- Replaced the private `ProjectFindPanelDelegate` protocol with a panel cancel closure.
- Static rebase fixups after project-find cancel cleanup: `3718`; target remains `<2000`; result remains fail.

## Command palette cancel cleanup

- Replaced the private `CommandPalettePanelDelegate` protocol with a panel cancel closure while keeping `NSWindowDelegate` for blur dismissal.
- Static rebase fixups after command-palette cancel cleanup: `3717`; target remains `<2000`; result remains fail.

## Release reflection metadata cleanup

- Added release-only `-Xfrontend -disable-reflection-metadata` Swift settings to the app/runtime Swift targets.
- Static rebase fixups after rebuilding the app normally with reflection metadata disabled: `2661`; target remains `<2000`; result remains fail.
- `swift test -c release` passes; after release tests, rebuild with `swift build -c release` before auditing because the test build rewrites release artifacts with test settings.

## Release concrete metadata accessor cleanup

- Added release-only `-Xfrontend -disable-concrete-type-metadata-mangled-name-accessors` beside reflection metadata stripping.
- Static rebase fixups after rebuilding the app normally with both metadata flags: `2639`; target remains `<2000`; result remains fail.

## Pane layout state cleanup

- Replaced `EditorPaneLayout: Codable` JSON state with compact manual layout encoding to remove Codable/CodingKeys metadata from the app binary.
- Static rebase fixups after pane-layout state cleanup: `2626`; target remains `<2000`; result remains fail.

## Module marker visibility cleanup

- Made unused module marker enums internal and switched the smoke test to `@testable import ItsyEditor`.
- Static rebase fixups after module-marker visibility cleanup: `2622`; target remains `<2000`; result remains fail.

## Text field subclass cleanup

- Replaced three AppKit text-field subclasses with one shared `ItsyActionTextField` and kept find-only shortcuts behind an explicit flag.
- Static rebase fixups after text-field subclass cleanup: `2596`; target remains `<2000`; result remains fail.

## Tab button subclass cleanup

- Replaced the custom tab button subclass with plain `NSButton.tag` routing owned by `TabBarView`.
- Static rebase fixups after tab-button subclass cleanup: `2587`; target remains `<2000`; result remains fail.

## Editor pane controller cleanup

- Replaced the trivial `NSViewController` subclass for editor panes with a value wrapper around a plain `NSViewController` and `MetalTextView`.
- Static rebase fixups after editor-pane controller cleanup: `2582`; target remains `<2000`; result remains fail.

## Project find panel subclass cleanup

- Replaced the custom project-find panel subclass with a plain titled `NSPanel`; local verification showed that configuration already reports `canBecomeKey == true`.
- Static rebase fixups after project-find panel subclass cleanup: `2566`; target remains `<2000`; result remains fail.

## Command palette panel subclass cleanup

- Replaced the custom borderless command-palette panel subclass with a plain hidden-titlebar `NSPanel`; local verification showed that configuration reports `canBecomeKey == true` and `canBecomeMain == false`.
- Static rebase fixups after command-palette panel subclass cleanup: `2547`; target remains `<2000`; result remains fail.

## File tree outline subclass cleanup

- Replaced the file-tree `NSOutlineView` subclass with a local key monitor scoped to the outline view for Space preview.
- Static rebase fixups after file-tree outline subclass cleanup: `2543`; target remains `<2000`; result remains fail.

## File tree node cleanup

- Replaced the private Swift `FileTreeNode` class with `NSURL` outline items plus a per-sidebar child cache.
- Static rebase fixups after file-tree node cleanup: `2527`; target remains `<2000`; result remains fail.
- Static `__DATA_CONST,__objc_classlist` entries dropped from `22` to `21`.

## Rope input scratch cleanup

- Replaced parser `RopeInput` class storage with stack-owned scratch state passed directly to the synchronous tree-sitter input callback.
- Static rebase fixups after rope-input scratch cleanup: `2509`; target remains `<2000`; result remains fail.
- Static `__DATA_CONST,__objc_classlist` entries dropped from `21` to `20`.

## Rope node enum cleanup

- Replaced the private immutable `RopeNode` class with an `indirect enum` so the persistent rope tree no longer emits Swift class metadata.
- Static rebase fixups after rope-node enum cleanup: `2507`; target remains `<2000`; result remains fail.
- Static `__DATA_CONST,__objc_classlist` entries dropped from `20` to `19`.

## Command palette descriptor experiment

- Replaced app command-palette per-command closures with static command descriptors plus one id dispatcher.
- Static rebase fixups increased from `2507` to `2520` (`__DATA_CONST,__const` increased from `693` to `703`, `__DATA,__objc_const` from `950` to `953`).
- The experiment was reverted. This path does not reduce the remaining dyld gap.

## Glyph atlas value cleanup

- Converted `GlyphAtlas` from a Swift class to a struct, passed mutable atlas state through `LineShaper`, and removed the now-stateless cached `LineShaper` field.
- Static rebase fixups after glyph-atlas cleanup: `2480`; target remains `<2000`; result remains fail.
- Static `__DATA_CONST,__objc_classlist` entries dropped from `19` to `18`.
- Section deltas: `__DATA,__objc_const` dropped from `950` to `920`; `__DATA,__data` dropped from `62` to `56`; `__DATA_CONST,__const` rose from `693` to `703`.

## Quick Look delegate cleanup

- Folded `ItsyQuickLookController` into the existing `FileTreeSidebarView` Quick Look delegate/data-source path.
- Static rebase fixups after Quick Look delegate cleanup: `2464`; target remains `<2000`; result remains fail.
- Static `__DATA_CONST,__objc_classlist` entries dropped from `18` to `17`.
- Section deltas: `__DATA,__objc_const` dropped from `920` to `913`; `__DATA,__objc_data` dropped from `109` to `102`; `__DATA,__data` dropped from `56` to `55`.

## Project find controller cleanup

- Folded `ProjectFindController` panel/search state into the existing `AppDelegate`.
- Static rebase fixups after project-find controller cleanup: `2445`; target remains `<2000`; result remains fail.
- Static `__DATA_CONST,__objc_classlist` entries dropped from `17` to `16`.
- Section deltas: `__DATA,__objc_const` dropped from `913` to `901`; `__DATA_CONST,__const` dropped from `703` to `702`; `__DATA,__data` dropped from `55` to `50`.

## Command palette controller cleanup

- Folded `CommandPaletteController` panel/delegate state into the existing `AppDelegate`.
- Static rebase fixups after command-palette controller cleanup: `2429`; target remains `<2000`; result remains fail.
- Static `__DATA_CONST,__objc_classlist` entries dropped from `16` to `15`.
- Section deltas: `__DATA,__objc_const` dropped from `901` to `896`; `__DATA_CONST,__const` dropped from `702` to `701`; `__DATA,__objc_data` dropped from `102` to `94`; `__DATA,__data` dropped from `50` to `49`.

## Settings controller cleanup

- Replaced `ThemeSettingsWindowController` with a lazy plain `NSWindowController` owned by the existing `AppDelegate`.
- Static rebase fixups after settings-controller cleanup: `2416`; target remains `<2000`; result remains fail.
- Static `__DATA_CONST,__objc_classlist` entries dropped from `15` to `14`.
- Section deltas: `__DATA,__objc_const` dropped from `896` to `892`; `__DATA,__objc_data` dropped from `94` to `87`; `__DATA,__data` dropped from `49` to `48`.

## Tab bar subclass cleanup

- Folded `TabBarView` state/layout into the existing `EditorWindowController`; the tab coordinator now tracks window controllers directly.
- Static rebase fixups after tab-bar subclass cleanup: `2406`; target remains `<2000`; result remains fail.
- Static `__DATA_CONST,__objc_classlist` entries dropped from `14` to `13`.
- Section deltas: `__DATA,__objc_const` dropped from `892` to `885`; `__DATA,__objc_data` dropped from `87` to `80`; `__DATA,__data` dropped from `48` to `47`; `__DATA_CONST,__const` rose from `701` to `704`; `__DATA,__objc_selrefs` rose from `652` to `655`.

## Release flag experiments

- `swift build -c release -Xlinker -dead_strip` did not change static rebase fixups: `2406`.
- `swift build -c release -Xswiftc -cross-module-optimization` worsened static rebase fixups to `2465`; `__DATA_CONST,__const` rose from `704` to `763`.
- `swift build -c release -Xswiftc -Osize` worsened static rebase fixups to `2416`; `__DATA_CONST,__objc_protolist` and `__DATA,__objc_protorefs` each rose from `11` to `12`.
- Normal `swift build -c release` restored static rebase fixups to `2406`; target remains `<2000`; result remains fail.

## Find bar subclass cleanup

- Folded `FindBarView` state/layout into the existing `EditorWindowController`; `FindBarState` and the shared action text field remain.
- Static rebase fixups after find-bar subclass cleanup: `2384`; target remains `<2000`; result remains fail.
- Static `__DATA_CONST,__objc_classlist` entries dropped from `13` to `12`.
- Section deltas: `__DATA,__objc_const` dropped from `885` to `874`; `__DATA_CONST,__const` dropped from `704` to `703`; `__DATA,__objc_data` dropped from `80` to `72`; `__DATA,__data` dropped from `47` to `46`.
