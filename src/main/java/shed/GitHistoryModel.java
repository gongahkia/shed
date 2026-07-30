package shed;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

final class GitHistoryModel {
    enum State {
        READY,
        UNAVAILABLE,
        ERROR
    }

    enum RemoteAction {
        FETCH("Fetch", List.of("git", "fetch", "--prune"), false),
        PULL("Pull (fast-forward only)", List.of("git", "pull", "--ff-only"), true),
        PUSH("Push", List.of("git", "push"), true);

        private final String label;
        private final List<String> command;
        private final boolean requiresConfirmation;

        RemoteAction(String label, List<String> command, boolean requiresConfirmation) {
            this.label = label;
            this.command = command;
            this.requiresConfirmation = requiresConfirmation;
        }

        String label() {
            return label;
        }

        List<String> command() {
            return command;
        }

        boolean requiresConfirmation() {
            return requiresConfirmation;
        }
    }

    record Commit(String hash, String decorations, String subject, String author, String timestamp) {
        Commit {
            hash = hash == null ? "" : hash;
            decorations = decorations == null ? "" : decorations;
            subject = subject == null ? "" : subject;
            author = author == null ? "" : author;
            timestamp = timestamp == null ? "" : timestamp;
        }

        String display() {
            String shortHash = hash.length() <= 12 ? hash : hash.substring(0, 12);
            return shortHash + " " + inline(subject);
        }

        @Override
        public String toString() {
            return display();
        }
    }

    record Snapshot(State state, String root, List<Commit> commits, List<String> remotes, String detail) {
        Snapshot {
            root = root == null ? "" : root;
            commits = commits == null ? List.of() : List.copyOf(commits);
            remotes = remotes == null ? List.of() : List.copyOf(remotes);
            detail = detail == null ? "" : detail;
        }

        boolean available() {
            return state == State.READY;
        }
    }

    record RemoteResult(RemoteAction action, boolean succeeded, String detail) {
        RemoteResult {
            detail = detail == null ? "" : detail;
        }
    }

    private GitHistoryModel() { }

    static Snapshot unavailable(String detail) {
        return new Snapshot(State.UNAVAILABLE, "", List.of(), List.of(), detail);
    }

    static Snapshot fromCommands(File root, CommandResult history, CommandResult remotes) {
        if (root == null) return unavailable("Not inside a Git repository.");
        if (history == null) return new Snapshot(State.ERROR, root.getAbsolutePath(), List.of(), List.of(), "Git history did not return a result.");
        if (history.exitCode != 0) {
            return new Snapshot(State.ERROR, root.getAbsolutePath(), List.of(), List.of(), commandError(history, "git history failed"));
        }
        if (outputTruncated(history)) {
            return new Snapshot(State.ERROR, root.getAbsolutePath(), List.of(), List.of(),
                "Git history output was truncated; increase process.output.max.bytes before inspecting history.");
        }
        List<Commit> commits = parseHistory(history.stdout);
        List<String> remoteNames = List.of();
        String remoteDetail = "";
        if (remotes == null) {
            remoteDetail = " Remote list is unavailable.";
        } else if (remotes.exitCode != 0) {
            remoteDetail = " Remote list is unavailable: " + commandError(remotes, "git remote failed");
        } else if (outputTruncated(remotes)) {
            remoteDetail = " Remote list was truncated; increase process.output.max.bytes before remote actions.";
        } else {
            remoteNames = lines(remotes.stdout);
        }
        String detail = commits.isEmpty() ? "No commits." : commits.size() + " recent commit" + (commits.size() == 1 ? "" : "s") + ".";
        if (remoteDetail.isEmpty()) {
            detail += remoteNames.isEmpty() ? " No configured remotes." : " " + remoteNames.size() + " configured remote" + (remoteNames.size() == 1 ? "" : "s") + ".";
        } else {
            detail += remoteDetail;
        }
        return new Snapshot(State.READY, root.getAbsolutePath(), commits, remoteNames, detail);
    }

    static List<Commit> parseHistory(String output) {
        List<String> fields = nulSeparated(output);
        List<Commit> commits = new ArrayList<>();
        for (int index = 0; index + 4 < fields.size(); index += 5) {
            String hash = fields.get(index);
            if (hash.isEmpty()) continue;
            commits.add(new Commit(hash, fields.get(index + 1), fields.get(index + 2), fields.get(index + 3), fields.get(index + 4)));
        }
        return commits;
    }

    private static List<String> lines(String output) {
        List<String> values = new ArrayList<>();
        for (String line : (output == null ? "" : output).split("\\R")) {
            if (!line.isBlank()) values.add(line.strip());
        }
        return List.copyOf(values);
    }

    private static List<String> nulSeparated(String output) {
        List<String> values = new ArrayList<>();
        String source = output == null ? "" : output;
        int start = 0;
        for (int index = 0; index < source.length(); index++) {
            if (source.charAt(index) == '\u0000') {
                values.add(source.substring(start, index));
                start = index + 1;
            }
        }
        if (start < source.length()) values.add(source.substring(start));
        return values;
    }

    private static boolean outputTruncated(CommandResult result) {
        return result.stdout.endsWith("\n[shed: output truncated]");
    }

    private static String commandError(CommandResult result, String fallback) {
        String message = result.stderr.isBlank() ? result.stdout.strip() : result.stderr.strip();
        return message.isEmpty() ? fallback + " (exit " + result.exitCode + ")" : message;
    }

    private static String inline(String value) {
        return value.replace("\r", "⏎").replace("\n", "⏎").replace("\t", "⇥");
    }
}
