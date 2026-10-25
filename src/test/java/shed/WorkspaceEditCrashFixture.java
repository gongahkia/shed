package shed;

import java.nio.file.Path;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Headless process fixture: exits without close/rollback after the original has moved. */
public final class WorkspaceEditCrashFixture {
    static final String ID = "proposal-" + WorkspacePaths.hash("fixture\ncrash").substring(0, 32);
    public static void main(String[] args) throws Exception {
        if (!Boolean.getBoolean("java.awt.headless")) throw new IllegalStateException("Fixture must be headless");
        Path root = WorkspacePaths.root(Path.of(args[0])), history = Path.of(args[1]), file = root.resolve("source");
        boolean restore = args.length > 2 && args[2].equals("restore");
        var policy = new AgentPolicy(Set.of(AgentPolicy.Capability.READ, AgentPolicy.Capability.PROPOSE), List.of("**/README.md"), List.of(), true);
        var queries = new WorkspaceQueryService(root, policy, WorkspaceDocumentSource.diskOnly());
        var base = WorkspaceTextFile.capture(queries.workspaceId(), file, true);
        new WorkspaceProposalService(queries, null, history, "fixture").propose("crash", "fixture", "turn",
            List.of(new WorkspaceProposalService.Input("source", base.document().version(), "agent")));
        byte[] desired = base.image().encode("agent");
        if (restore) Files.write(file, desired);
        var steps = new ArrayList<Object>();
        try (var lease = new WorkspaceChangeJournal(history).open(true)) {
            var initial = lease.read(ID); var data = new LinkedHashMap<>(initial.data());
            data.put("operation", List.of(Map.of("document", base.document().documentId(), "uri", file.toUri().toString(), "source", "DISK",
                "version", base.document().version(), "before", lease.putText(base.document().content()), "after", lease.putText("agent"),
                "diskBefore", lease.put(base.bytes()), "diskAfter", lease.put(desired))));
            data.put("operationKind", "APPLY"); data.put("approvedRevision", 0); data.put("resourceSteps", List.of());
            data.put("status", restore ? "RECOVERY_REQUIRED" : "APPLYING");
            int[] revision = {lease.append(ID, initial.revision(), restore ? "RESTORING" : "APPLYING", data).revision()};
            try (var transaction = new WorkspaceEditTransaction(step -> {
                steps.add(step.toMap());
                data.put(restore ? "restoreSteps" : "resourceSteps", List.copyOf(steps));
                revision[0] = lease.append(ID, revision[0], restore ? "RESTORE_PREPARED" : "RESOURCE_PREPARED", data).revision();
                if (step.kind().equals("REPLACE")) Runtime.getRuntime().halt(77);
            })) {
                transaction.replace(file, transaction.stage(file, restore ? base.bytes() : desired));
            }
        }
        throw new IllegalStateException("Crash point was not reached");
    }
}
