package shed;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Explicit PostgreSQL CLI bridge; connection details remain entirely with libpq and the user's environment. */
final class DatabaseController {
    private static final String TABLES_QUERY = "select table_schema, table_name from information_schema.tables "
        + "where table_type = 'BASE TABLE' and table_schema not in ('pg_catalog', 'information_schema') "
        + "order by table_schema, table_name";
    private final Texteditor editor;

    DatabaseController(Texteditor editor) {
        this.editor = editor;
    }

    String handle(String argument) {
        String value = argument == null ? "" : argument.trim();
        if (value.isEmpty() || "status".equalsIgnoreCase(value)) return showStatus();
        List<String> tokens;
        try {
            tokens = ShellCommand.directCommand(value);
        } catch (IllegalArgumentException error) {
            return "Database command invalid: " + error.getMessage();
        }
        String operation = tokens.getFirst().toLowerCase(Locale.ROOT);
        return switch (operation) {
            case "query", "sql" -> query(tokens.subList(1, tokens.size()));
            case "tables" -> submit("tables", queryCommand(TABLES_QUERY));
            case "file" -> executeFile(tokens.subList(1, tokens.size()));
            case "terminal", "console" -> editor.terminalController.openDirect("PostgreSQL", workspace().toFile(), baseCommand());
            default -> "Usage: :database [status|query <quoted-sql>|tables|file <workspace-relative.sql>|terminal]";
        };
    }

    static List<String> queryCommand(String sql) {
        if (sql == null || sql.isBlank() || sql.indexOf('\u0000') >= 0 || sql.indexOf('\n') >= 0 || sql.indexOf('\r') >= 0) {
            throw new IllegalArgumentException("SQL must be a non-empty single line");
        }
        List<String> command = new ArrayList<>(baseCommand());
        command.add("--set");
        command.add("ON_ERROR_STOP=on");
        command.add("--command");
        command.add(sql);
        return List.copyOf(command);
    }

    private static List<String> baseCommand() {
        return List.of("psql", "--no-psqlrc");
    }

    private String query(List<String> arguments) {
        if (arguments.isEmpty()) return "Usage: :database query <quoted-sql>";
        try {
            return submit("query", queryCommand(String.join(" ", arguments)));
        } catch (IllegalArgumentException error) {
            return "Database query invalid: " + error.getMessage();
        }
    }

    private String executeFile(List<String> arguments) {
        if (arguments.size() != 1) return "Usage: :database file <workspace-relative.sql>";
        Path root = workspace();
        Path file;
        try {
            Path requested = Path.of(arguments.getFirst());
            if (requested.isAbsolute()) return "Database file must be relative to the workspace";
            file = root.resolve(requested).normalize();
            if (!file.startsWith(root) || !Files.isRegularFile(file) || !file.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".sql")) {
                return "Database file must be an existing .sql file inside the workspace";
            }
            file = file.toRealPath();
            if (!file.startsWith(root.toRealPath())) return "Database file must remain inside the workspace";
        } catch (IOException | RuntimeException error) {
            return "Database file unavailable";
        }
        List<String> command = new ArrayList<>(baseCommand());
        command.add("--set");
        command.add("ON_ERROR_STOP=on");
        command.add("--file");
        command.add(file.toString());
        return submit("file " + file.getFileName(), command);
    }

    private String submit(String operation, List<String> command) {
        Path root = workspace();
        int job = editor.asyncJobService.submit("database " + operation, token -> editor.runExternalCommand(command, root.toFile(), null, token,
            editor.configManager.getProcessTimeoutMs(), editor.configManager.getProcessOutputMaxBytes(), true),
            (snapshot, result, error) -> complete(operation, snapshot, result, error));
        return "PostgreSQL " + operation + " requested (job " + job + ").";
    }

    private String showStatus() {
        StringBuilder output = new StringBuilder("PostgreSQL CLI\n\n");
        output.append("Workspace: ").append(workspace()).append("\n");
        output.append("Connection: user-managed libpq/psql configuration; no connection is opened for this view.\n");
        output.append("Detected connection selectors: ");
        List<String> selectors = new ArrayList<>();
        for (String name : List.of("PGHOST", "PGDATABASE", "PGUSER", "PGSERVICE", "PGPASSFILE")) {
            if (System.getenv(name) != null && !System.getenv(name).isBlank()) selectors.add(name);
        }
        output.append(selectors.isEmpty() ? "(none)" : String.join(", ", selectors)).append("\n\n");
        output.append(":database query \"select 1\"\n:database tables\n:database file migrations/check.sql\n:database terminal\n\n");
        output.append("Shed does not store credentials or connection strings. Prefer a libpq password/service file over PGPASSWORD.\n");
        editor.showScratchBuffer("[database]", output.toString());
        return "Showing PostgreSQL CLI status";
    }

    private void complete(String operation, AsyncJobService.JobSnapshot snapshot, CommandResult result, Exception error) {
        if (editor.closingDown) return;
        if (snapshot != null && snapshot.getStatus() == AsyncJobService.Status.CANCELLED) {
            editor.showMessage("PostgreSQL " + operation + " cancelled");
            return;
        }
        String output = output(result);
        if (!output.isBlank()) editor.showScratchBuffer("[database " + operation + "]", output + "\n");
        if (error != null || result == null || result.exitCode != 0) {
            String detail = error != null ? concise(error) : result == null ? "unknown error" : output.isBlank() ? "exit " + result.exitCode : output.strip();
            editor.showMessage("PostgreSQL " + operation + " failed: " + detail);
        } else {
            editor.showMessage("PostgreSQL " + operation + " completed");
        }
    }

    private Path workspace() {
        FileBuffer buffer = editor.getCurrentBuffer();
        if (editor.workspaceController != null && buffer != null && buffer.getFile() != null) {
            Path root = editor.workspaceController.rootFor(buffer.getFile().toPath());
            if (root != null) return root;
        }
        Path active = editor.workspaceController == null ? null : editor.workspaceController.activeRoot();
        if (active != null) return active;
        if (buffer != null && buffer.getFile() != null && buffer.getFile().getParentFile() != null) {
            return buffer.getFile().getParentFile().toPath().toAbsolutePath().normalize();
        }
        return Path.of(".").toAbsolutePath().normalize();
    }

    private static String output(CommandResult result) {
        if (result == null) return "";
        if (result.stderr.isBlank() || result.stderr.equals(result.stdout)) return result.stdout;
        return result.stdout.isBlank() ? result.stderr : result.stdout + "\n" + result.stderr;
    }

    private static String concise(Exception error) {
        String message = error == null ? null : error.getMessage();
        return message == null || message.isBlank() ? error == null ? "unknown error" : error.getClass().getSimpleName()
            : message.replace('\n', ' ').replace('\r', ' ');
    }
}
