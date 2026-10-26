package shed;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;

/** Bounded result history and one explicit, private work description per workspace. No task scheduler. */
final class AgentInteractionHistory {
    record Entry(Instant time, AgentAction action, String instruction, String proposal, AgentAnswer answer, String summary) {
        @Override public String toString() { return action + " · " + instruction.replace('\n', ' ').substring(0, Math.min(100, instruction.length())); }
        int characters() { return instruction.length() + summary.length() + (answer == null ? 0 : answer.text().length() + answer.locations().stream().mapToInt(l -> l.path().toString().length() + l.note().length() + 128).sum()); }
    }
    private final LinkedHashMap<Path, List<Entry>> workspaces = new LinkedHashMap<>();
    void add(Path root, Entry entry) {
        List<Entry> entries = workspaces.computeIfAbsent(WorkspacePaths.root(root), ignored -> new ArrayList<>());
        entries.addFirst(entry);
        int total = 0, keep = 0;
        for (Entry value : entries) { total += value.characters(); if (keep >= 50 || total > 2 * 1024 * 1024) break; keep++; }
        entries.subList(keep, entries.size()).clear();
        while (workspaces.size() > 8) workspaces.remove(workspaces.keySet().iterator().next());
    }
    List<Entry> entries(Path root) { return List.copyOf(workspaces.getOrDefault(WorkspacePaths.root(root), List.of())); }
    void clear(Path root) { workspaces.remove(WorkspacePaths.root(root)); }
    private static Path directory(Path sessions, Path root) { return sessions.resolve("agent-notes").resolve(WorkspacePaths.id(WorkspacePaths.root(root))); }
    static String loadWork(Path sessions, Path root) throws IOException {
        if (sessions == null) return "";
        Path directory = directory(sessions, root);
        if (!Files.exists(directory, LinkOption.NOFOLLOW_LINKS)) return "";
        Path file = PrivateWorkspaceFiles.existingDirectory(directory).resolve("work.txt");
        if (!Files.exists(file, LinkOption.NOFOLLOW_LINKS)) return "";
        String text = new String(PrivateWorkspaceFiles.read(file, 32768), StandardCharsets.UTF_8);
        validateWork(text); return text;
    }
    static void saveWork(Path sessions, Path root, String text) throws IOException {
        validateWork(text);
        if (sessions == null) throw new IOException("A session storage directory is required to save work");
        Path directory = PrivateWorkspaceFiles.directory(directory(sessions, root)), file = directory.resolve("work.txt");
        byte[] content = text.getBytes(StandardCharsets.UTF_8);
        if (Files.exists(file, LinkOption.NOFOLLOW_LINKS)) {
            PrivateWorkspaceFiles.read(file, 32768);
            AtomicFileWriter.write(file, content);
        } else PrivateWorkspaceFiles.create(directory, "work.txt", content);
    }
    private static void validateWork(String text) { if (text == null || text.length() > 7500 || text.indexOf('\0') >= 0) throw new IllegalArgumentException("Work descriptions support at most 7,500 characters without NUL"); }
    static String workPrompt(String description, boolean run) {
        validateWork(description);
        if (description.isBlank()) throw new IllegalArgumentException("Describe the current work first");
        return (run ? "Continue the following work. Inspect the current implementation and propose remaining changes."
            : "Review progress on the following work. Inspect current code and changes, identify remaining requirements and bugs, and cite relevant locations.")
            + " Do not assume prior suggestions were applied or checks passed.\n\nWork description:\n" + description;
    }
}
