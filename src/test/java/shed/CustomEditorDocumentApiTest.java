package shed;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import shed.api.CustomEditorDocument;

class CustomEditorDocumentApiTest {
    @Test
    void apiV1DocumentsRetainNoopLifecycleDefaults() {
        CustomEditorDocument document = new CustomEditorDocument() {
            @Override public Path file() { return Path.of("example.bin"); }
            @Override public byte[] bytes() { return new byte[] {1}; }
            @Override public boolean isBinary() { return true; }
            @Override public void write(byte[] replacement) throws IOException { }
        };
        boolean[] changed = {false};
        boolean[] disposed = {false};

        document.onDidChange(change -> changed[0] = true).close();
        document.onDidDispose(() -> disposed[0] = true).close();

        assertEquals(0L, document.revision());
        assertFalse(changed[0]);
        assertFalse(disposed[0]);
        assertFalse(document.canUndo());
        assertFalse(document.canRedo());
    }

    @Test
    void keepsBoundedCustomEditorByteHistory() {
        CustomEditorController.ByteHistory history = new CustomEditorController.ByteHistory();
        history.recordUndo(new byte[] {1});

        assertTrue(history.canUndo());
        assertEquals(1, history.undoTarget()[0]);
        history.completeUndo(new byte[] {2});
        assertTrue(history.canRedo());
        assertEquals(2, history.redoTarget()[0]);
        history.completeRedo(new byte[] {1});
        assertTrue(history.canUndo());
    }
}
