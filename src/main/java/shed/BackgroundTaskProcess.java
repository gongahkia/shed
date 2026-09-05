package shed;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** Owns one local watch process, its bounded output, and an optional literal readiness marker. */
final class BackgroundTaskProcess {
    private BackgroundTaskProcess() {
    }

    static Running start(List<String> command, File workingDirectory, Map<String, String> environment, int outputLimit,
                         String readinessMarker) throws IOException {
        if (command == null || command.isEmpty() || command.getFirst() == null || command.getFirst().isBlank()) {
            throw new IOException("background task command is required");
        }
        ProcessBuilder builder = new ProcessBuilder(command);
        builder.directory(workingDirectory == null ? new File(".") : workingDirectory);
        builder.redirectErrorStream(true);
        if (environment != null && !environment.isEmpty()) builder.environment().putAll(environment);
        Process process = builder.start();
        process.getOutputStream().close();
        Running running = new Running(process, outputLimit, readinessMarker);
        running.start();
        return running;
    }

    static final class Running {
        private final Process process;
        private final int outputLimit;
        private final String readinessMarker;
        private final ByteArrayOutputStream output = new ByteArrayOutputStream();
        private final Object outputLock = new Object();
        private final CompletableFuture<CommandResult> completion = new CompletableFuture<>();
        private final CompletableFuture<Void> ready = new CompletableFuture<>();
        private boolean truncated;
        private String readinessTail = "";

        private Running(Process process, int outputLimit, String readinessMarker) {
            this.process = process;
            this.outputLimit = Math.max(1024, outputLimit);
            this.readinessMarker = readinessMarker == null ? "" : readinessMarker;
        }

        private void start() {
            Thread reader = Thread.ofVirtual().start(this::readOutput);
            Thread.ofVirtual().start(() -> completeWhenExited(reader));
        }

        String awaitReadiness(int timeoutMs, AsyncJobService.JobToken token) {
            if (readinessMarker.isBlank()) return "background task requires ready_when for debug pre-launch";
            if (token != null) token.onCancel(this::stop);
            try {
                ready.get(Math.max(500, timeoutMs), TimeUnit.MILLISECONDS);
                if (!process.isAlive()) return "background task exited after reporting ready_when marker";
                return "";
            } catch (TimeoutException error) {
                stop();
                return "background task did not report ready_when marker within " + timeoutMs + "ms: " + readinessMarker;
            } catch (InterruptedException error) {
                stop();
                Thread.currentThread().interrupt();
                return "background task readiness wait interrupted";
            } catch (ExecutionException error) {
                Throwable cause = error.getCause();
                String detail = cause == null ? "unknown readiness failure" : cause.getMessage();
                return detail == null || detail.isBlank() ? "background task exited before reporting ready_when marker" : detail;
            }
        }

        CommandResult awaitCompletion(AsyncJobService.JobToken token) {
            if (token != null) token.onCancel(this::stop);
            try {
                CommandResult result = completion.get();
                return token != null && token.isCancelled()
                    ? new CommandResult(-1, result.stdout, "Process cancelled")
                    : result;
            } catch (InterruptedException error) {
                stop();
                Thread.currentThread().interrupt();
                return new CommandResult(-1, output(), "Process interrupted");
            } catch (ExecutionException error) {
                Throwable cause = error.getCause();
                String detail = cause == null ? "background task failed" : cause.getMessage();
                return new CommandResult(-1, output(), detail == null ? "background task failed" : detail);
            }
        }

        void stop() {
            if (process.isAlive()) process.destroyForcibly();
        }

        private void readOutput() {
            try (InputStreamReader input = new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8)) {
                char[] chunk = new char[4096];
                for (int count; (count = input.read(chunk)) >= 0;) append(new String(chunk, 0, count));
            } catch (IOException ignored) {
                // The process completion state remains authoritative when its pipe closes unexpectedly.
            }
        }

        private void completeWhenExited(Thread reader) {
            try {
                int exitCode = process.waitFor();
                reader.join();
                if (!readinessMarker.isBlank() && !ready.isDone()) {
                    ready.completeExceptionally(new IOException("background task exited before reporting ready_when marker: " + readinessMarker));
                }
                completion.complete(new CommandResult(exitCode, output(), ""));
            } catch (InterruptedException error) {
                stop();
                Thread.currentThread().interrupt();
                completion.complete(new CommandResult(-1, output(), "Process interrupted"));
            }
        }

        private void append(String text) {
            if (text == null || text.isEmpty()) return;
            synchronized (outputLock) {
                byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
                int remaining = outputLimit - output.size();
                if (remaining <= 0) truncated = true;
                else {
                    int count = Math.min(remaining, bytes.length);
                    output.write(bytes, 0, count);
                    if (count < bytes.length) truncated = true;
                }
                if (!readinessMarker.isBlank() && !ready.isDone()) {
                    String candidate = readinessTail + text;
                    if (candidate.contains(readinessMarker)) ready.complete(null);
                    int keep = Math.max(0, readinessMarker.length() - 1);
                    readinessTail = keep == 0 ? "" : candidate.substring(Math.max(0, candidate.length() - keep));
                }
            }
        }

        private String output() {
            synchronized (outputLock) {
                String text = output.toString(StandardCharsets.UTF_8);
                return truncated ? text + "\n[shed: output truncated]" : text;
            }
        }
    }
}
