package shed;

import java.nio.file.Path;
import java.util.List;

/** Headless process interruption at real, durably recorded rename execution boundaries. */
public final class WorkspaceRenameCrashFixture {
    public static void main(String[] args) {
        if (!Boolean.getBoolean("java.awt.headless")) throw new IllegalStateException("Headless fixture required");
        Path root = Path.of(args[0]), history = Path.of(args[1]); String phase = args[2];
        var queries = WorkspaceDeletionProposalTest.queries(root, WorkspaceDocumentSource.diskOnly());
        var service = new WorkspaceProposalService(queries, null, history, "fixture");
        WorkspaceEditTransaction.StepObserver linkedCrash = step -> {
            if (phase.equals("unrecorded-link") && step.kind().equals("RENAME_FILE")) {
                java.nio.file.Files.createLink(step.target(), step.source()); Runtime.getRuntime().halt(77);
            }
            if (step.kind().equals("UNLINK_SOURCE")) Runtime.getRuntime().halt(77);
        };
        if (phase.equals("restore-linked")) {
            var proposal = service.list(0, 10).getFirst(); String id = (String) proposal.get("id"); var inspection = service.recovery(id);
            new WorkspaceProposalService(queries, null, history, "fixture", linkedCrash).restore(id,
                ((Number) proposal.get("revision")).intValue(), (String) inspection.get("fingerprint"));
            throw new AssertionError("Restore crash point not reached");
        }
        var proposal = service.propose("rename", "task", "turn", List.of(WorkspaceProposalService.Input.rename("source",
            queries.snapshot(queries.workspaceId(), "source").version(), "renamed")));
        String id = (String) proposal.get("id"); int revision = ((Number) proposal.get("revision")).intValue();
        if (phase.contains("undo")) revision = ((Number) service.apply(id, revision).get("revision")).intValue();
        WorkspaceDocumentEditor crash = new WorkspaceDocumentEditor() {
            public List<WorkspaceDocument> snapshots() { return List.of(); }
            public List<WorkspaceDocument> applyTexts(List<TextChange> changes, List<Path> unbound) {
                Runtime.getRuntime().halt(77); throw new AssertionError("halt returned");
            }
        };
        service = phase.startsWith("linked") || phase.equals("unrecorded-link") ? new WorkspaceProposalService(queries, null, history, "fixture", linkedCrash)
            : new WorkspaceProposalService(queries, crash, history, "fixture");
        if (phase.contains("undo")) service.undo(id, revision); else service.apply(id, revision);
        throw new AssertionError("Rename crash point not reached");
    }
}
