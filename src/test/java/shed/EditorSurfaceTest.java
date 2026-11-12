package shed;

import static org.junit.jupiter.api.Assertions.*;
import javax.swing.*;
import org.junit.jupiter.api.Test;

class EditorSurfaceTest {
    @Test void twoSurfacesShareDocumentUndoButRetainLiveAnchorsAndIndependentSelections() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            FileBuffer document = new FileBuffer((String) null);
            JTextArea left = new JTextArea();
            JTextArea right = new JTextArea();
            left.setDocument(document.getDocument()); right.setDocument(document.getDocument());
            EditorSurface a = new SwingEditorSurface(left, new JScrollPane(left), document::getUndoManager);
            EditorSurface b = new SwingEditorSurface(right, new JScrollPane(right), document::getUndoManager);
            a.replace(0, 0, "alpha beta");
            document.getUndoManager().discardAllEdits();
            a.select(8, 6);
            EditorSurface.View saved = a.captureView();
            b.select(0, 0);
            b.replace(0, 0, "XX");
            saved.restoreSelection(a);
            assertEquals(new EditorSurface.Selection(10, 8), a.selection());
            assertNotEquals(a.selection(), b.selection());
            assertEquals(left.getText(), right.getText());
            a.undo();
            assertEquals("alpha beta", right.getText());
            b.redo();
            assertEquals("XXalpha beta", left.getText());
            saved.restoreSelection(a);
            assertEquals(new EditorSurface.Selection(10, 8), a.selection());
            assertTrue(document.documentRevision() > 0);
        });
    }

    @Test void projectedReadOnlySurfaceRejectsProgrammaticEdits() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            JTextArea area = new JTextArea("projection");
            EditorSurface surface = new SwingEditorSurface(area, new JScrollPane(area), () -> null, () -> false);
            assertThrows(IllegalStateException.class, () -> surface.replace(0, 1, "change"));
            assertEquals("projection", area.getText());
        });
    }

    @Test void offsetsAreUtf16AndSelectionDirectionIsPreserved() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            JTextArea area = new JTextArea("a😀z");
            EditorSurface surface = new SwingEditorSurface(area, new JScrollPane(area), () -> null);
            assertEquals(4, surface.length());
            surface.select(1, 3);
            assertEquals(new EditorSurface.Selection(1, 3), surface.selection());
            surface.select(100, -2);
            assertEquals(new EditorSurface.Selection(4, 0), surface.selection());
        });
    }
}
