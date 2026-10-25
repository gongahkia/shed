package shed;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Map;

/** Headless process fixture: exits without close/rollback after the original has moved. */
public final class WorkspaceEditCrashFixture {
    static final String ID = "proposal-" + "e".repeat(32);
    public static void main(String[] args) throws Exception {
        if (!Boolean.getBoolean("java.awt.headless")) throw new IllegalStateException("Fixture must be headless");
        var steps = new ArrayList<Object>();
        try (var lease = new WorkspaceChangeJournal(Path.of(args[1])).open(true)) {
            lease.append(ID, -1, "APPLYING", Map.of("steps", steps));
            int[] revision = {0};
            try (var transaction = new WorkspaceEditTransaction(step -> {
                steps.add(step.toMap());
                revision[0] = lease.append(ID, revision[0], "RESOURCE_PREPARED", Map.of("steps", steps)).revision();
                if (step.kind().equals("REPLACE")) Runtime.getRuntime().halt(77);
            })) {
                Path file = Path.of(args[0]).resolve("source");
                transaction.replace(file, transaction.stage(file, "agent".getBytes(StandardCharsets.UTF_8)));
            }
        }
        throw new IllegalStateException("Crash point was not reached");
    }
}
