package shed;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class ProblemsServiceTest {
    @Test
    void retainsLatestEntriesPerQuickfixSource() {
        ProblemsService service = new ProblemsService();
        service.recordQuickfixEntries(List.of(new QuickfixService.Entry("old.java", 2, 1, "old", "task:test")));
        service.recordQuickfixEntries(List.of(new QuickfixService.Entry("new.java", 4, 3, "new", "task:test")));
        service.recordQuickfixEntries(List.of(new QuickfixService.Entry("search.txt", 1, 1, "hit", "workspace-search")));

        List<ProblemsService.Problem> snapshot = service.snapshot(List.of());
        assertEquals(2, snapshot.size());
        assertTrue(snapshot.stream().anyMatch(problem -> problem.filePath().equals("new.java")));
        assertFalse(snapshot.stream().anyMatch(problem -> problem.filePath().equals("old.java")));
        assertTrue(snapshot.stream().anyMatch(problem -> problem.filePath().equals("search.txt")));
    }

    @Test
    void usesLiveLspDiagnosticsInsteadOfQuickfixCopiesAndDeduplicates() {
        ProblemsService service = new ProblemsService();
        service.recordQuickfixEntries(List.of(new QuickfixService.Entry("App.java", 8, 2, "missing name", "diag-error")));
        ProblemsService.Problem live = new ProblemsService.Problem("App.java", 8, 2, "missing name", "lsp", ProblemsService.Severity.ERROR);

        List<ProblemsService.Problem> snapshot = service.snapshot(List.of(live, live));

        assertEquals(1, snapshot.size());
        assertEquals(ProblemsService.Severity.ERROR, snapshot.getFirst().severity());
    }

    @Test
    void clearsOnlyTheNamedQuickfixSource() {
        ProblemsService service = new ProblemsService();
        service.recordQuickfixEntries(List.of(
            new QuickfixService.Entry("test.java", 1, 1, "failure", "task:test"),
            new QuickfixService.Entry("file.txt", 2, 1, "match", "workspace-search")
        ));

        service.clearQuickfixSource("task:test");

        List<ProblemsService.Problem> snapshot = service.snapshot(List.of());
        assertEquals(1, snapshot.size());
        assertEquals("workspace-search", snapshot.getFirst().source());
    }

    @Test
    void capsOneLargeProducerWithoutDroppingItsEntireLatestResult() {
        ProblemsService service = new ProblemsService();
        java.util.ArrayList<QuickfixService.Entry> entries = new java.util.ArrayList<>();
        for (int index = 0; index < ProblemsService.MAX_RETAINED_ENTRIES + 4; index++) {
            entries.add(new QuickfixService.Entry("bulk.txt", index + 1, 1, "entry " + index, "workspace-search"));
        }

        service.recordQuickfixEntries(entries);

        assertEquals(ProblemsService.MAX_RETAINED_ENTRIES, service.retainedEntryCount());
        assertEquals(ProblemsService.MAX_RETAINED_ENTRIES, service.snapshot(List.of()).size());
    }
}
