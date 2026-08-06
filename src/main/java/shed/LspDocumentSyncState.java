package shed;

import java.util.ArrayList;
import java.util.List;

final class LspDocumentSyncState {
    private VersionedTextSnapshot snapshot;
    private final List<LspDocumentChange> pendingChanges = new ArrayList<>();
    private boolean requiresFullSync;

    LspDocumentSyncState(String text) {
        this(VersionedTextSnapshot.of(text));
    }

    LspDocumentSyncState(VersionedTextSnapshot snapshot) {
        this.snapshot = snapshot == null ? VersionedTextSnapshot.empty() : snapshot;
        this.requiresFullSync = false;
    }

    boolean apply(int offset, int removedLength, String insertedText, String currentText) {
        String inserted = insertedText == null ? "" : insertedText;
        String current = currentText == null ? "" : currentText;
        if (offset < 0 || removedLength < 0 || offset > snapshot.length() || offset + removedLength > snapshot.length()) {
            resetTo(current);
            return false;
        }
        VersionedTextSnapshot updated = snapshot.replace(offset, removedLength, inserted);
        if (!updated.text().equals(current)) {
            resetTo(current);
            return false;
        }
        VersionedTextSnapshot.Position start = snapshot.positionAt(offset);
        VersionedTextSnapshot.Position end = snapshot.positionAt(offset + removedLength);
        pendingChanges.add(new LspDocumentChange(start.line(), start.character(), end.line(), end.character(), inserted));
        snapshot = updated;
        return true;
    }

    boolean apply(FileBuffer.DocumentTextChange change) {
        if (change == null || !change.incremental() || snapshot != change.before()) {
            resetTo(change == null ? snapshot : change.after());
            return false;
        }
        VersionedTextSnapshot.Position start = snapshot.positionAt(change.offset());
        VersionedTextSnapshot.Position end = snapshot.positionAt(change.offset() + change.removedLength());
        pendingChanges.add(new LspDocumentChange(start.line(), start.character(), end.line(), end.character(), change.insertedText()));
        snapshot = change.after();
        return true;
    }

    void reconcile(String currentText) {
        String current = currentText == null ? "" : currentText;
        if (!snapshot.text().equals(current)) {
            resetTo(current);
        }
    }

    void reconcile(VersionedTextSnapshot current) {
        VersionedTextSnapshot value = current == null ? VersionedTextSnapshot.empty() : current;
        if (snapshot != value) {
            resetTo(value);
        }
    }

    String text() {
        return snapshot.text();
    }

    int length() {
        return snapshot.length();
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
        resetTo(VersionedTextSnapshot.of(currentText));
    }

    private void resetTo(VersionedTextSnapshot current) {
        snapshot = current == null ? VersionedTextSnapshot.empty() : current;
        pendingChanges.clear();
        requiresFullSync = true;
    }
}
