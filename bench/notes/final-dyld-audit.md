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
- Left C-resource wrappers as classes: `Parser`, `Tree`, `HighlightQuery`, `RopeInput`, and `GlyphAtlas`; they own pointers/resources with `deinit`.
- Left `RopeNode` as a class because the rope is a persistent tree of shared child nodes; changing it would be a buffer representation rewrite, not a local reference-type cleanup.
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
