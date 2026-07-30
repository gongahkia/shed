package shed;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

final class GitChangesWorkbenchModel {
    enum State {
        READY,
        UNAVAILABLE,
        ERROR
    }

    record Change(String indexStatus, String worktreeStatus, String path, String originalPath) {
        String displayPath() {
            return originalPath == null || originalPath.isBlank() ? path : originalPath + " → " + path;
        }
    }

    record Snapshot(State state, String root, String branch, List<Change> changes, String detail) {
        Snapshot {
            root = root == null ? "" : root;
            branch = branch == null ? "" : branch;
            changes = changes == null ? List.of() : List.copyOf(changes);
            detail = detail == null ? "" : detail;
        }

        boolean available() {
            return state == State.READY;
        }
    }

    private GitChangesWorkbenchModel() { }

    static Snapshot unavailable(String detail) {
        return new Snapshot(State.UNAVAILABLE, "", "", List.of(), detail);
    }

    static Snapshot fromStatus(File root, CommandResult result) {
        if (root == null) return unavailable("Not inside a Git repository.");
        if (result == null) return new Snapshot(State.ERROR, root.getAbsolutePath(), "", List.of(), "Git status did not return a result.");
        if (result.exitCode != 0) {
            String detail = firstNonBlank(result.stderr, result.stdout, "git status failed (exit " + result.exitCode + ")");
            return new Snapshot(State.ERROR, root.getAbsolutePath(), "", List.of(), detail);
        }
        if (result.stdout.endsWith("\n[shed: output truncated]")) {
            return new Snapshot(State.ERROR, root.getAbsolutePath(), "", List.of(),
                "Git status output was truncated; increase process.output.max.bytes before inspecting changes.");
        }
        return parse(root.getAbsolutePath(), result.stdout);
    }

    static Snapshot parse(String root, String porcelain) {
        String branch = "";
        List<Change> changes = new ArrayList<>();
        List<String> entries = nulSeparated(porcelain);
        for (int index = 0; index < entries.size(); index++) {
            String entry = entries.get(index);
            if (entry.isEmpty()) continue;
            if (entry.startsWith("## ")) {
                branch = entry.substring(3);
                continue;
            }
            if (entry.length() < 4 || entry.charAt(2) != ' ') continue;
            String indexStatus = status(entry.charAt(0));
            String worktreeStatus = status(entry.charAt(1));
            String path = safePath(entry.substring(3));
            String originalPath = null;
            if ((entry.charAt(0) == 'R' || entry.charAt(0) == 'C') && index + 1 < entries.size()) {
                originalPath = safePath(entries.get(++index));
            }
            changes.add(new Change(indexStatus, worktreeStatus, path, originalPath));
        }
        String detail = changes.isEmpty() ? "Working tree is clean." : changes.size() + " changed file" + (changes.size() == 1 ? "" : "s") + ".";
        return new Snapshot(State.READY, root, branch, changes, detail);
    }

    private static String status(char value) {
        return switch (value) {
            case ' ' -> "";
            case 'M' -> "Modified";
            case 'A' -> "Added";
            case 'D' -> "Deleted";
            case 'R' -> "Renamed";
            case 'C' -> "Copied";
            case 'U' -> "Unmerged";
            case '?' -> "Untracked";
            case '!' -> "Ignored";
            default -> String.valueOf(value);
        };
    }

    private static List<String> nulSeparated(String value) {
        String source = value == null ? "" : value;
        List<String> values = new ArrayList<>();
        int start = 0;
        for (int index = 0; index < source.length(); index++) {
            if (source.charAt(index) == '\u0000') {
                values.add(source.substring(start, index));
                start = index + 1;
            }
        }
        values.add(source.substring(start));
        return values;
    }

    private static String safePath(String value) {
        return value.replace("\n", "⏎").replace("\r", "␍").replace("\t", "⇥");
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) if (value != null && !value.isBlank()) return value.strip();
        return "";
    }
}
