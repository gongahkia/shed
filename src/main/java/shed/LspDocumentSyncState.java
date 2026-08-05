package shed;

import java.util.ArrayList;
import java.util.List;

final class LspDocumentSyncState {
    private String text;
    private final List<LspDocumentChange> pendingChanges = new ArrayList<>();
    private boolean requiresFullSync;

    LspDocumentSyncState(String text) {
        this.text = text == null ? "" : text;
        this.requiresFullSync = false;
    }

    boolean apply(int offset, int removedLength, String insertedText, String currentText) {
        String inserted = insertedText == null ? "" : insertedText;
        String current = currentText == null ? "" : currentText;
        if (offset < 0 || removedLength < 0 || offset > text.length() || offset + removedLength > text.length()) {
            resetTo(current);
            return false;
        }
        Position start = positionAt(text, offset);
        Position end = positionAt(text, offset + removedLength);
        String updated = text.substring(0, offset) + inserted + text.substring(offset + removedLength);
        if (!updated.equals(current)) {
            resetTo(current);
            return false;
        }
        pendingChanges.add(new LspDocumentChange(start.line(), start.character(), end.line(), end.character(), inserted));
        text = updated;
        return true;
    }

    void reconcile(String currentText) {
        String current = currentText == null ? "" : currentText;
        if (!text.equals(current)) {
            resetTo(current);
        }
    }

    String text() {
        return text;
    }

    boolean requiresFullSync() {
        return requiresFullSync;
    }

    List<LspDocumentChange> drainChanges() {
        List<LspDocumentChange> changes = List.copyOf(pendingChanges);
        pendingChanges.clear();
        requiresFullSync = false;
        return changes;
    }

    boolean hasPendingChanges() {
        return requiresFullSync || !pendingChanges.isEmpty();
    }

    private void resetTo(String currentText) {
        text = currentText;
        pendingChanges.clear();
        requiresFullSync = true;
    }

    private static Position positionAt(String value, int offset) {
        int line = 0;
        int lineStart = 0;
        for (int index = 0; index < offset; index++) {
            if (value.charAt(index) == '\n') {
                line++;
                lineStart = index + 1;
            }
        }
        return new Position(line, offset - lineStart);
    }

    private record Position(int line, int character) { }
}
