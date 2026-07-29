package shed;

import java.util.IdentityHashMap;
import java.util.Map;
import javax.swing.event.DocumentEvent;
import javax.swing.undo.UndoManager;
import javax.swing.undo.UndoableEdit;

final class BoundedUndoManager extends UndoManager {
    private static final long EDIT_OVERHEAD_BYTES = 64L;
    private final Map<UndoableEdit, Long> editBytes = new IdentityHashMap<>();
    private long maxBytes = UndoHistoryPolicy.DEFAULT_MAX_BYTES;

    BoundedUndoManager(UndoHistoryPolicy policy) {
        configure(policy);
    }

    synchronized void configure(UndoHistoryPolicy policy) {
        maxBytes = policy.maxBytes();
        setLimit(policy.maxEntries());
        trimToLimits();
    }

    synchronized UndoHistoryPolicy policy() {
        return new UndoHistoryPolicy(getLimit(), maxBytes);
    }

    synchronized int retainedEditCount() {
        return edits.size();
    }

    synchronized long retainedBytes() {
        return totalBytes();
    }

    @Override
    public synchronized boolean addEdit(UndoableEdit edit) {
        long estimatedBytes = estimateBytes(edit);
        boolean accepted = super.addEdit(edit);
        if (accepted && edits.contains(edit)) {
            editBytes.put(edit, estimatedBytes);
        }
        trimToLimits();
        return accepted;
    }

    @Override
    public synchronized void discardAllEdits() {
        super.discardAllEdits();
        editBytes.clear();
    }

    @Override
    protected synchronized void trimForLimit() {
        trimToLimits();
    }

    @Override
    protected synchronized void trimEdits(int from, int to) {
        super.trimEdits(from, to);
        editBytes.keySet().removeIf(edit -> !edits.contains(edit));
    }

    private void trimToLimits() {
        while (edits.size() > getLimit()) {
            trimEdits(0, 0);
        }
        while (totalBytes() > maxBytes && !edits.isEmpty()) {
            trimEdits(0, 0);
        }
    }

    private long totalBytes() {
        long total = 0L;
        for (UndoableEdit edit : edits) {
            total = Math.addExact(total, editBytes.getOrDefault(edit, estimateBytes(edit)));
        }
        return total;
    }

    private long estimateBytes(UndoableEdit edit) {
        if (edit instanceof DocumentEvent event) {
            return Math.addExact(EDIT_OVERHEAD_BYTES, Math.multiplyExact(event.getLength(), 2L));
        }
        return EDIT_OVERHEAD_BYTES;
    }
}
