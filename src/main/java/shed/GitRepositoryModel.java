package shed;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

final class GitRepositoryModel {
    enum State { READY, UNAVAILABLE, ERROR }

    record Worktree(String path, String head, String branch, boolean main, boolean bare, String locked, String prunable) {
        Worktree {
            path = path == null ? "" : path;
            head = head == null ? "" : head;
            branch = branch == null ? "" : branch;
            locked = locked == null ? "" : locked;
            prunable = prunable == null ? "" : prunable;
        }

        String display() {
            String label = branch.isBlank() ? (bare ? "bare" : "detached HEAD") : branch.replace("refs/heads/", "");
            return (main ? "main  " : "linked  ") + label + "  " + path;
        }

        @Override public String toString() { return display(); }
    }

    record Stash(String reference, String hash, String subject, String createdAt) {
        Stash {
            reference = reference == null ? "" : reference;
            hash = hash == null ? "" : hash;
            subject = subject == null ? "" : subject;
            createdAt = createdAt == null ? "" : createdAt;
        }

        String display() { return reference + "  " + subject.replace('\n', ' ').replace('\r', ' '); }
        @Override public String toString() { return display(); }
    }

    record Snapshot(State state, String root, List<Worktree> worktrees, List<Stash> stashes, String detail) {
        Snapshot {
            root = root == null ? "" : root;
            worktrees = worktrees == null ? List.of() : List.copyOf(worktrees);
            stashes = stashes == null ? List.of() : List.copyOf(stashes);
            detail = detail == null ? "" : detail;
        }
        boolean available() { return state == State.READY; }
    }

    private GitRepositoryModel() { }

    static Snapshot unavailable(String detail) {
        return new Snapshot(State.UNAVAILABLE, "", List.of(), List.of(), detail);
    }

    static Snapshot fromCommands(File root, CommandResult worktrees, CommandResult stashes) {
        if (root == null) return unavailable("Not inside a Git repository.");
        if (worktrees == null || worktrees.exitCode != 0) {
            return new Snapshot(State.ERROR, root.getAbsolutePath(), List.of(), List.of(), error(worktrees, "git worktree list failed"));
        }
        if (truncated(worktrees)) {
            return new Snapshot(State.ERROR, root.getAbsolutePath(), List.of(), List.of(), "Git worktree output was truncated.");
        }
        if (stashes == null || stashes.exitCode != 0) {
            return new Snapshot(State.ERROR, root.getAbsolutePath(), parseWorktrees(worktrees.stdout), List.of(), error(stashes, "git stash list failed"));
        }
        if (truncated(stashes)) {
            return new Snapshot(State.ERROR, root.getAbsolutePath(), parseWorktrees(worktrees.stdout), List.of(), "Git stash output was truncated.");
        }
        List<Worktree> trees = parseWorktrees(worktrees.stdout);
        List<Stash> entries = parseStashes(stashes.stdout);
        return new Snapshot(State.READY, root.getAbsolutePath(), trees, entries,
            trees.size() + " worktree" + (trees.size() == 1 ? "" : "s") + ", " + entries.size() + " stash" + (entries.size() == 1 ? "" : "es") + ".");
    }

    static List<Worktree> parseWorktrees(String output) {
        List<Worktree> result = new ArrayList<>();
        String path = "", head = "", branch = "", locked = "", prunable = "";
        boolean bare = false;
        for (String line : nul(output)) {
            if (line.isEmpty()) {
                if (!path.isEmpty()) result.add(new Worktree(path, head, branch, result.isEmpty(), bare, locked, prunable));
                path = head = branch = locked = prunable = "";
                bare = false;
                continue;
            }
            int split = line.indexOf(' ');
            String key = split < 0 ? line : line.substring(0, split);
            String value = split < 0 ? "" : line.substring(split + 1);
            switch (key) {
                case "worktree" -> path = value;
                case "HEAD" -> head = value;
                case "branch" -> branch = value;
                case "bare" -> bare = true;
                case "locked" -> locked = value;
                case "prunable" -> prunable = value;
                default -> { }
            }
        }
        if (!path.isEmpty()) result.add(new Worktree(path, head, branch, result.isEmpty(), bare, locked, prunable));
        return List.copyOf(result);
    }

    static List<Stash> parseStashes(String output) {
        List<Stash> result = new ArrayList<>();
        for (String row : nul(output)) {
            if (row.isEmpty()) continue;
            String[] fields = row.split("\\u001f", -1);
            if (fields.length >= 4 && !fields[0].isBlank()) result.add(new Stash(fields[0], fields[1], fields[2], fields[3]));
        }
        return List.copyOf(result);
    }

    private static List<String> nul(String output) {
        String source = output == null ? "" : output;
        List<String> values = new ArrayList<>();
        int start = 0;
        for (int index = 0; index < source.length(); index++) {
            if (source.charAt(index) == '\0') {
                values.add(source.substring(start, index));
                start = index + 1;
            }
        }
        if (start < source.length()) values.add(source.substring(start));
        return values;
    }

    private static boolean truncated(CommandResult result) {
        return result != null && result.stdout.endsWith("\n[shed: output truncated]");
    }

    private static String error(CommandResult result, String fallback) {
        if (result == null) return fallback;
        String message = result.stderr.isBlank() ? result.stdout.strip() : result.stderr.strip();
        return message.isBlank() ? fallback + " (exit " + result.exitCode + ")" : message;
    }
}
