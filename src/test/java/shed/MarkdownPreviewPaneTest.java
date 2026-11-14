package shed;

import static org.junit.jupiter.api.Assertions.*;

import java.awt.Color;
import java.awt.Font;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MarkdownPreviewPaneTest {
    @TempDir Path temporary;

    private static final class CountingBuffer extends FileBuffer {
        int captures;
        CountingBuffer(File file) throws IOException { super(file); }
        @Override public String getFullContent() { captures++; return super.getFullContent(); }
    }

    @Test void openingCapturesAndRendersOnceWithoutChangingSourceAndExplicitRefreshStillWorks() throws Exception {
        Path file = Files.writeString(temporary.resolve("preview.md"), "# Original\n\ntext\n");
        SwingUtilities.invokeAndWait(() -> {
            try {
                CountingBuffer buffer = new CountingBuffer(file.toFile());
                JTextArea area = new JTextArea(); area.setDocument(buffer.getDocument());
                EditorPane source = new EditorPane(area, null, new JScrollPane(area), null);
                source.setBuffer(buffer);
                long revision = buffer.documentRevision();
                VersionedTextSnapshot snapshot = buffer.textSnapshot();
                String saved = buffer.getSavedContent();
                int listeners = buffer.getDocument().getDocumentListeners().length;
                buffer.captures = 0;
                MarkdownPreviewPane preview = new MarkdownPreviewPane(buffer,
                    () -> new Font(Font.DIALOG, Font.PLAIN, 13), () -> Color.WHITE, () -> Color.BLACK,
                    href -> { }, () -> { }, () -> false, () -> false, source, null);
                try {
                    assertEquals(1, buffer.captures, "Opening must not render the same source twice");
                    assertTrue(preview.getHtml().contains("id=\"original\""));
                    assertEquals(revision, buffer.documentRevision());
                    assertSame(snapshot, buffer.textSnapshot());
                    assertEquals(saved, buffer.getSavedContent());
                    assertFalse(buffer.isModified());
                    assertFalse(buffer.getUndoManager().canUndo());
                    assertEquals("# Original\n\ntext\n", area.getText());
                    area.append("\n## Updated\n");
                    long editedRevision = buffer.documentRevision();
                    preview.refreshNow();
                    assertEquals(2, buffer.captures, "Explicit refresh must still capture current source");
                    assertTrue(preview.getHtml().contains("id=\"updated\""));
                    assertEquals(editedRevision, buffer.documentRevision());
                } finally { preview.disposePreview(); }
                assertEquals(listeners, buffer.getDocument().getDocumentListeners().length);
            } catch (Exception error) { throw new AssertionError(error); }
        });
    }
}
