package shed;

import java.util.HashSet;
import java.util.Set;

/** Keeps line-based diff markers current for edits that do not change line boundaries. */
final class IncrementalDiffGutter {
    private static final int MAX_LINE_CHARS = 64 * 1024;

    private final String savedContent;
    private final VersionedTextSnapshot saved;
    private final Set<Integer> added;
    private final Set<Integer> modified;
    private final Set<Integer> deletedAfter;
    private VersionedTextSnapshot current;

    IncrementalDiffGutter(String savedContent, VersionedTextSnapshot saved,
                          VersionedTextSnapshot current, LineNumberPanel.DiffMarkers markers) {
        this.savedContent = savedContent;
        this.saved = saved;
        this.current = current;
        this.added = new HashSet<>(markers.added());
        this.modified = new HashSet<>(markers.modified());
        this.deletedAfter = new HashSet<>(markers.deletedAfter());
    }

    boolean matches(VersionedTextSnapshot text, String savedContent) {
        return current == text && this.savedContent == savedContent;
    }

    boolean apply(FileBuffer.DocumentTextChange change, String savedContent) {
        if (change == null || !change.incremental() || !matches(change.before(), savedContent)
            || change.insertedText().indexOf('\n') >= 0) return false;
        int line = change.before().positionAt(change.offset()).line();
        if (change.before().positionAt(change.offset() + change.removedLength()).line() != line
            || change.after().positionAt(change.offset()).line() != line) return false;
        int start = change.after().lineStartOffset(line);
        int end = change.after().lineEndOffset(line);
        if (end - start > MAX_LINE_CHARS) return false;

        current = change.after();
        added.remove(line);
        modified.remove(line);
        if (line >= saved.lineCount()) {
            added.add(line);
        } else if (!current.line(line).equals(saved.line(line))) {
            modified.add(line);
        }
        return true;
    }

    LineNumberPanel.DiffMarkers markers() {
        return new LineNumberPanel.DiffMarkers(added, modified, deletedAfter);
    }
}
