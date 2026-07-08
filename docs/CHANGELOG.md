# Changelog

## 2026-07-08

### Phase 36 D9: Docs automation

- Added screenshot capture automation for main, command palette, find, and terminal views.
- Added CI drift check for regenerated keymap docs.
- Regenerated `docs/keymap-reference.md` from current keymaps.

### Phase 36 D7: Grapheme UAX29 seam fixtures

- Added edge fixtures for combining marks, regional indicators, ZWJ emoji, Indic conjuncts, and CRLF across piece seams.
- Routed non-simple grapheme spans through whole-document UAX29 boundaries.

### Phase 36 D6: Vim binding regression suite

- Added generated regression coverage for all 275 Vim bindings.
- Added a generator that fails tests when the bundled Vim keymap diverges from the generated snapshot.

### Phase 36 D5: Code coverage reporting

- Added coverage generation, CI upload artifacts, and a committed baseline.
- Added a coverage gate against the current baseline.
