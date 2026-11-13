package shed;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.SwingUtilities;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.text.PlainDocument;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MarkdownEditTest {
    @TempDir Path temp;

    private static final class Fixture {
        final FileBuffer buffer;
        final JTextArea area = new JTextArea();
        final AtomicBoolean writable = new AtomicBoolean(true);
        final SwingEditorSurface surface;
        final BoundedUndoManager undo;
        int events;
        Fixture(FileBuffer buffer) {
            this.buffer = buffer;
            area.setDocument(buffer.getDocument());
            undo = (BoundedUndoManager) buffer.getUndoManager();
            surface = new SwingEditorSurface(area, new JScrollPane(area), () -> undo, writable::get);
            buffer.getDocument().addDocumentListener(new DocumentListener() {
                public void insertUpdate(DocumentEvent event) { changed(event); }
                public void removeUpdate(DocumentEvent event) { changed(event); }
                public void changedUpdate(DocumentEvent event) { changed(event); }
                private void changed(DocumentEvent event) {
                    events++;
                    buffer.applyDocumentChange(event);
                    buffer.setModified(true);
                }
            });
        }
        MarkdownEdit edit(int start, int end) throws Exception {
            return new MarkdownEdit(buffer, area, surface, writable::get, start, end);
        }
        void rejected(MarkdownEdit edit) {
            String text = area.getText(), saved = buffer.getSavedContent();
            long revision = buffer.documentRevision();
            var snapshot = buffer.textSnapshot();
            boolean dirty = buffer.isModified(), canUndo = undo.canUndo(), canRedo = undo.canRedo();
            int count = undo.retainedEditCount(), beforeEvents = events;
            long bytes = undo.retainedBytes();
            int dot = area.getCaret().getDot(), mark = area.getCaret().getMark();
            assertFalse(edit.apply("changed", -1).isEmpty());
            assertEquals(text, area.getText()); assertEquals(saved, buffer.getSavedContent());
            assertEquals(revision, buffer.documentRevision()); assertSame(snapshot, buffer.textSnapshot());
            assertEquals(dirty, buffer.isModified()); assertEquals(canUndo, undo.canUndo()); assertEquals(canRedo, undo.canRedo());
            assertEquals(count, undo.retainedEditCount()); assertEquals(bytes, undo.retainedBytes());
            assertEquals(beforeEvents, events); assertEquals(dot, area.getCaret().getDot()); assertEquals(mark, area.getCaret().getMark());
        }
    }

    @Test void staleRevisionDocumentSelectionAndCapabilityLeaveEveryInvariantUntouched() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            try {
                Fixture f = new Fixture(FileBuffer.createScratch("test.md", "- [ ] task"));
                assertThrows(javax.swing.text.BadLocationException.class, () -> f.edit(-1, 0));
                assertThrows(javax.swing.text.BadLocationException.class, () -> f.edit(2, 1));
                assertThrows(javax.swing.text.BadLocationException.class,
                    () -> f.edit(0, f.buffer.getDocument().getLength() + 1));
                assertEquals(0, f.events);
                assertFalse(f.undo.canUndo());
                MarkdownEdit staleRevision = f.edit(0, 5);
                f.buffer.getDocument().insertString(0, "a", null);
                f.rejected(staleRevision);
                f.undo.undo(); // A rejected edit must also preserve redo.
                f.rejected(staleRevision);
                MarkdownEdit readOnly = f.edit(0, 5);
                f.writable.set(false); f.rejected(readOnly); f.writable.set(true);
                MarkdownEdit staleSelection = f.edit(0, 5);
                f.area.select(1, 2); f.rejected(staleSelection);
                MarkdownEdit staleDocument = f.edit(0, 5);
                f.area.setDocument(new PlainDocument()); f.rejected(staleDocument);
                f.area.setDocument(f.buffer.getDocument());
                MarkdownEdit filtered = f.edit(0, 5);
                f.buffer.getDocument().setDocumentFilter(new javax.swing.text.DocumentFilter());
                f.rejected(filtered);
                f.buffer.getDocument().setDocumentFilter(null);
                f.undo.beginCompoundEdit();
                try { f.rejected(f.edit(0, 5)); }
                finally { f.undo.endCompoundEdit(); }
            } catch (Exception error) { throw new AssertionError(error); }
        });
    }

    @Test void atomicUndoKeepsPriorEditAndRedoAndRefusesOversizedCommands() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            try {
                Fixture f = new Fixture(FileBuffer.createScratch("test.md", "- [ ] task"));
                f.buffer.getDocument().insertString(f.buffer.getDocument().getLength(), "!", null);
                assertEquals("", f.edit(0, 10).apply("- [x] task", -1));
                assertEquals("- [x] task!", f.area.getText());
                assertEquals(2, f.undo.retainedEditCount());
                f.undo.undo(); assertEquals("- [ ] task!", f.area.getText());
                f.undo.undo(); assertEquals("- [ ] task", f.area.getText());
                f.undo.redo(); assertEquals("- [ ] task!", f.area.getText());
                f.undo.redo(); assertEquals("- [x] task!", f.area.getText());
                f.undo.configure(new UndoHistoryPolicy(10, 100));
                f.rejected(f.edit(0, 10));
            } catch (Exception error) { throw new AssertionError(error); }
        });
    }

    @Test void rangeEditPreservesCodecMetadataAndOriginalEofBytes() throws Exception {
        for (String newline : new String[]{"\n", "\r\n", "\r\nfirst\n"}) {
            for (boolean eof : new boolean[]{false, true}) {
                Path file = temp.resolve("codec-" + Math.abs(newline.hashCode()) + "-" + eof + ".md");
                String raw = "\ufeffpréface" + newline + "- [ ] \uD83D\uDC69\u200D\uD83D\uDCBB" + (eof ? "\r\n" : "");
                Files.write(file, raw.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                FileBuffer buffer = new FileBuffer(file.toFile());
                SwingUtilities.invokeAndWait(() -> {
                    try {
                        Fixture f = new Fixture(buffer);
                        int position = f.area.getText().indexOf("[ ]") + 1;
                        assertEquals("", f.edit(position, position + 1).apply("x", -1));
                        f.undo.undo(); assertEquals(raw.substring(1).replace("\r\n", "\n"), f.area.getText());
                        f.undo.redo();
                    } catch (Exception error) { throw new AssertionError(error); }
                });
                buffer.save();
                assertArrayEquals(raw.replace("[ ]", "[x]").getBytes(java.nio.charset.StandardCharsets.UTF_8), Files.readAllBytes(file));
                assertEquals(buffer.getContent(), new FileBuffer(file.toFile()).getContent());
            }
        }
    }
}
