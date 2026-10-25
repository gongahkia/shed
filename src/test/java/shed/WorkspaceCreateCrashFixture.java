package shed;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;

/** Runs the actual proposal executor and exits between filesystem effects and final journal commit. */
public final class WorkspaceCreateCrashFixture {
    public static void main(String[] args) {
        if (!Boolean.getBoolean("java.awt.headless")) throw new IllegalStateException("Headless fixture required");
        Path root = Path.of(args[0]), storage = Path.of(args[1]);
        var policy = new AgentPolicy(Set.of(AgentPolicy.Capability.READ, AgentPolicy.Capability.PROPOSE, AgentPolicy.Capability.APPLY),
            List.of("**/README.md"), List.of(), true);
        var queries = new WorkspaceQueryService(root, policy, WorkspaceDocumentSource.diskOnly());
        var service = new WorkspaceProposalService(queries, null, storage, "fixture");
        var proposal = service.propose("create", "task", "turn", List.of(WorkspaceProposalService.Input.create("new/source", "created\n")));
        String id = (String) proposal.get("id"); int revision = ((Number) proposal.get("revision")).intValue();
        if (args[2].equals("undo")) revision = ((Number) service.apply(id, revision).get("revision")).intValue();
        WorkspaceDocumentEditor crash = new WorkspaceDocumentEditor() {
            public List<WorkspaceDocument> snapshots() { return List.of(); }
            public List<WorkspaceDocument> applyTexts(List<TextChange> changes, List<Path> unbound) {
                Runtime.getRuntime().halt(77); throw new AssertionError("halt returned");
            }
        };
        service = new WorkspaceProposalService(queries, crash, storage, "fixture");
        if (args[2].equals("undo")) service.undo(id, revision); else service.apply(id, revision);
        throw new AssertionError("Crash point not reached");
    }
}
