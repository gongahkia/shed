# Markdown link navigation — Tranche 3A.2

This tranche exposes the 3A.1 infrastructure through Shed's existing commands, picker and file-opening paths. No Tranche 3B, 3C, live rendering, appearance work or later tranche was begun. Exact verification, measurements, artifact identities and development failures are retained in the [evidence index](performance/markdown-tranche3a2/INDEX.md).

## A. Summary

Markdown users can follow supported ordinary links and wiki-links, follow verified heading fragments, choose ambiguous files, inspect outgoing links and inspect/navigate backlinks, including references from unsaved buffers. Commands are `:markdown link follow`, `:markdown outgoing` and `:markdown backlinks`. The existing Vim `gf` path uses the same semantic implementation. Three named actions expose these commands in the existing command palette, including Plain and Emacs profiles. No new shortcut was assigned.

Local navigation and inspection require an existing owning project folder. Verified HTTP(S) links can still be opened without one, using the existing on-demand semantic service. Missing, unsupported or stale links are rejected with ordinary command feedback; no target is created.

## B. Files changed

This inventory describes changes relative to the captured 3A.2 start, including the previously uncommitted 3A.1 files that were extended. Unrelated portions of that work and all prior performance evidence remain intact.

Production:

- New `src/main/java/shed/MarkdownLinkNavigation.java`: compact rows, selection rules, source/disk validation and checked target metadata. No controller, scanner, parser cache or document ownership.
- `MarkdownController.java`: explicit command lifecycle, existing-picker calls, bounded/coalesced validation, external-link policy and safe source navigation.
- `MarkdownLink.java`: verified one-based source line/column metadata on compact occurrences; the prior constructor remains available. UTF-16 offset/range semantics are unchanged.
- `MarkdownLinkResolver.java`: reuse established heading resolution after explicit selection of an ambiguous file candidate.
- `MarkdownProjectLinks.java`: read-only installed views and monotonic generation identities, including close/reopen protection.
- `PaneBufferController.java`: checked overload of the existing asynchronous opener. Existing callers retain their original behavior; navigation admission precedes installation/history changes.
- `Texteditor.java`: narrow composition/delegation operations for commands, checked opening and cancellation.
- `EditorUiController.java`: cancel pending Markdown navigation when the active pane binding changes.
- `CommandHandler.java`: new Markdown subcommands through existing dispatch.
- `PaletteController.java`: three action registrations; picker implementation/style is unchanged.

Tests:

- New `src/test/java/shed/MarkdownLinkNavigationTest.java`: 13 deterministic headless planning/validation cases.
- Extended `src/test/java/shed/TexteditorSwingIntegrationTest.java`: eleven workbench cases, including actual modal picker filtering, keyboard selection/dismissal, listener release, worker saturation, aliases, external policy, read-only profiles, splits and source/history preservation.

Benchmarks/tooling:

- New `src/benchmark/java/shed/MarkdownNavigationProbe.java`: paired legacy navigation/generic-picker controls and actual integrated navigation/picker measurements.
- `MarkdownPerformanceProbe.java`: reflective dispatch for `link-navigation`, retaining compilation against the before artifact.
- `scripts/benchmark-markdown.py`: bounded opt-in mode; existing `project-links` measurement methodology remains unchanged.
- `scripts/test_benchmark_markdown.py`: admission-limit coverage for the new mode.

Documentation: this report and the new evidence files listed in `docs/performance/markdown-tranche3a2/manifest.json`. No dependency, renderer, lexer, source-view implementation, document model, undo engine or README changed.

## C. Existing UI reuse

The actual current picker is `PaletteController.showPaletteDialog`, backed by the existing `showPicker`/`PaletteFilterSession` implementation. There is no `FilePickerDialog` in this checkout; that name in the earlier handoff was historical guidance. The same undecorated picker, theme/font lookup, list renderer, filtering, Up/Down/Enter/Escape handling, focus conventions and disposal are reused. There is no new dialog class, panel, permanent sidebar or navigation framework.

Commands use `CommandHandler` and the existing palette action registry. Opening uses `PaneBufferController`, existing project trust/configuration handling, `DocumentRequests`, normal buffer installation and `DocumentNavigation`. Existing buffers are reused, including canonical aliases identified on a worker. Existing split ownership remains independent from the shared source document.

## D. Navigation behavior

The command captures buffer/document ID, revision, weak snapshot identity, caret/selection, key-input epoch, profile, pane and configured roots. It requests or refreshes the existing project index, resolves physical source identity off the EDT and identifies a verified occurrence at the caret. Genuine hyperlinks take precedence over nested image occurrences; multiple remaining matches are rejected instead of arbitrarily choosing one. A complete result with no link does not fall back to the former Markdown regex/file-under-cursor interpretation.

Ordinary relative paths, path-qualified and basename wiki-links, aliases, Unicode and verified fragments use 3A.1 semantics. Definitions and resolved reference uses are supported where that model exposes them. Code/escaped examples remain inactive. Missing targets, outside-root paths, unsafe schemes, inaccurate spans and unsupported semantic input receive feedback without opening a target.

Ambiguous basenames use the existing picker, with relative candidate paths and explicit acceptance. The selected path is checked against resolver candidates again on a worker; heading resolution then uses the same established rules. Duplicate heading text, missing headings and unverified preview anchors receive explicit feedback. No heading disambiguation list is fabricated. Preview anchor generation and preview hyperlink handling remain unchanged.

HTTP(S) external navigation retains the existing source-link Desktop behavior and only runs after an explicit verified command. Mailto/FTP/protocol-relative/file/custom schemes are not newly authorized by the source-link UI. Indexing never browses a URL. The existing preview's separate policy is unchanged.

## E. Outgoing links

`:markdown outgoing` shows deterministic source-order occurrences. Rows contain alias/destination, link kind, resolution status and referring file with one-based line/column. Distinct occurrences remain distinct, including multiple links to one target. Selection maps directly to the immutable occurrence; row text is never parsed back into a file path or offset.

Rows are capped at 200 and the title marks `first 200` when limited. Filtering operates on those presented rows, consistent with this bounded integration. Kind/file prefixes and plain text keep source-controlled labels from activating Swing HTML rendering. No per-row file read or whole-document copy occurs on the EDT. Line/column comes from the captured immutable semantic revision; navigation uses the validated UTF-16 offset.

## F. Backlinks

`:markdown backlinks` resolves the current document's physical project/file identity, queries incoming edges and presents referring file/location plus destination/status. Selecting a row opens the referring source at the occurrence offset. This action validates the referring revision, not the linked heading: a reference to a missing heading is still a useful source location.

Unsaved buffer contributions are preferred through 3A.1 overlays. Added/removed references, saving and discard use the existing lifecycle. A stale referring file, deleted/renamed path, replaced buffer or changed revision cannot reuse the old offset. A current dirty referring buffer is reused rather than replaced by disk content. Untitled sources remain outside the file-scoped project index.

## G. Index integration

The UI uses the existing `Texteditor.markdownLinks()` facade, its outgoing/backlink records, resolver, saved/unsaved contributions and bounded index worker. There is no second index, project scanner or database. Nothing requests indexing at startup. An explicit command refreshes an already-built partition's metadata, reusing unchanged semantic facts; an already-pending pass is coalesced rather than restarted by every repeated command.

Feedback distinguishes complete empty results, partial coverage, pending/building, cancellation and failure. Picker titles mark partial coverage. A source excluded from indexing or an unavailable unsaved revision is reported as unavailable; it does not become an authoritative empty outgoing list. Backlinks can still be useful for targets whose own contribution is unsupported. Deferred unresolved reference text and inaccurate table-inline locations remain unclassified, as in 3A.1.

## H. Concurrency and lifecycle

All filesystem resolution, disk capture, hashing and target validation run off the EDT on the existing IDE worker service. The UI contributes at most one active scope/identity/validation/open operation plus one newest pending metadata request. The slot remains reserved through file loading; cancelled completion releases it without cancelling a newer interaction. The existing separate bounded 3A.1 index worker is reused, with its existing debounce and eligibility limits.

Requests retain weak buffer, pane and snapshot references. Temporary open-buffer captures are also weak; a queued command does not pin unrelated document text. Picker rows retain compact occurrence references. Capture is capped at 64 open file buffers; overflow is rejected explicitly. Source and target models remain immutable. Background completions in job history do not capture entire semantic snapshots or source strings.

Before a jump, the controller checks source identity/revision/snapshot, selection, profile, input epoch, pane, roots and index generation. Physical scope and disk versions are rechecked on a worker. Existing target buffers must match the captured ID/revision/snapshot. For newly loaded positional targets, content hash and disk version are checked after loading and before installation. Logical open paths are kept distinct from canonical identity, including URI spelling in disk-version checks. History is recorded only at accepted installation, using the existing history system.

Caret changes, pane binding changes, source closure, root changes, supersession and disposal cancel pending work. Escape releases picker state and listeners through the existing picker cleanup. Focus loss while waiting cancels the request; after explicit picker acceptance, delayed modal focus events no longer cancel that valid choice. Revision, input, pane and project checks continue through validation after explicit selection, using the normal file-opening focus handoff. No focus grab or viewport change occurs merely to extract/index links.

Index generations are monotonic across partition disposal/reopening. A picker selection whose project index changes is rejected and must be requested again. Filesystem state is not globally atomic: validation narrows races without claiming a filesystem transaction.

## I. Performance and memory

PERFORMANCE_TABLE

The new opt-in workload uses actual commands, existing modal pickers, keyboard acceptance/dismissal and completed forced Swing paint. It runs 4 KiB/100 KiB/1 MiB sources in 21/101/1,001-file projects. The before artifact measures legacy ordinary link follow plus the existing generic picker over the same headless index. It does not pretend that 3A.1 already had outgoing/backlink commands. Query/index controls retain the established independent `project-links` workload.

Explicit refresh and validation add end-to-end navigation latency while keeping dispatch short. Tables and raw records separate EDT dispatch, row construction, worker validation and observed completion/paint. Polling/forced paint is not native keyboard/compositor timing. The fixture is warm-cache and host load is uncontrolled. Sample counts accompany percentiles; small observations are not universal limits.

MEMORY_TABLE

Memory observations wait for established timers/jobs, then sample used/committed heap and settled RSS after explicit GC. Picker collection uses weak references; index/UI activity and source revision deltas are checked. Allocations are not equated with retention or RSS. The historical 910.05 MiB qualification remains open; these paired observations do not establish native-memory attribution or prove universal memory equivalence. No JVM/GC, font or renderer optimization was attempted.

Numeric fixture budgets and exact measurements are in the evidence index. No significant persistent UI growth or unbounded retention is claimed from timings alone; actual cleanup observations are retained alongside RSS/heap results.

## J. Correctness evidence

Headless tests cover relative/wiki/Unicode/fragment navigation, explicit ambiguous choice, missing/duplicate/unsupported headings, disk edits/deletion/rename, scope and symlink boundaries, code/escapes, external classification, current dirty source/target revisions, bounded rows and truthful completeness, cancellation, no EDT validation, source bytes and linked-image precedence.

Workbench cases cover existing history and caret placement, source/undo/dirty preservation, duplicate-tab avoidance, independent shared splits, current unsaved backlinks and removed references, source/index edits while selecting, actual picker styling/filtering/keyboard actions and listener release, aliases, all three profiles/read-only surfaces, no-project HTTP(S) semantics without creating an index, project/pane disposal and saturated worker supersession. The real outgoing acceptance regression covers modal focus restoration with an already-open target.

A document listener observes zero source events during actual picker acceptance/navigation; source revision, snapshot, baseline, dirty state and undo remain unchanged. The navigation code introduces no LSP text-change call. Existing LSP, persistence, recovery, highlighting, source-view, folding and preview tests remain enabled in the full suite. No tests were disabled or assertions weakened to obtain the result.

## K. Regression tests

FINAL_COUNTS

Exact commands, suite totals, skip reasons, packaged smoke, tooling output and development failures are in the evidence index. Failed development runs are preserved: malformed HTML wiki test input, canonical/logical disk URI mismatch, a benchmark's zero-offset assumption, modal focus restoration and an overlapping packaging failure were corrected. Verification builds were then serialized. No failed run is reported as successful.

## L. UI/UX compliance

Existing visual identity, layout, themes, typography, fonts, colors, editor surfaces, outline, navigation controls, tabs and picker style remain unchanged. New content consists of three action registrations and Markdown rows/feedback in established components. No global shortcut, settings, window class, framework, rendering architecture, permanent backlink panel or appearance feature was added. Non-Markdown symbol extraction and unrelated command/key routing are unchanged.

## M. README and repository compliance

README SHA-256 before and after: `48bf87f09ffa570b7fc65d305a15c3228bbc67e906cc992e4fe7108893e7501e`. README was not edited. Starting HEAD/status and 1,132 file hashes were captured before implementation. Existing uncommitted 3A.1 code and evidence were preserved; shared files were extended deliberately. No reset, clean, revert, commit or push command was issued by this task. Final preservation checks, dependency hashes and HEAD are archived.

## N. Remaining limitations

- Pickers present/filter at most the first 200 verified occurrences/candidates, with a visible limit label. They do not provide a new paging UI.
- Local navigation and inspection need an owning configured project folder. Unsupported, excluded, capped or unavailable current source data is rejected. Untitled buffers have no project file identity.
- Unresolved CommonMark reference text, wiki constructs without verified spans, table-inline precision and math-bearing preview anchors retain 3A.1 limitations. Ambiguous headings are reported rather than guessed.
- Target file opening without a fragment can use existing supported file/large-file surfaces; positional navigation requires verified complete text/revision data. More than 64 open file buffers or multiple physical aliases represented by distinct open buffers can require closing a buffer and retrying.
- A changed index, source, target or security/project configuration can reject a pending request. Deliberately preserved file size/mtime can require an existing known-path invalidation or rebuilding the partition; fresh version checks reject old positions even if metadata reuse missed the change.
- Preview hyperlink behavior remains its existing separate path. This tranche migrates source link follow and new inspections. It does not add wiki rendering or media fetching.
- Native-memory attribution remains unresolved. A second OS/filesystem was unavailable; I cannot verify runtime cross-platform behavior beyond this host. Production code uses existing Java/Swing/platform-neutral services and no new dependency.

## O. Next tranche recommendation

3B can build on current occurrence identity/range contracts and existing bounded async/disposal patterns, but should keep media work isolated from the link index and lexical highlighting. Establish decoded-image/diagram/math admission, cancellation, security and retained-memory budgets before persistent rich-media consumers. The unresolved RSS qualification remains a gate for broader persistent rendering work; it is not resolved by collected picker objects.

Preserve `FileBuffer`, undo, persistence, the 1D lexer, current preview design and these navigation/version checks. Do not add live Markdown presentation, appearance features, graph/vault behavior, transclusion or rename rewriting as part of that infrastructure. No later tranche was started here.
