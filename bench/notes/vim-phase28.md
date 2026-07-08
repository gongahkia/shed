# Phase28 Vim checkpoint

Date: 2026-07-01

## Completed

- Added `ItsyVim` as a pure SwiftPM target with no AppKit import.
- Moved Vim state from `MetalTextView` into `VimEngine`.
- Routed `MetalTextView` keymap command IDs through `VimEngine.handle(commandID:count:hasSelection:)`.
- Kept `MetalTextView` as the buffer/UI adapter for edits, pasteboard, undo, macro replay events, ex command callbacks, and visual selections.
- Added black-box `ItsyVimTests` coverage with an in-memory `BufferQuery`.
- Added a 16-seed fuzz test; each seed runs 500 random keys over a random buffer and validates local buffer/selection invariants.

## Verification

```sh
swift test --no-parallel --filter ItsyVimTests
swift test --no-parallel --filter 'vimJumpBackReturnsToPreviousJumpSelection|vimMacroRecordsAndReplaysKeys|vimMacroReplaySkipsRecursiveReplay|vimSearchCommandsRouteToHost|vimUndoRedoTreatsInsertSessionAsOneUndoUnit|vimDeleteOperatorAppliesMotionAndLine|vimChangeAndYankLineOperators|vimTextObjectsApplyToPendingOperator|vimQuoteTextObjectUsesShiftedQuoteBinding|vimVisualCharModeAppliesDeleteOperator|vimVisualLineModeYanksWholeLine|vimVisualBlockModePopulatesSelectionSet|vimRegistersYankAndPasteNamedRegister|vimDeleteWritesNumberedRegister|vimPlusRegisterSyncsSystemClipboard|vimExSubstitutionEditsBuffer|vimExCommandRoutesToHost|vimExCommandUsesCommandLineRequestWhenAvailable'
swift build -c release
rg -n "AppKit|NSEvent|NSPasteboard|NS" Sources/ItsyVim Tests/ItsyVimTests || true
```

Result:

- `ItsyVimTests`: pass.
- Focused Vim adapter tests: pass.
- Release build: pass.
- AppKit scan: no matches in `Sources/ItsyVim` or `Tests/ItsyVimTests`.

## Coverage

- Motions: character, line, word, big-word, line start/end, buffer start/end, paragraph, page, arrows, `gg`, and counts.
- Operators: `d`, `c`, `y` over motions and line form.
- Text objects: command routing for word, quotes, parens, brackets, braces, and paragraphs.
- Registers: `"`, `0...9`, `a...z`, `+`, `*`, and `_` parsing; adapter handles `*` as system pasteboard and `_` as black-hole.
- Macros: record, stop, replay register routing, and recursive replay guard through adapter tests.
- Marks: set and jump actions.
- Search: `/`, `?`, `n`, `N`, and command-ID routing.
- Visual: character, line, block mode actions plus adapter selection behavior.
- Ex: adapter tests cover substitution and host command routing.
- Fuzz: 8,000 random key steps across 16 generated buffers.

## Parity notes

- `ItsyVim` now owns key-state decisions; buffer mutation still lives in `MetalTextView`.
- Text object range calculation is still adapter-side.
- Remaining unsupported Vim commands stay pinned in `ItsyVimTests`; case, indent, dot-repeat, and tag text objects now have regression coverage.
- Full ex parsing is still adapter-side; `ItsyVim` only emits command-start/action routing.
