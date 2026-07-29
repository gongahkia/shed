package shed;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

final class WorkspaceReplaceService {
    static final int DEFAULT_MAX_MATCHES = 200;

    private final WorkspaceIndexService indexService;
    private final int maxMatches;

    WorkspaceReplaceService(WorkspaceIndexService indexService) {
        this(indexService, DEFAULT_MAX_MATCHES);
    }

    WorkspaceReplaceService(WorkspaceIndexService indexService, int maxMatches) {
        this.indexService = Objects.requireNonNull(indexService, "indexService");
        if (maxMatches <= 0) {
            throw new IllegalArgumentException("maxMatches must be positive");
        }
        this.maxMatches = maxMatches;
    }

    Preview preview(boolean persistentIndexEnabled, Path workspaceRoot, String needle, String replacement,
                    WorkspaceIndexService.Cancellation cancellation) {
        String find = requireFind(needle);
        String replace = replacement == null ? "" : replacement;
        WorkspaceIndexService.Cancellation effectiveCancellation = cancellation == null ? WorkspaceIndexService.Cancellation.NONE : cancellation;
        Source source = persistentIndexEnabled ? Source.PERSISTENT_INDEX : Source.AD_HOC;
        WorkspaceIndexService.BuildResult indexed = persistentIndexEnabled
            ? indexService.recover(true, workspaceRoot, effectiveCancellation, WorkspaceIndexService.Observer.NO_OP)
            : indexService.scan(workspaceRoot, effectiveCancellation, WorkspaceIndexService.Observer.NO_OP);
        if (indexed.status().state() == WorkspaceIndexService.State.CANCELLED) {
            return new Preview(source, State.CANCELLED, null, indexed.status().message());
        }
        if (indexed.status().state() != WorkspaceIndexService.State.READY || indexed.index() == null) {
            return new Preview(source, State.FAILED, null, indexed.status().message());
        }

        Path root = Path.of(indexed.index().root()).toAbsolutePath().normalize();
        List<FilePlan> files = new ArrayList<>();
        int nextFileId = 1;
        int nextMatchId = 1;
        for (WorkspaceIndexService.Entry entry : indexed.index().entries()) {
            if (effectiveCancellation.isCancelled()) {
                return new Preview(source, State.CANCELLED, null, "preview cancelled");
            }
            Path file = root.resolve(entry.relativePath()).normalize();
            if (!file.startsWith(root) || !readableRegularFile(file)) {
                continue;
            }
            String content;
            try {
                content = Files.readString(file, StandardCharsets.UTF_8);
            } catch (IOException | SecurityException error) {
                continue;
            }
            List<MatchPlan> matches = matches(content, find, nextMatchId, maxMatches - nextMatchId + 1, effectiveCancellation);
            if (matches == null) {
                return new Preview(source, State.CANCELLED, null, "preview cancelled");
            }
            if (matches.isEmpty()) {
                continue;
            }
            nextMatchId += matches.size();
            files.add(new FilePlan(nextFileId++, file, content, matches));
            if (nextMatchId - 1 >= maxMatches) {
                break;
            }
        }
        return new Preview(source, State.READY, new Plan(source, find, replace, files, nextMatchId - 1 >= maxMatches), "preview ready");
    }

    ApplyResult apply(Plan plan, WorkspaceIndexService.Cancellation cancellation) {
        Plan snapshot = Objects.requireNonNull(plan, "plan").snapshot();
        WorkspaceIndexService.Cancellation effectiveCancellation = cancellation == null ? WorkspaceIndexService.Cancellation.NONE : cancellation;
        List<FileResult> results = new ArrayList<>();
        for (FilePlan file : snapshot.files()) {
            if (effectiveCancellation.isCancelled()) {
                return new ApplyResult(State.CANCELLED, results, "apply cancelled");
            }
            if (file.selectedMatchCount() == 0) {
                results.add(new FileResult(file.fileId(), file.path(), FileState.SKIPPED, "no selected matches"));
                continue;
            }
            try {
                if (!readableRegularFile(file.path())) {
                    results.add(new FileResult(file.fileId(), file.path(), FileState.SKIPPED, "file is no longer a regular file"));
                    continue;
                }
                String current = Files.readString(file.path(), StandardCharsets.UTF_8);
                if (!current.equals(file.originalContent())) {
                    results.add(new FileResult(file.fileId(), file.path(), FileState.SKIPPED, "file changed since preview"));
                    continue;
                }
                String updated = applySelections(file, snapshot.replacement());
                AtomicFileWriter.write(file.path(), updated.getBytes(StandardCharsets.UTF_8));
                results.add(new FileResult(file.fileId(), file.path(), FileState.CHANGED, "applied " + file.selectedMatchCount() + " match(es)"));
            } catch (IOException | SecurityException error) {
                results.add(new FileResult(file.fileId(), file.path(), FileState.FAILED, message(error)));
            }
        }
        return new ApplyResult(State.COMPLETE, results, "apply complete");
    }

    private List<MatchPlan> matches(String content, String needle, int firstId, int limit, WorkspaceIndexService.Cancellation cancellation) {
        List<MatchPlan> matches = new ArrayList<>();
        int offset = 0;
        while (offset <= content.length() - needle.length() && matches.size() < limit) {
            if (cancellation.isCancelled()) {
                return null;
            }
            int found = content.indexOf(needle, offset);
            if (found < 0) {
                break;
            }
            LineColumn location = lineColumn(content, found);
            matches.add(new MatchPlan(firstId + matches.size(), found, needle.length(), location.line(), location.column(), previewLine(content, found), true));
            offset = found + needle.length();
        }
        return matches;
    }

    private static String applySelections(FilePlan file, String replacement) {
        StringBuilder updated = new StringBuilder(file.originalContent().length());
        int offset = 0;
        for (MatchPlan match : file.matches()) {
            updated.append(file.originalContent(), offset, match.offset());
            updated.append(match.selected() ? replacement : file.originalContent(), match.offset(), match.offset() + match.length());
            offset = match.offset() + match.length();
        }
        updated.append(file.originalContent(), offset, file.originalContent().length());
        return updated.toString();
    }

    private static boolean readableRegularFile(Path file) {
        try {
            BasicFileAttributes attributes = Files.readAttributes(file, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            return attributes.isRegularFile() && !attributes.isSymbolicLink();
        } catch (IOException | SecurityException error) {
            return false;
        }
    }

    private static String requireFind(String value) {
        if (value == null || value.isEmpty() || value.indexOf('\0') >= 0 || value.indexOf('\n') >= 0 || value.indexOf('\r') >= 0) {
            throw new IllegalArgumentException("replacement pattern must be a non-empty single line");
        }
        return value;
    }

    private static LineColumn lineColumn(String content, int offset) {
        int line = 1;
        int column = 1;
        for (int index = 0; index < offset; index++) {
            if (content.charAt(index) == '\n') {
                line++;
                column = 1;
            } else {
                column++;
            }
        }
        return new LineColumn(line, column);
    }

    private static String previewLine(String content, int offset) {
        int start = content.lastIndexOf('\n', Math.max(0, offset - 1)) + 1;
        int end = content.indexOf('\n', offset);
        String line = content.substring(start, end < 0 ? content.length() : end).strip();
        return line.length() <= 160 ? line : line.substring(0, 159) + "…";
    }

    private static String message(Exception error) {
        String value = error.getMessage();
        return value == null || value.isBlank() ? error.getClass().getSimpleName() : value;
    }

    enum Source {
        AD_HOC,
        PERSISTENT_INDEX
    }

    enum State {
        READY,
        COMPLETE,
        CANCELLED,
        FAILED
    }

    enum FileState {
        CHANGED,
        SKIPPED,
        FAILED
    }

    enum Selection {
        ON,
        OFF,
        TOGGLE
    }

    record Preview(Source source, State state, Plan plan, String message) {
    }

    record ApplyResult(State state, List<FileResult> files, String message) {
        ApplyResult {
            files = List.copyOf(Objects.requireNonNull(files, "files"));
        }
    }

    record FileResult(int fileId, Path path, FileState state, String message) {
    }

    private record LineColumn(int line, int column) {
    }

    static final class Plan {
        private final Source source;
        private final String needle;
        private final String replacement;
        private final List<FilePlan> files;
        private final boolean truncated;

        Plan(Source source, String needle, String replacement, List<FilePlan> files, boolean truncated) {
            this.source = Objects.requireNonNull(source, "source");
            this.needle = Objects.requireNonNull(needle, "needle");
            this.replacement = Objects.requireNonNull(replacement, "replacement");
            this.files = new ArrayList<>(Objects.requireNonNull(files, "files"));
            this.truncated = truncated;
        }

        synchronized boolean selectFile(int fileId, Selection selection) {
            for (FilePlan file : files) {
                if (file.fileId() == fileId) {
                    file.selectAll(selection);
                    return true;
                }
            }
            return false;
        }

        synchronized boolean selectMatch(int matchId, Selection selection) {
            for (FilePlan file : files) {
                if (file.selectMatch(matchId, selection)) {
                    return true;
                }
            }
            return false;
        }

        synchronized Plan snapshot() {
            List<FilePlan> copies = new ArrayList<>();
            for (FilePlan file : files) {
                copies.add(file.copy());
            }
            return new Plan(source, needle, replacement, copies, truncated);
        }

        synchronized Source source() { return source; }
        synchronized String needle() { return needle; }
        synchronized String replacement() { return replacement; }
        synchronized List<FilePlan> files() { return List.copyOf(files); }
        synchronized boolean truncated() { return truncated; }
        synchronized int selectedMatchCount() {
            return files.stream().mapToInt(FilePlan::selectedMatchCount).sum();
        }
    }

    static final class FilePlan {
        private final int fileId;
        private final Path path;
        private final String originalContent;
        private final List<MatchPlan> matches;

        FilePlan(int fileId, Path path, String originalContent, List<MatchPlan> matches) {
            this.fileId = fileId;
            this.path = Objects.requireNonNull(path, "path");
            this.originalContent = Objects.requireNonNull(originalContent, "originalContent");
            this.matches = new ArrayList<>(Objects.requireNonNull(matches, "matches"));
        }

        int fileId() { return fileId; }
        Path path() { return path; }
        String originalContent() { return originalContent; }
        List<MatchPlan> matches() { return List.copyOf(matches); }
        int selectedMatchCount() { return (int) matches.stream().filter(MatchPlan::selected).count(); }
        void selectAll(Selection selection) { matches.forEach(match -> match.select(selection)); }
        boolean selectMatch(int matchId, Selection selection) {
            for (MatchPlan match : matches) {
                if (match.matchId() == matchId) {
                    match.select(selection);
                    return true;
                }
            }
            return false;
        }
        FilePlan copy() { return new FilePlan(fileId, path, originalContent, matches.stream().map(MatchPlan::copy).toList()); }
    }

    static final class MatchPlan {
        private final int matchId;
        private final int offset;
        private final int length;
        private final int line;
        private final int column;
        private final String preview;
        private boolean selected;

        MatchPlan(int matchId, int offset, int length, int line, int column, String preview, boolean selected) {
            this.matchId = matchId;
            this.offset = offset;
            this.length = length;
            this.line = line;
            this.column = column;
            this.preview = Objects.requireNonNull(preview, "preview");
            this.selected = selected;
        }

        int matchId() { return matchId; }
        int offset() { return offset; }
        int length() { return length; }
        int line() { return line; }
        int column() { return column; }
        String preview() { return preview; }
        boolean selected() { return selected; }
        void select(Selection selection) {
            selected = switch (selection) {
                case ON -> true;
                case OFF -> false;
                case TOGGLE -> !selected;
            };
        }
        MatchPlan copy() { return new MatchPlan(matchId, offset, length, line, column, preview, selected); }
    }
}
