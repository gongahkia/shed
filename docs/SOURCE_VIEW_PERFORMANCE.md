# Source-view invalidation — Tranche 1B

This tranche targets source-view invalidation while retaining JTextArea, PlainDocument, FileBuffer, authoritative snapshots, undo, all painting methods and the existing UI delegates. Historical [Tranche 1 results](MARKDOWN_PERFORMANCE_BASELINE.md) and [packaged Mermaid verification](PACKAGED_MARKDOWN_TEST.md) are preserved.

## Confirmed operation and root cause

Keyboard/controller editing mutates the shared Swing document on EDT. `FileBuffer` tracks document revisions and undo; `PaneBufferController.handleDocumentChange` updates the immutable snapshot, dirty state, LSP/completion/search and deferred syntax work. Swing's UI listeners separately deliver that same document event to each attached view. A cheap model replacement does not include the attached view's work.

The inspected Temurin/OpenJDK 21 source is `java.desktop/javax/swing/text/PlainView.java` in the installed JDK's `lib/src.zip`. Its `updateDamage` path (approximately lines 668–722) calls private `calculateLongestLine` on REMOVE from the cached widest line, or when a structural change removes that element. `calculateLongestLine` (approximately lines 780–796) visits every line and uses font/tab metrics to measure its width. INSERT can promote a wider line without that full scan. Thus typing into a widest line, then undoing that insertion, rescans every line even when undo restores its old width and another line cannot be wider. Undo arrives as a REMOVE through `AbstractDocument.DefaultDocumentEvent.undo` and ordinary Swing notifications; it does not need a separate undo implementation.

The original profile and fresh baseline identify `PlainView.removeUpdate → updateDamage → calculateLongestLine → getLineWidth → Utilities.getTabbedTextWidth → FontDesignMetrics`. Other costs remain: actual glyph painting/model-to-view on extreme lines, snapshot/listener work, Markdown prefix highlighting, and HTML preview layout. They are not all attributed to PlainView.

## Selected narrow optimization

`SourcePlainView` extends PlainView and leaves drawing, model-to-view, hit testing, tab expansion and preferred-span calculation inherited. It retains one width witness rather than a per-line cache:

1. Remember the last processed maximum width and its exact font/metrics identity.
2. When a local INSERT increases that width, remember the affected line and the previous maximum.
3. On a local REMOVE from that same line, use Swing's existing integer width-measurement API to check whether the line remains at least as wide as the previous maximum.
4. If so, every other line is unchanged and bounded by that previous maximum. Swing's cached widest-line reference is still valid: repaint the line and issue the normal horizontal preference change, without asking Swing to rescan all lines.
5. Otherwise use the original `updateDamage` implementation. Structural edits, actual maximum shrinkage, font/metrics changes and uncertain cases retain Swing's normal path.

The witness advances only after processing a document event. An earlier document listener may query preferred size after mutation but before the view receives the event; that query cannot overwrite the pre-event bound. Geometry tests explicitly exercise this ordering.

A single attachment-time scan identifies tab-free ASCII source. It reads bounded, partial-return Segments without materializing a document string. Later INSERT events check only inserted text. A tab/non-ASCII insertion disables the fast path until a new view is attached. Existing wrapped, bidi/international views and PlainView subclasses are delegated unchanged. This conservative restriction avoids changing complex shaping and Swing's font/tab initialization behavior; those documents retain their existing performance, rather than receiving an unverified optimization. Floating-point spans at or above 2^24 pixels also use the stock path because adjacent integer widths can alias.

Metadata is constant per view: one Element witness, font/metrics references and numeric/boolean fields. It has no line-count-dependent storage, document copies, worker, timer or extra document listener. Detachment clears the witness and metrics references. Splits have independent witnesses for their own fonts and views while observing the same source and undo history. The attachment scan is additional bounded initialization work and is included in startup/install measurements.

The existing source pane marks its own internal client property, so WorkbenchStyle's existing TextAreaUI applies the adaptation only to source panes. The existing InlineCompletionDisplay applies it only when displaying actual source; ghost/projection views remain unchanged. Theme/UI reload, document switches and wrapping continue through the current view factory and lifecycle. No global look-and-feel replacement or user setting is added.

## Alternatives not selected

- A full custom renderer or document store is unnecessary for the measured undo path and would broaden correctness risk.
- Per-line arrays/maps or a width tree could avoid more maximum-shrink rescans, but introduce memory/structural-update costs and substantially broader invalidation rules. They are not needed to test the smaller witness-based optimization.
- Suppressing preferred-size notifications, changing wrapping/font defaults, disabling highlighting, altering undo events or changing document ownership would change behavior rather than safely reduce work.
- Reflection into private Swing fields or module-opening flags are not used. This uses public/protected Swing APIs only and copies no JDK implementation.
