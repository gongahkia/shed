# Editor workflow follow-up

This follows the repository and competitor review in [EDITOR_COMPARISON.md](EDITOR_COMPARISON.md). It implements the requested keymap, search, open-editor and measurement work. README.md was left untouched.

## Delivered behavior

| Area | Change | Verification focus |
| --- | --- | --- |
| Keyboard customization | Plain, Emacs, Vim and shared global action bindings; portable Mod shortcuts; effective binding/conflict inspection; menu discoverability and persisted reset | Profile precedence, physical modifier precedence, identical-action conflicts, printable punctuation, non-editor fields, IME composition and persisted reload |
| Search | Persistent Search panel; shared case/Unicode-word/regex controls; include/exclude globs; progress, cancellation and actual limit detection; keyboard result opening; transfers to inline Find and Replace | Option transfer, exact locations, source preservation, ignored/multiple/overlapping roots, cancellation during regex backtracking, regex groups, single-step inline undo and preview invalidation |
| Replacement | Filtered multi-root previews with per-match expansions and existing selection/backup/atomic-write paths | Different-length replacements after prefixes, selected matches, stale disk content, dirty editor guards and refusal to apply truncated previews |
| Open documents | Optional document strip with disambiguated names, unsaved indicators, keyboard switching and guarded close | Selection preservation, accessible dirty labels, canceled discard, active-split switching and replacing a closed final document in every split |
| Performance | Independent JAR-based benchmark with startup milestones, heap/RSS, edit-to-paint and picker samples; reduced fuzzy-ranking allocation | Same harness/fixtures/JDK/heap across pre-change and current artifacts; retained raw samples and explicit budgets |

[KEYBINDS.md](KEYBINDS.md), [SEARCH_WORKFLOWS.md](SEARCH_WORKFLOWS.md), [OPEN_EDITORS.md](OPEN_EDITORS.md) and [EDITING_PERFORMANCE.md](EDITING_PERFORMANCE.md) describe the controls and their limits.

## Interface review

Full review scope: the keyboard-shortcut inspector additions, inline Find/Replace, persistent Search, replacement preview controls, the shared tool header/tab presentation, and the open-editor strip. Swing remains the framework; existing `ConfigManager` colors, `resolveUiFont` fonts and `UiZoom` sizing remain the design sources.

| Inventory | Before → after / decision |
| --- | --- |
| Typography | Source names were primarily reachable through pickers/status text. The strip now keeps names visible and adds paths only for duplicates. Search controls, results and the strip use the configured UI font and zoom. Full paths and dirty state are exposed to accessibility APIs. |
| Surfaces and controls | Workspace matches replaced the source view with a quickfix scratch buffer. They now occupy a persistent tool surface with explicit controls and retained results. Native macOS button painting produced pale text on white buttons in the dark theme; targeted controls now paint with the existing palette. Interactive search/document targets use 40 scaled pixels. |
| Animation | No new animation. Search debounces ordinary edits, streams bounded batches, and invalidates older requests. Results do not move the source caret until explicitly opened. |
| Icons | No new icon library. The dirty dot is supplemented by an accessible unsaved label; the Close action uses text. |
| Performance | Avoided rebuilding the open-editor list for unchanged displayed state. Shared search matching is cancellable, bounded and off the event thread; highlights are limited around the selected match. Search font metrics wait for staged startup so a hidden control does not force early system-font discovery. |

Rejected candidates: a new styling framework/icon system, decorative typing transitions, and disposable preview/pin semantics in this change. Every opened document is retained; avoiding implicit eviction keeps the existing split/document lifecycle predictable. Multi-key custom chords and a general extension API remain separate work. Workspace streaming search is line-oriented; current-file and full-document replacement regexes can consume newlines. This is documented rather than implying full search-engine parity.

Visual inspection uses captures of Shed's own window in the default dark theme, replacement preview state, and GitHub Light at 150% zoom. Behavioral desktop checks cover keyboard opening, source retention, dirty labels, close cancellation, split consistency, options, invalidation and undo. Screen-reader output and native IME composition were not exercised through external assistive software; tests validate accessible metadata and composition-event handling. Other operating systems and all theme/zoom combinations were not visually audited.

Review verdict: the inspected dark and light/150% states are readable and usable after correcting native-button contrast, tool sizing, clipped replacement filenames and selection retention. The bounds above remain unverified. Captures: [Search](images/workflows-search.png), [replacement review](images/workflows-replace.png), and [Search at 150%](images/workflows-search-150.png).

## Verification

| Check | Result |
| --- | --- |
| Clean headless build/check | 960 tests, zero failures/errors, 81 skips; packaged successfully |
| Full desktop suite | 960 tests, zero failures/errors, three environment-specific skips (DOS hidden attributes, Fish, CTest preset tooling) |
| Final layout refinements | Focused desktop checks passed for shared search, preview invalidation, dirty-document guards, filenames, retained match selection, open-editor lifecycle and staged-font layout |
| Final packaging | Successful after the refinements |
| README constraint | No changes to README.md |

[Measured performance](PERFORMANCE_RESULTS.md) records raw samples, build hashes, startup regression evidence, budget results and methodological limits. These are local Shed-before/Shed-after observations, not measurements of Lite XL, Sublime Text, VS Code or Atom.
