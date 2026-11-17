package shed;

import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.BooleanSupplier;
import static shed.MarkdownMedia.*;
import shed.MarkdownSemanticSnapshot.Source;

/** Explicitly owned, lazy service. Poll/take results; no EDT callbacks or document listeners are installed. */
final class MarkdownMediaService implements AutoCloseable {
    record Budget(int queued, int consumers, int sessions, int entries, long cacheBytes, long residentBytes, long residentPixels) {
        Budget {
            if (queued < 1 || queued > 32 || consumers < 1 || consumers > 256 || sessions < 1 || sessions > 64 || entries < 1 || entries > 128
                    || cacheBytes < 1 || residentBytes < cacheBytes || residentBytes > 64L * 1024 * 1024
                    || residentPixels < 1 || residentPixels > 16L * 1024 * 1024) throw new IllegalArgumentException("Invalid media budget");
        }
        static Budget desktop() { return new Budget(8, 64, 16, 32, 16L << 20, 32L << 20, 8L << 20); }
    }
    interface Renderer {
        Prepared prepare(Request request, BooleanSupplier cancelled) throws Exception;
        BufferedImage render(Prepared prepared, BooleanSupplier cancelled) throws Exception;
        default void validate(Prepared prepared, BooleanSupplier cancelled) throws Exception { }
    }
    record Prepared(String key, Request request, byte[] imageBytes) { }
    record AssetInfo(Kind kind, String identity, Options options, int width, int height, long bytes, long pixels) { }
    static final class Rejected extends Exception {
        final State state;
        Rejected(State state, String detail) { super(detail); this.state = state; }
    }
    record Metrics(int queued, int running, int consumers, int sessions, int cached, int resident,
                   long residentBytes, long residentPixels, long cacheBytes, int peakQueued,
                   long rendered, long hits, long coalesced, long staleDiscarded, long failures, long rejected) { }
    record Result(State state, Optional<Lease> lease, String detail, long queueNanos, long workerNanos) implements AutoCloseable {
        @Override public void close() { lease.ifPresent(Lease::close); }
    }
    private static final class Entry {
        final BufferedImage image;
        final AssetInfo info;
        final long bytes, pixels;
        int pins;
        boolean cached;
        Entry(BufferedImage image, Prepared prepared) {
            this.image = image; pixels = (long) image.getWidth() * image.getHeight(); bytes = pixels * 4;
            info = new AssetInfo(prepared.request().kind(), prepared.key(), prepared.request().options(), image.getWidth(), image.getHeight(), bytes, pixels);
        }
    }
    /** Paint-only access avoids exposing the mutable cached raster. Closing is idempotent. */
    final class Lease implements AutoCloseable {
        private final Session session;
        private final Stamp stamp;
        private final List<MarkdownSourceRange> ranges;
        private Entry entry;
        private Lease(Session session, Stamp stamp, List<MarkdownSourceRange> ranges, Entry entry) { this.session = session; this.stamp = stamp; this.ranges = ranges; this.entry = entry; session.leases.add(this); }
        Stamp stamp() { return stamp; }
        List<MarkdownSourceRange> ranges() { return ranges; }
        AssetInfo info() { synchronized (MarkdownMediaService.this) { return require().info; } }
        boolean isCurrent(Source source, long generation) {
            synchronized (MarkdownMediaService.this) { return entry != null && !closed && !session.closed && session.stamp.same(stamp) && stamp.matches(source, generation); }
        }
        int width() { synchronized (MarkdownMediaService.this) { return require().image.getWidth(); } }
        int height() { synchronized (MarkdownMediaService.this) { return require().image.getHeight(); } }
        void paint(Graphics2D graphics, int x, int y) {
            // The pin keeps the raster alive after releasing the service lock; no expensive paint holds it.
            BufferedImage image; synchronized (MarkdownMediaService.this) { image = require().image; }
            graphics.drawImage(image, x, y, null);
        }
        private Entry require() { if (entry == null) throw new IllegalStateException("Media lease closed"); return entry; }
        @Override public void close() {
            synchronized (MarkdownMediaService.this) {
                if (entry == null) return;
                Entry old = entry; entry = null; session.leases.remove(this); consumers--; unpin(old);
            }
        }
    }
    final class Handle implements AutoCloseable {
        private final Session session;
        private final Stamp stamp;
        private final List<MarkdownSourceRange> ranges;
        private State state = State.PENDING;
        private String detail = "Media pending";
        private Job job;
        private Entry entry;
        private long queueNanos, workerNanos;
        private boolean registered;
        private Handle(Session session, Stamp stamp, List<MarkdownSourceRange> ranges) { this.session = session; this.stamp = stamp; this.ranges = ranges; }
        State state() { synchronized (MarkdownMediaService.this) { return state; } }
        Result take(Source current, long generation) {
            synchronized (MarkdownMediaService.this) {
                if ((state == State.PENDING || state == State.READY) && (!stamp.matches(current, generation) || !session.stamp.same(stamp))) {
                    finish(this, State.SUPERSEDED, "Media revision or consumer changed"); staleDiscarded++;
                }
                if (state == State.PENDING) return new Result(state, Optional.empty(), detail, queueNanos, workerNanos);
                if (state != State.READY) return new Result(state, Optional.empty(), detail, queueNanos, workerNanos);
                if (!session.stamp.same(stamp) || !stamp.matches(current, generation) || session.closed || closed) {
                    finish(this, State.SUPERSEDED, "Media revision or consumer changed"); staleDiscarded++;
                    return new Result(state, Optional.empty(), detail, queueNanos, workerNanos);
                }
                Lease lease = new Lease(session, stamp, ranges, entry); entry = null;
                detach(this); consumers++; // transfer the existing pin and admission slot to the lease
                state = State.CLOSED; detail = "Media asset transferred";
                return new Result(State.READY, Optional.of(lease), "Rendered media", queueNanos, workerNanos);
            }
        }
        @Override public void close() { synchronized (MarkdownMediaService.this) { finish(this, State.CANCELLED, "Media request cancelled"); } }
    }
    final class Session implements AutoCloseable {
        private Stamp stamp;
        private boolean closed;
        private final Set<Handle> handles = Collections.newSetFromMap(new IdentityHashMap<>());
        private final Set<Lease> leases = Collections.newSetFromMap(new IdentityHashMap<>());
        private Session(Stamp stamp) { this.stamp = stamp; }
        void update(Source source, long generation) {
            synchronized (MarkdownMediaService.this) {
                if (closed) return;
                Stamp next = Stamp.of(source, generation);
                if (stamp.same(next)) return;
                for (Handle handle : List.copyOf(handles)) { finish(handle, State.SUPERSEDED, "Media consumer revision changed"); staleDiscarded++; }
                for (Lease lease : List.copyOf(leases)) lease.close();
                stamp = next;
            }
        }
        Handle request(Plan plan) {
            synchronized (MarkdownMediaService.this) {
                Handle handle = new Handle(this, plan.request().map(Request::stamp).orElse(stamp), plan.request().map(Request::ranges).orElse(List.of()));
                if (closed || MarkdownMediaService.this.closed) { handle.state = State.CLOSED; handle.detail = "Media service or session closed"; return handle; }
                if (plan.request().isEmpty()) { handle.state = plan.state(); handle.detail = plan.detail(); return handle; }
                Request request = plan.request().orElseThrow();
                if (!stamp.same(request.stamp())) { handle.state = State.SUPERSEDED; handle.detail = "Media request is stale"; return handle; }
                if (consumers >= budget.consumers()) { handle.state = State.RESOURCE_LIMIT; handle.detail = "Media consumer limit"; rejected++; return handle; }
                String key = request.key(); Job job = jobs.get(key);
                if (job != null && job.abandoned) job = null;
                if (job == null && queuedCount() >= budget.queued() && running != 0) {
                    handle.state = State.RESOURCE_LIMIT; handle.detail = "Media queue limit"; rejected++; return handle;
                }
                handle.registered = true; consumers++; handles.add(handle);
                if (job != null) { job.handles.add(handle); handle.job = job; coalesced++; return handle; }
                job = new Job(key, request); job.handles.add(handle); handle.job = job; jobs.put(key, job);
                try { executor().execute(job); }
                catch (RejectedExecutionException full) { jobs.remove(key, job); finish(handle, State.RESOURCE_LIMIT, "Media queue limit"); rejected++; }
                peakQueued = Math.max(peakQueued, queuedCount());
                return handle;
            }
        }
        @Override public void close() {
            synchronized (MarkdownMediaService.this) {
                if (closed) return; closed = true;
                for (Handle handle : List.copyOf(handles)) finish(handle, State.CLOSED, "Media consumer closed");
                for (Lease lease : List.copyOf(leases)) lease.close();
                sessions.remove(this);
            }
        }
    }
    private final class Job implements Runnable {
        final String key;
        final Request request;
        final long submitted = System.nanoTime();
        final Set<Handle> handles = Collections.newSetFromMap(new IdentityHashMap<>());
        volatile boolean abandoned;
        boolean started;
        Job(String key, Request request) { this.key = key; this.request = request; }
        boolean cancelled() { return abandoned || Thread.currentThread().isInterrupted(); }
        @Override public void run() {
            long began = System.nanoTime();
            synchronized (MarkdownMediaService.this) {
                if (cancelled() || closed) { jobs.remove(key, this); MarkdownMediaService.this.notifyAll(); return; }
                started = true; running++;
            }
            BufferedImage output = null; Entry ready = null; State state = State.READY; String detail = "Rendered media";
            try {
                if (javax.swing.SwingUtilities.isEventDispatchThread()) throw new IllegalStateException("Media renderer on EDT");
                Prepared prepared = renderer.prepare(request, this::cancelled);
                synchronized (MarkdownMediaService.this) {
                    if (cancelled() || closed) throw new CancellationException();
                    ready = cache.get(prepared.key());
                    if (ready != null) { ready.pins++; hits++; }
                }
                if (ready == null) {
                    output = renderer.render(prepared, this::cancelled);
                    renderer.validate(prepared, this::cancelled);
                    if (output == null) throw new IllegalStateException("Renderer produced no image");
                    // Own a compact ARGB raster, including when a library returns a view into a larger image.
                    long pixels = (long) output.getWidth() * output.getHeight();
                    if (pixels <= 0 || pixels > 4L * 1024 * 1024 || output.getWidth() > request.options().maxWidth() || output.getHeight() > request.options().maxHeight())
                        throw new Rejected(State.RESOURCE_LIMIT, "Rendered image exceeds sizing budget");
                    BufferedImage owned = new BufferedImage(output.getWidth(), output.getHeight(), BufferedImage.TYPE_INT_ARGB);
                    var graphics = owned.createGraphics(); try { graphics.drawImage(output, 0, 0, null); } finally { graphics.dispose(); }
                    output.flush(); output = null;
                    synchronized (MarkdownMediaService.this) {
                        if (cancelled() || closed) { owned.flush(); throw new CancellationException(); }
                        Entry entry = new Entry(owned, prepared);
                        evictFor(entry.bytes, entry.pixels);
                        if (residentBytes + entry.bytes > budget.residentBytes() || residentPixels + entry.pixels > budget.residentPixels()) {
                            owned.flush(); throw new Rejected(State.RESOURCE_LIMIT, "Active media memory budget exhausted");
                        }
                        ready = entry; ready.pins = 1; residents.add(ready); residentBytes += ready.bytes; residentPixels += ready.pixels;
                        if (entry.bytes <= budget.cacheBytes()) { entry.cached = true; cache.put(prepared.key(), entry); cacheBytes += entry.bytes; trimCache(); }
                        rendered++;
                    }
                }
            } catch (CancellationException error) { state = State.CANCELLED; detail = "Media rendering cancelled"; }
            catch (Rejected error) { state = error.state; detail = error.getMessage(); }
            catch (Exception | LinkageError error) { state = State.FAILED; detail = error.getClass().getSimpleName(); }
            finally {
                if (output != null) output.flush();
                synchronized (MarkdownMediaService.this) {
                    long duration = System.nanoTime() - began;
                    if (state == State.FAILED) failures++; if (state == State.RESOURCE_LIMIT) rejected++;
                    for (Handle handle : List.copyOf(handles)) {
                        handle.job = null; handle.queueNanos = began - submitted; handle.workerNanos = duration;
                        if (closed || handle.session.closed || !handle.session.stamp.same(handle.stamp)) {
                            finish(handle, State.SUPERSEDED, "Obsolete media result discarded"); staleDiscarded++;
                        } else if (state == State.READY && ready != null) {
                            ready.pins++; handle.entry = ready; handle.state = State.READY; handle.detail = detail;
                        } else finish(handle, state, detail);
                    }
                    handles.clear(); jobs.remove(key, this); running--;
                    if (ready != null) unpin(ready);
                    MarkdownMediaService.this.notifyAll();
                }
            }
        }
    }
    private final Budget budget;
    private final Renderer renderer;
    private final Set<Session> sessions = Collections.newSetFromMap(new IdentityHashMap<>());
    private final Map<String, Job> jobs = new HashMap<>();
    private final LinkedHashMap<String, Entry> cache = new LinkedHashMap<>(16, .75f, true);
    private final Set<Entry> residents = Collections.newSetFromMap(new IdentityHashMap<>());
    private ThreadPoolExecutor executor;
    private boolean closed;
    private int consumers, running, peakQueued;
    private long residentBytes, residentPixels, cacheBytes, rendered, hits, coalesced, staleDiscarded, failures, rejected;
    MarkdownMediaService() { this(Budget.desktop(), new MarkdownMediaRenderer()); }
    MarkdownMediaService(Budget budget, Renderer renderer) { this.budget = Objects.requireNonNull(budget); this.renderer = Objects.requireNonNull(renderer); }
    synchronized Session open(Source source, long generation) {
        if (closed) throw new IllegalStateException("Media service closed");
        if (!source.completeSource() || sessions.size() >= budget.sessions()) throw new IllegalArgumentException("Media session admission limit");
        Session session = new Session(Stamp.of(source, generation)); sessions.add(session); return session;
    }
    synchronized Metrics metrics() { return new Metrics(queuedCount(), running, consumers, sessions.size(), cache.size(), residents.size(),
        residentBytes, residentPixels, cacheBytes, peakQueued, rendered, hits, coalesced, staleDiscarded, failures, rejected); }
    private int queuedCount() { return executor == null ? 0 : executor.getQueue().size(); }
    private ThreadPoolExecutor executor() {
        if (executor == null) {
            executor = new ThreadPoolExecutor(1, 1, 1, TimeUnit.SECONDS, new ArrayBlockingQueue<>(budget.queued()), task -> {
                Thread thread = new Thread(task, "shed-markdown-media"); thread.setDaemon(true); return thread;
            });
            executor.allowCoreThreadTimeOut(true);
        }
        return executor;
    }
    private void detach(Handle handle) {
        handle.session.handles.remove(handle);
        if (handle.registered) { handle.registered = false; consumers--; }
        Job job = handle.job; handle.job = null;
        if (job != null) {
            job.handles.remove(handle);
            if (job.handles.isEmpty()) {
                job.abandoned = true;
                if (!job.started && executor != null && executor.remove(job)) jobs.remove(job.key, job);
            }
        }
        notifyAll();
    }
    private void finish(Handle handle, State state, String detail) {
        if (!handle.registered) return;
        Entry entry = handle.entry; handle.entry = null; handle.state = state; handle.detail = detail;
        detach(handle); if (entry != null) unpin(entry);
    }
    private void unpin(Entry entry) { entry.pins--; if (!entry.cached && entry.pins == 0) release(entry); }
    private void release(Entry entry) { if (residents.remove(entry)) { residentBytes -= entry.bytes; residentPixels -= entry.pixels; entry.image.flush(); } }
    private void evictFor(long bytes, long pixels) {
        var iterator = cache.entrySet().iterator();
        while ((residentBytes + bytes > budget.residentBytes() || residentPixels + pixels > budget.residentPixels()) && iterator.hasNext()) {
            Entry entry = iterator.next().getValue(); if (entry.pins != 0) continue;
            iterator.remove(); entry.cached = false; cacheBytes -= entry.bytes; release(entry);
        }
    }
    private void trimCache() {
        var iterator = cache.entrySet().iterator();
        while ((cacheBytes > budget.cacheBytes() || cache.size() > budget.entries()) && iterator.hasNext()) {
            Entry entry = iterator.next().getValue(); iterator.remove(); entry.cached = false; cacheBytes -= entry.bytes;
            if (entry.pins == 0) release(entry);
        }
    }
    /** Nonblocking disposal. A noninterruptible renderer owns its worker until it actually returns. */
    @Override public synchronized void close() {
        if (closed) return; closed = true;
        for (Session session : List.copyOf(sessions)) session.close();
        for (Entry entry : cache.values()) { entry.cached = false; if (entry.pins == 0) release(entry); }
        cache.clear(); cacheBytes = 0;
        if (executor != null) executor.shutdown();
    }
    boolean awaitIdle(long timeout, TimeUnit unit) throws InterruptedException {
        if (javax.swing.SwingUtilities.isEventDispatchThread()) throw new IllegalStateException("Do not wait for media on EDT");
        long until = System.nanoTime() + unit.toNanos(timeout);
        synchronized (this) {
            while (!jobs.isEmpty() || running != 0) { long left = until - System.nanoTime(); if (left <= 0) return false; TimeUnit.NANOSECONDS.timedWait(this, left); }
            return true;
        }
    }
}
