package shed;

import static org.junit.jupiter.api.Assertions.*;
import java.util.Random;
import java.util.Set;
import org.junit.jupiter.api.Test;

class WorkspaceTextEditsTest {
    @Test void selectedHunksAndUndoPreserveUnrelatedHumanChanges() {
        String base = "first\nmiddle\nlast\n", proposed = "FIRST\nmiddle\nLAST\n";
        assertEquals(2, WorkspaceTextEdits.diff(base, proposed).size());
        assertEquals("FIRST\nmiddle\nlast\n", WorkspaceTextEdits.selected(base, proposed, Set.of(0)));
        assertEquals("first\nhuman middle\nlast\n", WorkspaceTextEdits.rebase(proposed, base, "FIRST\nhuman middle\nLAST\n"));
        assertThrows(WorkspaceApiException.class, () -> WorkspaceTextEdits.rebase(proposed, base, "human first\nmiddle\nLAST\n"));
    }

    @Test void diffRoundTripsRepeatedLinesUnicodeAndRandomEdits() {
        String[] parts = {"a", "b", "\n", "👩‍💻", "é", "🇸🇬", "\t", ""};
        Random random = new Random(719);
        for (int trial = 0; trial < 500; trial++) {
            StringBuilder left = new StringBuilder(), right = new StringBuilder();
            for (int i = 0; i < random.nextInt(35); i++) left.append(parts[random.nextInt(parts.length)]);
            for (int i = 0; i < random.nextInt(35); i++) right.append(parts[random.nextInt(parts.length)]);
            String before = left.toString(), after = right.toString();
            var edits = WorkspaceTextEdits.diff(before, after);
            assertEquals(after, WorkspaceTextEdits.apply(before, edits), () -> "Failed: " + before + " -> " + after);
            for (var edit : edits) {
                assertTrue(GraphemeBoundary.isBoundary(before, edit.start())); assertTrue(GraphemeBoundary.isBoundary(before, edit.end()));
            }
        }
    }

    @Test void hugeDiffFallsBackToABoundedCoarseHunkAndRejectsOverlappingUndo() {
        String before = "a\n".repeat(2000), after = "b\n".repeat(2000);
        assertEquals(after, WorkspaceTextEdits.apply(before, WorkspaceTextEdits.diff(before, after)));
        assertEquals(1, WorkspaceTextEdits.diff(before, after).size());
        assertThrows(WorkspaceApiException.class, () -> WorkspaceTextEdits.rebase("a\n", "insert\na\n", "other\na\n"));
    }
}
