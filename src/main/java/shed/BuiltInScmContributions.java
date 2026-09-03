package shed;

import shed.api.ScmContribution;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/** Native SCM providers beyond Git; every operation remains an explicit direct-argv action. */
final class BuiltInScmContributions {
    private BuiltInScmContributions() {
    }

    static List<ExtensionRegistry.Owned<ScmContribution>> all() {
        return List.of(new ExtensionRegistry.Owned<>("builtin", new CommandScm("mercurial", "Mercurial", "hg", ".hg",
                List.of("status", "diff", "log", "add", "commit", "pull", "push", "update"))),
            new ExtensionRegistry.Owned<>("builtin", new CommandScm("subversion", "Subversion", "svn", ".svn",
                List.of("status", "diff", "log", "add", "commit", "update"))));
    }

    private static final class CommandScm implements ScmContribution {
        private final String id;
        private final String display;
        private final String executable;
        private final String marker;
        private final List<String> actions;

        private CommandScm(String id, String display, String executable, String marker, List<String> actions) {
            this.id = id;
            this.display = display;
            this.executable = executable;
            this.marker = marker;
            this.actions = List.copyOf(actions);
        }

        @Override public String id() { return id; }
        @Override public String displayName() { return display; }
        @Override public boolean supports(Path root) { return root != null && Files.exists(root.resolve(marker)); }
        @Override public String status(Path root) throws Exception { return run(root, "status", ""); }
        @Override public List<String> actions() { return actions; }
        @Override public String execute(Path root, String action, String arguments) throws Exception { return run(root, action, arguments); }

        private String run(Path root, String action, String arguments) throws Exception {
            if (!actions.contains(action)) throw new IOException("unsupported " + id + " action: " + action);
            List<String> command = new java.util.ArrayList<>();
            command.add(executable);
            command.add(action);
            if (arguments != null && !arguments.isBlank()) command.addAll(ShellCommand.directCommand(arguments));
            Path output = Files.createTempFile("shed-scm-", ".log");
            try {
                Process process = new ProcessBuilder(command).directory(root.toFile()).redirectErrorStream(true).redirectOutput(output.toFile()).start();
                if (!process.waitFor(60, java.util.concurrent.TimeUnit.SECONDS)) {
                    process.destroyForcibly();
                    throw new IOException(id + " command timed out");
                }
                String text = readCapped(Files.newInputStream(output));
                if (process.exitValue() != 0) throw new IOException(text.isBlank() ? id + " exited " + process.exitValue() : text.strip());
                return text.isBlank() ? "(" + action + " completed)" : text;
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
                throw new IOException(id + " command interrupted", error);
            } finally {
                Files.deleteIfExists(output);
            }
        }
    }

    private static String readCapped(InputStream input) throws IOException {
        try (InputStream value = input; ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            for (int total = 0, read; (read = value.read(buffer)) >= 0;) {
                int accepted = Math.min(read, 128 * 1024 - total);
                if (accepted > 0) output.write(buffer, 0, accepted);
                total += read;
                if (total > 128 * 1024) break;
            }
            return output.toString(StandardCharsets.UTF_8);
        }
    }
}
