package shed;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

final class ExternalFormatter {
    record Result(String text, String error) { boolean succeeded() { return error == null || error.isBlank(); } }

    private ExternalFormatter() { }

    static Result format(FormatterPolicy policy, Path workspace, Path file, String input, int timeoutMs, int maxBytes,
        AsyncJobService.JobToken token) throws IOException, InterruptedException {
        if (policy == null || policy.mode() != FormatterPolicy.Mode.EXTERNAL) return new Result(null, "external formatter is not selected");
        if (policy.command().isBlank()) return new Result(null, "external formatter command is required");
        if (workspace == null || file == null) return new Result(null, "external formatter requires workspace and file paths");
        List<String> argv = new ArrayList<>();
        argv.add(policy.command());
        for (String raw : policy.args()) {
            String argument = raw == null ? "" : raw;
            if (argument.contains("${") && !"${file}".equals(argument)) return new Result(null, "external formatter uses an unsupported placeholder");
            argv.add("${file}".equals(argument) ? file.toString() : argument);
        }
        Process process = new ProcessBuilder(argv).directory(workspace.toFile()).start();
        if (token != null) token.onCancel(process::destroyForcibly);
        int limit = Math.max(1024, maxBytes);
        Capture stdout = new Capture();
        Capture stderr = new Capture();
        Thread out = Thread.ofVirtual().start(() -> drain(process.getInputStream(), stdout, limit));
        Thread err = Thread.ofVirtual().start(() -> drain(process.getErrorStream(), stderr, limit));
        Thread in = Thread.ofVirtual().start(() -> write(process.getOutputStream(), input));
        boolean complete = process.waitFor(Math.max(1, timeoutMs), TimeUnit.MILLISECONDS);
        if (!complete) {
            process.destroyForcibly();
            process.waitFor(1, TimeUnit.SECONDS);
            join(out); join(err); join(in);
            return new Result(null, "external formatter timed out");
        }
        join(out); join(err); join(in);
        int exit = process.exitValue();
        if (stdout.exceeded) return new Result(null, "external formatter output exceeds configured process.output.max.bytes");
        String error = stderr.bytes.toString(StandardCharsets.UTF_8).strip();
        if (stderr.exceeded) error = error + " (stderr truncated)";
        if (exit != 0) return new Result(null, "external formatter exited " + exit + (error.isBlank() ? "" : ": " + error));
        return new Result(stdout.bytes.toString(StandardCharsets.UTF_8), "");
    }

    private static final class Capture {
        final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        volatile boolean exceeded;
    }

    private static void drain(InputStream input, Capture target, int limit) {
        try (input) {
            byte[] chunk = new byte[8192];
            for (int count; (count = input.read(chunk)) >= 0;) {
                if (target.bytes.size() + count > limit) target.exceeded = true;
                if (target.bytes.size() < limit) target.bytes.write(chunk, 0, Math.min(count, limit - target.bytes.size()));
            }
        } catch (IOException ignored) { }
    }

    private static void write(OutputStream output, String input) {
        try (output) { output.write((input == null ? "" : input).getBytes(StandardCharsets.UTF_8)); }
        catch (IOException ignored) { }
    }

    private static void join(Thread thread) throws InterruptedException { thread.join(); }
}
