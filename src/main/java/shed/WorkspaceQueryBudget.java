package shed;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.function.BooleanSupplier;

/** Cooperative limits shared by discovery, ignore parsing and snapshot search. */
final class WorkspaceQueryBudget {
    static final int MAX_ENTRIES = 100_000;
    static final int MAX_PATH_CHARACTERS = 8 * 1024 * 1024;
    static final int MAX_TEXT_CHARACTERS = 32 * 1024 * 1024;
    static final int MAX_RESPONSE_CHARACTERS = 768 * 1024;
    private final long deadline;
    private final BooleanSupplier cancelled;
    private int entries;
    private long paths, text;
    private final Map<String, Integer> counts = new LinkedHashMap<>();
    private final Set<String> reasons = new LinkedHashSet<>();

    WorkspaceQueryBudget(int timeoutMillis, BooleanSupplier cancelled) {
        if (timeoutMillis < 1 || timeoutMillis > 10_000) throw WorkspaceApiException.invalidRequest("timeoutMillis must be between 1 and 10000");
        this.deadline = System.nanoTime() + timeoutMillis * 1_000_000L;
        this.cancelled = java.util.Objects.requireNonNull(cancelled);
    }

    void check() {
        if (Thread.currentThread().isInterrupted() || cancelled.getAsBoolean())
            throw new WorkspaceApiException(WorkspaceApiException.Code.CANCELLED, "Workspace query cancelled");
        if (System.nanoTime() > deadline) stop("TIME_LIMIT");
    }
    boolean cancelled() { check(); return false; }
    void visit(String path) {
        check();
        if (++entries > MAX_ENTRIES) stop("ENTRY_LIMIT");
        if ((paths += path.length()) > MAX_PATH_CHARACTERS) stop("PATH_LIMIT");
    }
    void text(int characters) {
        check();
        if ((text += characters) > MAX_TEXT_CHARACTERS) stop("TEXT_LIMIT");
    }
    void count(String name) { counts.merge(name, 1, Integer::sum); }
    void incomplete(String reason) { reasons.add(reason); }
    void stop(String reason) { incomplete(reason); throw new Stopped(); }
    boolean complete() { return reasons.isEmpty(); }
    Map<String, Object> metadata() {
        return Map.of("complete", complete(), "incompleteReasons", reasons.stream().sorted().toList(),
            "counts", Map.copyOf(counts), "visitedEntries", Math.min(entries, MAX_ENTRIES), "snapshotCharacters", text);
    }
    static final class Stopped extends RuntimeException {
        Stopped() { super(null, null, false, false); }
    }
}
