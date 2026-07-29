package shed;

import javax.swing.JOptionPane;

final class DocumentLifecycle {
    private DocumentLifecycle() {
    }

    static boolean needsDiscardConfirmation(FileBuffer buffer, boolean force) {
        return !force && buffer != null && buffer.isModified();
    }

    static boolean discardConfirmed(int option) {
        return option == JOptionPane.YES_OPTION;
    }
}
