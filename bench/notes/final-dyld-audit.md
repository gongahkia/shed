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
