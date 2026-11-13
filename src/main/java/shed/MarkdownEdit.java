package shed;

import java.util.function.BooleanSupplier;
import javax.swing.JTextArea;
import javax.swing.SwingUtilities;
import javax.swing.text.BadLocationException;
import javax.swing.text.PlainDocument;

/** A short-lived, EDT-owned Markdown range edit. It owns neither source nor history. */
final class MarkdownEdit {
    private final FileBuffer buffer;
    private final JTextArea area;
    private final EditorSurface surface;
    private final BooleanSupplier targetValid;
    private final PlainDocument document;
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
        if (!SwingUtilities.isEventDispatchThread()) throw new IllegalStateException("Markdown edits require the EDT");
        if (buffer.isLargeFile() || buffer.isShowingPreviewOnly() || !targetValid.getAsBoolean()
            || !(surface instanceof SwingEditorSurface) || area.getDocument() != document || buffer.getDocument() != document
            || buffer.documentRevision() != revision || area.getCaret().getDot() != dot
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
                + (inserted.isEmpty() ? 0 : 64L + 2L * inserted.length());
            if (bytes > history.policy().maxBytes()) return "Markdown edit exceeds the undo history byte limit";
            try { history.beginCompoundEdit(); }
            catch (IllegalStateException busy) { return "Markdown edit unavailable: another edit is in progress"; }
            try {
                surface.replace(editStart, editEnd, inserted);
            } finally {
                history.endCompoundEdit();
            }
            if (caret >= 0) surface.select(caret, caret);
            else surface.select(mapPosition(dot, editStart, editEnd, inserted.length()), mapPosition(mark, editStart, editEnd, inserted.length()));
            return "";
        } catch (BadLocationException error) {
            return "Markdown edit unavailable: invalid range";
        }
    }

    private int mapPosition(int position, int editStart, int editEnd, int replacementLength) {
        if (position <= editStart) return position;
        if (position >= editEnd) return position + replacementLength - (editEnd - editStart);
        return editStart + Math.min(position - editStart, replacementLength);
    }
}
