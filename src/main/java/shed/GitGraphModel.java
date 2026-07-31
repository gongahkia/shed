package shed;

import java.io.File;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

final class GitGraphModel {
    enum State { READY, UNAVAILABLE, ERROR }

    record Commit(String hash, List<String> parents, String decorations, String subject, String author, String timestamp) {
        Commit {
            hash = value(hash);
            parents = parents == null ? List.of() : parents.stream().filter(parent -> !value(parent).isBlank()).map(GitGraphModel::value).toList();
            decorations = value(decorations);
            subject = inline(subject);
            author = value(author);
            timestamp = value(timestamp);
        }

        String shortHash() {
            return hash.length() <= 8 ? hash : hash.substring(0, 8);
        }
    }

    record Row(Commit commit, int lane, List<String> beforeLanes, List<String> afterLanes) {
        Row {
            beforeLanes = beforeLanes == null ? List.of() : List.copyOf(beforeLanes);
            afterLanes = afterLanes == null ? List.of() : List.copyOf(afterLanes);
        }
    }

    record Snapshot(State state, String root, List<Row> rows, int laneCount, String detail) {
        Snapshot {
            root = value(root);
            rows = rows == null ? List.of() : List.copyOf(rows);
            laneCount = Math.max(0, laneCount);
            detail = value(detail);
        }

        boolean available() {
            return state == State.READY;
        }
    }

    private GitGraphModel() { }

    static Snapshot unavailable(String detail) {
        return new Snapshot(State.UNAVAILABLE, "", List.of(), 0, detail);
    }

    static Snapshot fromCommand(File root, CommandResult result) {
        if (root == null) return unavailable("Not inside a Git repository.");
        if (result == null) return new Snapshot(State.ERROR, root.getAbsolutePath(), List.of(), 0, "Git graph did not return a result.");
        if (result.exitCode != 0) {
            return new Snapshot(State.ERROR, root.getAbsolutePath(), List.of(), 0, commandError(result, "git log failed"));
        }
        if (outputTruncated(result)) {
            return new Snapshot(State.ERROR, root.getAbsolutePath(), List.of(), 0,
                "Git graph output was truncated; increase process.output.max.bytes before inspecting history.");
        }
        List<Commit> commits = parseHistory(result.stdout);
        List<Row> rows = graphRows(commits);
        int laneCount = rows.stream().mapToInt(row -> Math.max(row.beforeLanes().size(), row.afterLanes().size())).max().orElse(0);
        String detail = commits.isEmpty() ? "No commits." : commits.size() + " commit" + (commits.size() == 1 ? "" : "s") + ".";
        return new Snapshot(State.READY, root.getAbsolutePath(), rows, laneCount, detail);
    }

    static List<Commit> parseHistory(String output) {
        List<String> fields = nulSeparated(output);
        List<Commit> commits = new ArrayList<>();
        for (int index = 0; index + 5 < fields.size(); index += 6) {
            String hash = fields.get(index);
            if (hash.isBlank()) continue;
            commits.add(new Commit(hash, parentHashes(fields.get(index + 1)), fields.get(index + 2), fields.get(index + 3),
                fields.get(index + 4), fields.get(index + 5)));
        }
        return List.copyOf(commits);
    }

    static List<Row> graphRows(List<Commit> commits) {
        List<Row> rows = new ArrayList<>();
        List<String> lanes = new ArrayList<>();
        if (commits == null) return List.of();
        for (Commit commit : commits) {
            if (commit == null || commit.hash().isBlank()) continue;
            int lane = lanes.indexOf(commit.hash());
            if (lane < 0) {
                lane = 0;
                lanes.add(0, commit.hash());
            }
            List<String> before = List.copyOf(lanes);
            lanes.remove(lane);
            lanes.addAll(lane, commit.parents());
            deduplicate(lanes);
            List<String> after = List.copyOf(lanes);
            rows.add(new Row(commit, lane, before, after));
        }
        return List.copyOf(rows);
    }

    private static List<String> parentHashes(String parents) {
        if (parents == null || parents.isBlank()) return List.of();
        List<String> values = new ArrayList<>();
        for (String parent : parents.trim().split("\\s+")) {
            if (!parent.isBlank()) values.add(parent);
        }
        return List.copyOf(values);
    }

    private static void deduplicate(List<String> lanes) {
        Set<String> seen = new HashSet<>();
        lanes.removeIf(hash -> !seen.add(hash));
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
        return value(value).replace("\r", "⏎").replace("\n", "⏎").replace("\t", "⇥");
    }

    private static String value(String value) {
        return value == null ? "" : value;
    }
}
