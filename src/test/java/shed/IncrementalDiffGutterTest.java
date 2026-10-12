package shed;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class IncrementalDiffGutterTest {
    @Test
    void updatesOnlyTheEditedLineAndClearsItsMarkerWhenRestored() {
        String saved = "first\nsecond\nthird\n";
        VersionedTextSnapshot before = VersionedTextSnapshot.of(saved);
        IncrementalDiffGutter index = new IncrementalDiffGutter(saved, before, before,
            LineNumberPanel.diffMarkers(saved, saved));
        VersionedTextSnapshot changed = before.replace(6, 6, "edited");

        assertTrue(index.apply(new FileBuffer.DocumentTextChange(before, changed, 6, 6, "edited", true), saved));
        assertEquals(LineNumberPanel.diffMarkers(saved, changed.text()), index.markers());

        VersionedTextSnapshot restored = changed.replace(6, 6, "second");
        assertTrue(index.apply(new FileBuffer.DocumentTextChange(changed, restored, 6, 6, "second", true), saved));
        assertEquals(LineNumberPanel.diffMarkers(saved, restored.text()), index.markers());
    }

    @Test
    void structuralEditsRequireFullRebuild() {
        String saved = "first\nsecond\n";
        VersionedTextSnapshot before = VersionedTextSnapshot.of(saved);
        IncrementalDiffGutter index = new IncrementalDiffGutter(saved, before, before,
            LineNumberPanel.diffMarkers(saved, saved));
        VersionedTextSnapshot after = before.replace(5, 0, "\n");

        assertFalse(index.apply(new FileBuffer.DocumentTextChange(before, after, 5, 0, "\n", true), saved));
        assertTrue(index.matches(before, saved));
    }

    @Test
    void keepsOtherMarkersAfterAFullRebuildForAnEarlierLineInsertion() {
        String saved = "first\nsecond\nthird\n";
        VersionedTextSnapshot before = VersionedTextSnapshot.of("first\nnew line\nsecond\nthird\n");
        IncrementalDiffGutter index = new IncrementalDiffGutter(saved, VersionedTextSnapshot.of(saved), before,
            LineNumberPanel.diffMarkers(saved, before.text()));
        int offset = before.lineStartOffset(1);
        VersionedTextSnapshot after = before.replace(offset, 3, "newer");

        assertTrue(index.apply(new FileBuffer.DocumentTextChange(before, after, offset, 3, "newer", true), saved));
        assertEquals(LineNumberPanel.diffMarkers(saved, after.text()), index.markers());
    }
}
