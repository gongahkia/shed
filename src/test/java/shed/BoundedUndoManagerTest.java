package shed;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import javax.swing.text.PlainDocument;
import org.junit.jupiter.api.Test;

public class BoundedUndoManagerTest {
    @Test
    void evictsOldestEditsAtEntryLimitWhileRetainedUndoRemainsValid() throws Exception {
        PlainDocument document = new PlainDocument();
        BoundedUndoManager manager = new BoundedUndoManager(new UndoHistoryPolicy(3, 1024));
        document.addUndoableEditListener(manager);

        append(document, "a");
        append(document, "b");
        append(document, "c");
        append(document, "d");

        assertEquals(3, manager.retainedEditCount());
        assertTrue(manager.retainedBytes() <= 1024);
        manager.undo();
        manager.undo();
        manager.undo();
        assertEquals("a", document.getText(0, document.getLength()));
        assertFalse(manager.canUndo());
        manager.redo();
        manager.redo();
        manager.redo();
        assertEquals("abcd", document.getText(0, document.getLength()));
    }

    @Test
    void evictsOldestEditsAtByteLimitWhileRetainedUndoRemainsValid() throws Exception {
        PlainDocument document = new PlainDocument();
        BoundedUndoManager manager = new BoundedUndoManager(new UndoHistoryPolicy(10, 80));
        document.addUndoableEditListener(manager);

        append(document, "a");
        append(document, "b");

        assertEquals(1, manager.retainedEditCount());
        assertTrue(manager.retainedBytes() <= 80);
        manager.undo();
        assertEquals("a", document.getText(0, document.getLength()));
        assertFalse(manager.canUndo());
    }

    @Test
    void applyingPolicyToExistingBufferTrimsRetainedHistory() throws Exception {
        ConfigManager config = new ConfigManager();
        config.set("undo.history.max.entries", "3");
        config.set("undo.history.max.bytes", "1024");
        FileBuffer buffer = new FileBuffer((String) null, config);

        append(buffer.getDocument(), "a");
        append(buffer.getDocument(), "b");
        append(buffer.getDocument(), "c");
        append(buffer.getDocument(), "d");
        BoundedUndoManager manager = (BoundedUndoManager) buffer.getUndoManager();
        assertEquals(3, manager.retainedEditCount());

        config.set("undo.history.max.entries", "1");
        buffer.applyUndoHistoryPolicy();

        assertEquals(new UndoHistoryPolicy(1, 1024), manager.policy());
        assertEquals(1, manager.retainedEditCount());
    }

    private void append(PlainDocument document, String text) throws Exception {
        document.insertString(document.getLength(), text, null);
    }
}
