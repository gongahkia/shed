package shed;

import java.util.function.BooleanSupplier;
import java.lang.ref.WeakReference;
import javax.swing.JTextArea;
import javax.swing.SwingUtilities;
import javax.swing.text.BadLocationException;
import javax.swing.text.PlainDocument;
import javax.swing.undo.AbstractUndoableEdit;

/** A short-lived, EDT-owned Markdown range edit. It owns neither source nor history. */
final class MarkdownEdit {
    static String applyPlan(MarkdownSubtreeEditPlan plan, MarkdownSemanticSnapshot.Source current,
                            FileBuffer buffer, JTextArea area, EditorSurface surface, BooleanSupplier targetValid) {
        if (!SwingUtilities.isEventDispatchThread()) throw new IllegalStateException("Markdown edits require the EDT");
        if (!plan.source().matches(current) || current.revision() != buffer.documentRevision()
            || current.text() != buffer.textSnapshot() || area.getDocument() != buffer.getDocument()
            || area.getCaret().getDot() != plan.dot() || area.getCaret().getMark() != plan.mark())
            return "Markdown edit unavailable: stale subtree plan";
        try {
            plan.subtree().validate(current.text()); plan.range().validate(current.text());
            for (var marker : plan.markers()) {
                marker.range().validate(current.text());
                if (!marker.expected().equals(buffer.getDocument().getText(marker.range().start(), marker.range().length())))
                    return "Markdown edit unavailable: stale subtree marker";
            }
            if (!plan.expected().equals(buffer.getDocument().getText(plan.range().start(), plan.range().length())))
                return "Markdown edit unavailable: stale subtree text";
            if (plan.undoBytes() > ((BoundedUndoManager) buffer.getUndoManager()).policy().maxBytes())
                return "Markdown edit exceeds the undo history byte limit";
            return new MarkdownEdit(buffer, area, surface, targetValid, plan.range().start(), plan.range().end())
                .apply(plan.replacement(), -1, plan);
        } catch (BadLocationException | IllegalArgumentException invalid) {
            return "Markdown edit unavailable: invalid subtree range";
        }
    }

    static String applyPlan(MarkdownHeadingEditPlan plan, MarkdownSemanticSnapshot.Source current,
                            FileBuffer buffer, JTextArea area, EditorSurface surface, BooleanSupplier targetValid) {
        if (!SwingUtilities.isEventDispatchThread()) throw new IllegalStateException("Markdown edits require the EDT");
        if (!plan.source().matches(current) || current.revision() != buffer.documentRevision()
            || current.text() != buffer.textSnapshot() || area.getDocument() != buffer.getDocument()
            || area.getCaret().getDot() != plan.dot() || area.getCaret().getMark() != plan.mark())
            return "Markdown edit unavailable: stale semantic plan";
        try {
            plan.range().validate(current.text());
            if (!plan.expected().equals(buffer.getDocument().getText(plan.range().start(), plan.range().length())))
                return "Markdown edit unavailable: stale marker text";
            return new MarkdownEdit(buffer, area, surface, targetValid, plan.range().start(), plan.range().end())
                .apply(plan.replacement(), -1);
        } catch (BadLocationException | IllegalArgumentException invalid) {
            return "Markdown edit unavailable: invalid semantic range";
        }
    }

    private final FileBuffer buffer;
    private final JTextArea area;
    private final EditorSurface surface;
    private final BooleanSupplier targetValid;
    private final PlainDocument document;
    private final VersionedTextSnapshot snapshot;
    private final long revision;
    private final int start, end, dot, mark;
    private final String expected;

    MarkdownEdit(FileBuffer buffer, JTextArea area, EditorSurface surface,
                 BooleanSupplier targetValid, int start, int end) throws BadLocationException {
        if (!SwingUtilities.isEventDispatchThread()) throw new IllegalStateException("Markdown edits require the EDT");
        this.buffer = buffer;
        this.area = area;
        this.surface = surface;
        this.targetValid = targetValid;
        document = buffer.getDocument();
        snapshot = buffer.textSnapshot();
        revision = buffer.documentRevision();
        this.start = start;
        this.end = end;
        dot = area.getCaret().getDot();
        mark = area.getCaret().getMark();
        if (start < 0 || end < start || end > document.getLength())
            throw new BadLocationException("Invalid Markdown range", start);
        expected = document.getText(start, end - start);
    }

    String apply(String replacement, int caret) {
        return apply(replacement, caret, null);
    }

    private String apply(String replacement, int caret, MarkdownSubtreeEditPlan subtree) {
        return apply(replacement, caret, subtree, null);
    }

    String applySelection(String replacement, EditorSurface.Selection after, BooleanSupplier restoreContext) {
        if (!SwingUtilities.isEventDispatchThread()) throw new IllegalStateException("Markdown edits require the EDT");
        try { new MarkdownSourceRange(start, end).validate(snapshot); }
        catch (IllegalArgumentException invalid) { return "Markdown edit unavailable: invalid source selection"; }
        long limit = (long) start + replacement.length();
        if (after == null || restoreContext == null || limit > Integer.MAX_VALUE
            || (long) document.getLength() - (end - start) + replacement.length() > Integer.MAX_VALUE
            || after.dot() < start || after.mark() < start || after.dot() > limit || after.mark() > limit
            || splitsPair(replacement, after.dot() - start) || splitsPair(replacement, after.mark() - start))
            return "Markdown edit unavailable: invalid result selection";
        return apply(replacement, -1, null, new SelectionHistory(area, document,
            new EditorSurface.Selection(dot, mark), after, restoreContext));
    }

    private static boolean splitsPair(String text, int offset) {
        return offset > 0 && offset < text.length() && Character.isHighSurrogate(text.charAt(offset - 1))
            && Character.isLowSurrogate(text.charAt(offset));
    }

    private String apply(String replacement, int caret, MarkdownSubtreeEditPlan subtree, SelectionHistory selection) {
        if (!SwingUtilities.isEventDispatchThread()) throw new IllegalStateException("Markdown edits require the EDT");
        if (buffer.isLargeFile() || buffer.isShowingPreviewOnly() || !targetValid.getAsBoolean()
            || !(surface instanceof SwingEditorSurface) || area.getDocument() != document || buffer.getDocument() != document
            || buffer.documentRevision() != revision || area.getCaret().getDot() != dot
            || selection != null && buffer.textSnapshot() != snapshot
            || area.getCaret().getMark() != mark || document.getDocumentFilter() != null) {
            return "Markdown edit unavailable: read-only, unsupported or stale target";
        }
        try {
            if (!expected.equals(document.getText(start, end - start))) return "Markdown edit unavailable: stale text";
            if (expected.equals(replacement)) return "";
            int prefix = 0, suffix = 0;
            while (prefix < expected.length() && prefix < replacement.length()
                && expected.charAt(prefix) == replacement.charAt(prefix)) prefix++;
            while (suffix < expected.length() - prefix && suffix < replacement.length() - prefix
                && expected.charAt(expected.length() - suffix - 1) == replacement.charAt(replacement.length() - suffix - 1)) suffix++;
            int editStart = start + prefix, editEnd = end - suffix;
            String inserted = replacement.substring(prefix, replacement.length() - suffix);
            BoundedUndoManager history = (BoundedUndoManager) buffer.getUndoManager();
            // Match BoundedUndoManager's payload accounting before touching source or redo history.
            long bytes = (editEnd == editStart ? 0 : 64L + 2L * (editEnd - editStart))
                + (inserted.isEmpty() ? 0 : 64L + 2L * inserted.length()) + (selection == null ? 0 : 128L);
            if (bytes > history.policy().maxBytes()) return "Markdown edit exceeds the undo history byte limit";
            try { history.beginCompoundEdit(); }
            catch (IllegalStateException busy) { return "Markdown edit unavailable: another edit is in progress"; }
            try {
                if (selection != null) history.addEdit(new SelectionBoundary(selection, true));
                surface.replace(editStart, editEnd, inserted);
                if (selection != null) history.addEdit(new SelectionBoundary(selection, false));
            } finally {
                history.endCompoundEdit();
            }
            if (selection != null) surface.select(selection.after.dot(), selection.after.mark());
            else if (subtree != null) surface.select(subtree.mapPosition(dot), subtree.mapPosition(mark));
            else if (caret >= 0) surface.select(caret, caret);
            else surface.select(mapPosition(dot, editStart, editEnd, inserted.length()), mapPosition(mark, editStart, editEnd, inserted.length()));
            return "";
        } catch (BadLocationException error) {
            return "Markdown edit unavailable: invalid range";
        }
    }

    /** View state brackets the existing compound document edit; it owns no pane or source strongly. */
    private static final class SelectionHistory {
        final WeakReference<JTextArea> area;
        final WeakReference<PlainDocument> document;
        final EditorSurface.Selection before, after;
        final BooleanSupplier context;
        boolean undoAllowed, redoAllowed;
        SelectionHistory(JTextArea area, PlainDocument document, EditorSurface.Selection before,
                         EditorSurface.Selection after, BooleanSupplier context) {
            this.area = new WeakReference<>(area); this.document = new WeakReference<>(document);
            this.before = before; this.after = after; this.context = context;
        }
        boolean current() {
            JTextArea target = area.get();
            return target != null && target.getDocument() == document.get() && context.getAsBoolean();
        }
        void restore(EditorSurface.Selection value, boolean allowed) {
            JTextArea target = area.get(); PlainDocument source = document.get();
            if (!allowed || target == null || source == null || target.getDocument() != source
                || value.dot() > source.getLength() || value.mark() > source.getLength()) return;
            target.setCaretPosition(value.mark()); target.moveCaretPosition(value.dot());
        }
    }

    private static final class SelectionBoundary extends AbstractUndoableEdit {
        final SelectionHistory selection;
        final boolean before;
        SelectionBoundary(SelectionHistory selection, boolean before) { this.selection = selection; this.before = before; }
        @Override public void undo() {
            super.undo();
            if (before) selection.restore(selection.before, selection.undoAllowed);
            else selection.undoAllowed = selection.current();
        }
        @Override public void redo() {
            super.redo();
            if (before) selection.redoAllowed = selection.current();
            else selection.restore(selection.after, selection.redoAllowed);
        }
    }

    private int mapPosition(int position, int editStart, int editEnd, int replacementLength) {
        if (position <= editStart) return position;
        if (position >= editEnd) return position + replacementLength - (editEnd - editStart);
        return editStart + Math.min(position - editStart, replacementLength);
    }
}
