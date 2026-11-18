package shed;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import javax.swing.JTextArea;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

public class UnicodeEditingRegressionTest {
    private static final List<String> CLUSTERS = List.of("e\u0301", "|", "👩🏽", "|", "👩‍💻", "|", "🇸🇬", "|", "🇺🇸", "|", "α", "|", "日", "|", "م",
        "|", "क्ष", "|", "क्‍ष", "|", "ক্ষ", "|", "ક્ષ", "|", "କ୍ଷ", "|", "క్ష", "|", "ക്ഷ", "|", "✈️", "|", "𝄞");
    private static final String FIXTURE = String.join("", CLUSTERS);

    @TempDir
    Path tempDir;

    @Test
    void conjunctDeletionPreservesBomCrLfSourceSnapshotAndSavedBytes() throws Exception {
        String disk = "\uFEFF**क्ष**\r\n";
        Path source = tempDir.resolve("conjunct.md"); Files.writeString(source, disk, StandardCharsets.UTF_8);
        FileBuffer buffer = new FileBuffer(source.toFile());
        javax.swing.SwingUtilities.invokeAndWait(() -> {
            JTextArea area = new JTextArea(buffer.getDocument());
            assertEquals("**क्ष**\n", buffer.getContent());
            var range = GraphemeEditRange.next(buffer.getContent(), 2);
            area.replaceRange("", range.start(), range.end());
            assertEquals("****\n", buffer.getContent());
            buffer.reconcileDocumentText();
            assertEquals(buffer.getContent(), buffer.textSnapshot().text());
            buffer.getUndoManager().undo(); assertEquals("**क्ष**\n", buffer.getContent());
            buffer.getUndoManager().redo(); assertEquals("****\n", buffer.getContent());
        });
        buffer.save();
        assertEquals("\uFEFF****\r\n", Files.readString(source, StandardCharsets.UTF_8));
        assertEquals(buffer.getContent(), new FileBuffer(source.toFile()).getContent());
    }

    @Test
    void singleCaretUnicodeFixtureSurvivesNavigationSelectionDeleteUndoSaveAndReload() throws Exception {
        assertNavigation();

        Path source = tempDir.resolve("unicode.txt");
        Files.writeString(source, FIXTURE, StandardCharsets.UTF_8);
        FileBuffer buffer = new FileBuffer(source.toFile());
        JTextArea area = new JTextArea(buffer.getDocument());
        int modifierStart = FIXTURE.indexOf("👩🏽");
        int flagStart = FIXTURE.indexOf("🇸🇬");
        GraphemeEditRange.Range selection = GraphemeEditRange.selection(FIXTURE, modifierStart + 1, flagStart + 1);
        area.setSelectionStart(selection.start());
        area.setSelectionEnd(selection.end());
        assertEquals("👩🏽|👩‍💻|🇸🇬", area.getSelectedText());

        int zwjStart = FIXTURE.indexOf("👩‍💻");
        GraphemeEditRange.Range deletion = GraphemeEditRange.next(buffer.getContent(), zwjStart);
        area.replaceRange("", deletion.start(), deletion.end());
        String deleted = FIXTURE.replace("👩‍💻", "");
        assertEquals(deleted, buffer.getContent());
        assertFalse(buffer.getContent().contains("\uFFFD"));

        assertTrue(buffer.getUndoManager().canUndo());
        buffer.getUndoManager().undo();
        assertEquals(FIXTURE, buffer.getContent());
        assertTrue(buffer.getUndoManager().canRedo());
        buffer.getUndoManager().redo();
        assertEquals(deleted, buffer.getContent());

        buffer.save();
        assertEquals(deleted, Files.readString(source, StandardCharsets.UTF_8));
        FileBuffer reloaded = new FileBuffer(source.toFile());
        assertEquals(deleted, reloaded.getContent());
        assertFalse(reloaded.getContent().contains("\uFFFD"));
    }

    private void assertNavigation() {
        int offset = 0;
        for (String cluster : CLUSTERS) {
            int next = GraphemeBoundary.next(FIXTURE, offset);
            assertEquals(cluster, FIXTURE.substring(offset, next));
            offset = next;
        }
        assertEquals(FIXTURE.length(), offset);

        for (int index = CLUSTERS.size() - 1; index >= 0; index--) {
            int previous = GraphemeBoundary.previous(FIXTURE, offset);
            assertEquals(CLUSTERS.get(index), FIXTURE.substring(previous, offset));
            offset = previous;
        }
        assertEquals(0, offset);
    }
}
