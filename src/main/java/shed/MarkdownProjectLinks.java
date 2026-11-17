package shed;

import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import static shed.MarkdownLink.*;

/** Headless EDT facade. Explicit requests alone create partitions and the isolated bounded worker. */
final class MarkdownProjectLinks implements AutoCloseable {
    interface Builder {
        MarkdownLinkIndex.Snapshot build(Path root, List<Path> roots, MarkdownLinkIndex.Snapshot previous,
            List<MarkdownLinkIndex.Overlay> overlays, Set<Path> force, java.util.function.BooleanSupplier stop) throws Exception;
    }
    record Diagnostics(long submitted, long coalesced, long staleRejected, int partitions, int running, int pending) { }
    private static final class Partition {
        long generation;
        boolean pending, forceLimited;
        final Set<Path> force = new HashSet<>();
        State state = State.PENDING;
        MarkdownLinkIndex.Snapshot snapshot;
    }
    private record Work(Path root, long generation, List<MarkdownLinkIndex.Overlay> sources, AtomicBoolean stop, boolean truncated, Set<Path> forced) { }
    private final Supplier<List<Path>> roots;
    private final Supplier<List<MarkdownLinkIndex.Overlay>> overlays;
    private final Builder builder;
    private final Map<Path, Partition> partitions = new LinkedHashMap<>();
    private final Timer debounce;
    private AsyncJobService jobs;
    private Work running;
    private boolean closed;
    private long submitted, coalesced, staleRejected;
    private Runnable changed = () -> { };

    MarkdownProjectLinks(Supplier<List<Path>> roots, Supplier<List<MarkdownLinkIndex.Overlay>> overlays) {
        this(roots, overlays, MarkdownLinkIndex::build);
    }
    MarkdownProjectLinks(Supplier<List<Path>> roots, Supplier<List<MarkdownLinkIndex.Overlay>> overlays, Builder builder) {
        edt(); this.roots = roots; this.overlays = overlays; this.builder = builder;
        debounce = new Timer(180, event -> startNext()); debounce.setRepeats(false);
    }
    void onChanged(Runnable listener) { edt(); changed = Objects.requireNonNull(listener); }

    /** Request a configured root. Repeated initial requests coalesce; refresh explicitly detects closed-file changes. */
    boolean request(Path root) {
        edt(); if (closed) return false; reconcileRoots(); root = root.toAbsolutePath().normalize();
        if (!roots.get().contains(root)) return false;
        if (partitions.containsKey(root)) { coalesced++; return true; }
        if (partitions.size() >= 4) return false;
        var partition = new Partition(); partition.pending = true; partitions.put(root, partition); startNext(); return true;
    }
    void refresh(Path root) {
        edt(); if (!request(root)) return;
        invalidate(partitions.get(root.toAbsolutePath().normalize()), null); startNext();
    }
    /** Called only by established document/save/external-change/close notifications, after source state updates. */
    void documentsChanged(Path path, boolean diskChanged) {
        edt(); if (closed || partitions.isEmpty()) return;
        reconcileRoots();
        for (Partition partition : partitions.values()) invalidate(partition, diskChanged ? path : null);
        debounce.restart();
    }
    private void invalidate(Partition partition, Path force) {
        partition.generation++; partition.pending = true; if (force != null) {
            if (partition.force.size() < 64) partition.force.add(force.toAbsolutePath().normalize());
            else partition.forceLimited = true;
        } partition.state = State.PENDING;
        if (running != null && partitions.get(running.root()) == partition) running.stop().set(true);
    }
    void reconcileRoots() {
        edt(); Set<Path> current = new HashSet<>(roots.get());
        for (Path root : List.copyOf(partitions.keySet())) if (!current.contains(root)) closeScope(root);
    }
    private void startNext() {
        edt(); if (closed || running != null) return;
        for (var entry : partitions.entrySet()) {
            var partition = entry.getValue(); if (!partition.pending) continue;
            var all = overlays.get(); boolean truncated = all.size() > 64;
            var sources = List.copyOf(all.subList(0, Math.min(64, all.size())));
            var work = new Work(entry.getKey(), partition.generation, sources, new AtomicBoolean(), truncated || partition.forceLimited, Set.copyOf(partition.force));
            var previous = partition.snapshot; Set<Path> force = work.forced();
            var configured = List.copyOf(roots.get());
            partition.pending = false; partition.force.clear(); partition.forceLimited = false; running = work;
            if (jobs == null) jobs = new AsyncJobService(new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(1), task -> { var thread = new Thread(task, "shed-markdown-links"); thread.setDaemon(true); return thread; },
                new ThreadPoolExecutor.AbortPolicy()), 10, null);
            submitted++;
            Path scope = work.root(); long generation = work.generation();
            jobs.submit("Markdown project links", token -> builder.build(scope, configured, previous, sources, force,
                () -> work.stop().get() || token.isCancelled()), (job, result, error) -> finish(scope, generation, result, error));
            return;
        }
    }
    private void finish(Path root, long generation, MarkdownLinkIndex.Snapshot result, Exception error) {
        edt(); if (running == null || !running.root().equals(root) || running.generation() != generation) return;
        Work work = running; running = null; var partition = partitions.get(root);
        if (!closed && partition != null && partition.generation == generation && !work.stop().get()) {
            boolean current = sameSources(work.sources(), overlays.get());
            if (!current) { staleRejected++; invalidate(partition, null); }
            else if (error != null || result == null) partition.state = error instanceof CancellationException ? State.CANCELLED : State.FAILED;
            else {
                partition.snapshot = result;
                partition.state = work.truncated() ? State.PARTIAL : result.state();
            }
        } else {
            staleRejected++;
            if (partition != null && partition.pending) {
                for (Path path : work.forced()) {
                    if (partition.force.size() < 64) partition.force.add(path); else partition.forceLimited = true;
                }
            }
        }
        if (!closed) { startNext(); changed.run(); }
    }
    private static boolean sameSources(List<MarkdownLinkIndex.Overlay> captured, List<MarkdownLinkIndex.Overlay> current) {
        if (captured.size() != Math.min(64, current.size())) return false;
        for (int i = 0; i < captured.size(); i++) {
            var old = captured.get(i); var now = current.get(i);
            if (!old.path().equals(now.path()) || !old.source().matches(now.source())) return false;
        }
        return true;
    }
    Query outgoing(Path root, Path source) { edt(); return query(root, source, false); }
    Query backlinks(Path root, Path target) { edt(); return query(root, target, true); }
    private Query query(Path root, Path file, boolean incoming) {
        var partition = partitions.get(root.toAbsolutePath().normalize());
        if (partition == null) return new Query(closed ? State.CLOSED : State.PENDING, List.of(), List.of(), "Project not requested");
        if (partition.state == State.PENDING || partition.state == State.CANCELLED || partition.state == State.FAILED || partition.snapshot == null)
            return new Query(partition.state, List.of(), List.of(), "No confirmed current index; request/refresh required");
        Query query = incoming ? partition.snapshot.backlinks(file) : partition.snapshot.outgoing(file);
        return new Query(partition.state, query.edges(), query.incompleteFiles(), query.detail()
            + (partition.state != query.state() ? "; Facade source/invalidation admission limit" : ""));
    }
    Optional<MarkdownLinkIndex.Metrics> metrics(Path root) {
        edt(); var partition = partitions.get(root.toAbsolutePath().normalize());
        return partition == null || partition.snapshot == null ? Optional.empty() : Optional.of(partition.snapshot.metrics());
    }
    /** Resolve only an occurrence in the currently installed revision; no EDT filesystem access. */
    Optional<Resolution> resolve(Path root, Occurrence occurrence) {
        return outgoing(root, occurrence.source().file()).edges().stream().filter(e -> e.occurrence().equals(occurrence)).map(Edge::resolution).findFirst();
    }
    void cancel(Path root) {
        edt(); var partition = partitions.get(root.toAbsolutePath().normalize()); if (partition == null) return;
        partition.generation++; partition.pending = false; partition.state = State.CANCELLED;
        if (running != null && partitions.get(running.root()) == partition) running.stop().set(true);
    }
    void closeScope(Path root) {
        edt(); root = root.toAbsolutePath().normalize(); partitions.remove(root);
        if (running != null && running.root().equals(root)) running.stop().set(true);
        if (partitions.isEmpty() && jobs != null) { jobs.shutdownNow(); jobs = null; }
    }
    Diagnostics diagnostics() { edt(); return new Diagnostics(submitted, coalesced, staleRejected, partitions.size(), running == null ? 0 : 1,
        (int) partitions.values().stream().filter(p -> p.pending).count()); }
    @Override public void close() {
        edt(); if (closed) return; closed = true; debounce.stop(); partitions.clear();
        if (running != null) running.stop().set(true); running = null; changed = () -> { };
        if (jobs != null) { jobs.shutdownNow(); jobs = null; }
    }
    private static void edt() { if (!SwingUtilities.isEventDispatchThread()) throw new IllegalStateException("Markdown link facade requires EDT"); }
}
