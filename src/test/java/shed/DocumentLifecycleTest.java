package shed;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import javax.swing.JOptionPane;
import org.junit.jupiter.api.Test;

public class DocumentLifecycleTest {
    @Test
    void cancelledDiscardKeepsDirtyBufferEligibleForConfirmation() {
        FileBuffer dirty = FileBuffer.createScratch("[draft]", "draft");
        dirty.setContent("changed");

        assertTrue(DocumentLifecycle.needsDiscardConfirmation(dirty, false));
        assertFalse(DocumentLifecycle.discardConfirmed(JOptionPane.NO_OPTION));
        assertFalse(DocumentLifecycle.discardConfirmed(JOptionPane.CLOSED_OPTION));
        assertTrue(dirty.isModified());
        assertTrue(DocumentLifecycle.needsDiscardConfirmation(dirty, false));
    }

    @Test
    void cleanOrForcedBuffersDoNotRequireDiscardConfirmation() {
        FileBuffer clean = FileBuffer.createScratch("[clean]", "");
        FileBuffer dirty = FileBuffer.createScratch("[dirty]", "");
        dirty.setContent("changed");

        assertFalse(DocumentLifecycle.needsDiscardConfirmation(clean, false));
        assertFalse(DocumentLifecycle.needsDiscardConfirmation(dirty, true));
        assertTrue(DocumentLifecycle.discardConfirmed(JOptionPane.YES_OPTION));
    }
}
