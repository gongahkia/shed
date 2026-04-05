package shed;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

public class AsyncJobService {
    public enum Status {
        RUNNING,
        SUCCEEDED,
        FAILED,
        CANCELLED
    }

    public interface JobTask<T> {
        T run(JobToken token) throws Exception;
    }

    public interface JobCompletion<T> {
        void onComplete(JobSnapshot snapshot, T result, Exception error);
    }

    public static final class JobToken {
        private volatile boolean cancelled;
        private final List<Runnable> cancelHooks;

        private JobToken() {
            this.cancelled = false;
            this.cancelHooks = Collections.synchronizedList(new ArrayList<>());
        }

        public boolean isCancelled() {
            return cancelled;
        }

        public void onCancel(Runnable hook) {
            if (hook == null) {
                return;
            }
            if (cancelled) {
                hook.run();
                return;
            }
            cancelHooks.add(hook);
            if (cancelled) {
                runCancelHooks();
            }
        }

        private void cancel() {
            cancelled = true;
            runCancelHooks();
        }

        private void runCancelHooks() {
            synchronized (cancelHooks) {
                for (Runnable hook : cancelHooks) {
                    try {
                        hook.run();
                    } catch (Exception ignored) {
                    }
                }
                cancelHooks.clear();
            }
        }
    }

    public static final class JobSnapshot {
        private final int id;
        private final String description;
        private final Status status;
        private final long startedAtMillis;
        private final Long finishedAtMillis;
        private final String errorMessage;

        private JobSnapshot(int id, String description, Status status, long startedAtMillis, Long finishedAtMillis, String errorMessage) {
            this.id = id;
            this.description = description;
            this.status = status;
            this.startedAtMillis = startedAtMillis;
            this.finishedAtMillis = finishedAtMillis;
            this.errorMessage = errorMessage;
        }

        public int getId() {
            return id;
        }

        public String getDescription() {
            return description;
        }

        public Status getStatus() {
            return status;
        }

        public long getStartedAtMillis() {
            return startedAtMillis;
        }

        public Long getFinishedAtMillis() {
            return finishedAtMillis;
        }

        public String getErrorMessage() {
            return errorMessage;
        }
    }

    private static final class JobRecord {
        private final int id;
        private final String description;
        private final long startedAtMillis;
        private volatile Long finishedAtMillis;
        private volatile Status status;
        private volatile String errorMessage;
        private volatile Future<?> future;
        private final JobToken token;

        private JobRecord(int id, String description) {
            this.id = id;
            this.description = description;
            this.startedAtMillis = System.currentTimeMillis();
            this.finishedAtMillis = null;
            this.status = Status.RUNNING;
            this.errorMessage = "";
            this.token = new JobToken();
        }

        private JobSnapshot snapshot() {
            return new JobSnapshot(id, description, status, startedAtMillis, finishedAtMillis, errorMessage);
        }
    }

    private final ExecutorService executor;
    private final AtomicInteger nextId;
    private final Map<Integer, JobRecord> jobs;
    private final int maxHistoryEntries;

    public AsyncJobService() {
        this(200);
    }

    public AsyncJobService(int maxHistoryEntries) {
        this.executor = Executors.newCachedThreadPool();
        this.nextId = new AtomicInteger(1);
        this.jobs = new ConcurrentHashMap<>();
        this.maxHistoryEntries = Math.max(10, maxHistoryEntries);
    }

    public <T> int submit(String description, JobTask<T> task, JobCompletion<T> completion) {
        if (task == null) {
            throw new IllegalArgumentException("task must not be null");
        }
        int id = nextId.getAndIncrement();
        JobRecord record = new JobRecord(id, description == null ? "job-" + id : description);
        jobs.put(id, record);

        Future<?> future = executor.submit(() -> {
            T result = null;
            Exception error = null;
            try {
                result = task.run(record.token);
                if (record.token.isCancelled() || Thread.currentThread().isInterrupted()) {
                    record.status = Status.CANCELLED;
                } else {
                    record.status = Status.SUCCEEDED;
                }
            } catch (InterruptedException | CancellationException e) {
                record.status = Status.CANCELLED;
                record.errorMessage = e.getMessage() == null ? "" : e.getMessage();
                Thread.currentThread().interrupt();
                error = e instanceof Exception ? (Exception) e : new Exception(e);
            } catch (Exception e) {
                record.status = record.token.isCancelled() ? Status.CANCELLED : Status.FAILED;
                record.errorMessage = e.getMessage() == null ? "" : e.getMessage();
                error = e;
            } finally {
                record.finishedAtMillis = System.currentTimeMillis();
                trimHistoryIfNeeded();
                if (completion != null) {
                    completion.onComplete(record.snapshot(), result, error);
                }
            }
        });

        record.future = future;
        return id;
    }

    public boolean cancel(int id) {
        JobRecord record = jobs.get(id);
        if (record == null) {
            return false;
        }
        if (record.status != Status.RUNNING) {
            return false;
        }
        record.token.cancel();
        Future<?> future = record.future;
        if (future != null) {
            future.cancel(true);
        }
        return true;
    }

    public JobSnapshot get(int id) {
        JobRecord record = jobs.get(id);
        return record == null ? null : record.snapshot();
    }

    public List<JobSnapshot> list() {
        List<JobSnapshot> snapshots = new ArrayList<>();
        for (JobRecord record : jobs.values()) {
            snapshots.add(record.snapshot());
        }
        snapshots.sort((left, right) -> Integer.compare(right.getId(), left.getId()));
        return snapshots;
    }

    public void shutdownNow() {
        for (JobRecord record : jobs.values()) {
            if (record.status == Status.RUNNING) {
                record.token.cancel();
            }
        }
        executor.shutdownNow();
    }

    private void trimHistoryIfNeeded() {
        if (jobs.size() <= maxHistoryEntries) {
            return;
        }
        List<JobRecord> finished = new ArrayList<>();
        for (JobRecord record : jobs.values()) {
            if (record.status != Status.RUNNING) {
                finished.add(record);
            }
        }
        finished.sort((left, right) -> Long.compare(left.startedAtMillis, right.startedAtMillis));
        int trimCount = jobs.size() - maxHistoryEntries;
        for (int i = 0; i < finished.size() && trimCount > 0; i++) {
            jobs.remove(finished.get(i).id);
            trimCount--;
        }
    }
}
