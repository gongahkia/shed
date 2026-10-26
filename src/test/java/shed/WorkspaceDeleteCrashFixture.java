package shed;

import java.nio.file.Path;
import java.util.List;

/** Exits from the actual executor between filesystem completion and final editor/journal commit. */
public final class WorkspaceDeleteCrashFixture {
    public static void main(String[] args) {
        if (!Boolean.getBoolean("java.awt.headless")) throw new IllegalStateException("Headless fixture required");
        Path root = Path.of(args[0]), history = Path.of(args[1]);
        var queries = WorkspaceDeletionProposalTest.queries(root, WorkspaceDocumentSource.diskOnly());
        var service = new WorkspaceProposalService(queries, null, history, "fixture");
        var proposal = service.propose("delete", "task", "turn", List.of(WorkspaceProposalService.Input.delete("source", queries.snapshot(queries.workspaceId(), "source").version())));
        String id = (String) proposal.get("id"); int revision = ((Number) proposal.get("revision")).intValue();
        if (args[2].equals("undo")) revision = ((Number) service.apply(id, revision).get("revision")).intValue();
        WorkspaceDocumentEditor crash = new WorkspaceDocumentEditor() {
            public List<WorkspaceDocument> snapshots() { return List.of(); }
            public List<WorkspaceDocument> applyTexts(List<TextChange> changes, List<Path> unbound) {
                Runtime.getRuntime().halt(77); throw new AssertionError("halt returned");
            }
        };
        service = new WorkspaceProposalService(queries, crash, history, "fixture");
        if (args[2].equals("undo")) service.undo(id, revision); else service.apply(id, revision);
        throw new AssertionError("Crash point not reached");
    }
}
