package shed;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

final class WorkspaceTextSearchService {
    static final int DEFAULT_MAX_RESULTS = 200;
    static final int DEFAULT_BATCH_SIZE = 25;

    private final WorkspaceIndexService indexService;
    private final int maxResults;
    private final int batchSize;

    WorkspaceTextSearchService(WorkspaceIndexService indexService) {
        this(indexService, DEFAULT_MAX_RESULTS, DEFAULT_BATCH_SIZE);
    }

    WorkspaceTextSearchService(WorkspaceIndexService indexService, int maxResults, int batchSize) {
        this.indexService = Objects.requireNonNull(indexService, "indexService");
        this.maxResults = positive(maxResults, "maxResults");
        this.batchSize = positive(batchSize, "batchSize");
    }

    SearchResult search(boolean persistentIndexEnabled, Path root, String query, WorkspaceIndexService.Cancellation cancellation, Observer observer) {
        return search(persistentIndexEnabled, List.of(root), WorkspaceSearchQuery.literal(query), cancellation, observer);
    }

    SearchResult search(boolean persistentIndexEnabled, List<Path> roots, String query, WorkspaceIndexService.Cancellation cancellation, Observer observer) {
        return search(persistentIndexEnabled, roots, WorkspaceSearchQuery.literal(query), cancellation, observer);
    }

    SearchResult search(boolean persistentIndexEnabled, List<Path> workspaceRoots, WorkspaceSearchQuery query,
                        WorkspaceIndexService.Cancellation cancellation, Observer observer) {
        WorkspaceIndexService.Cancellation stop = cancellation == null ? WorkspaceIndexService.Cancellation.NONE : cancellation;
        Observer updates = observer == null ? Observer.NO_OP : observer;
        Source source = persistentIndexEnabled ? Source.PERSISTENT_INDEX : Source.AD_HOC;
        List<Path> roots = workspaceRoots == null ? List.of() : workspaceRoots.stream().filter(Objects::nonNull).distinct().toList();
        List<Match> matches = new ArrayList<>();
        List<Match> batch = new ArrayList<>();
        java.util.Set<Path> visited = new java.util.HashSet<>();
        SearchFileFilter filter = new SearchFileFilter(query.include(), query.exclude());
        java.util.regex.Pattern compiled = query.pattern().compile();
        int filesRead = 0, unreadable = 0, indexedFiles = 0;
        boolean truncated = false;
        try {
            outer: for (Path workspace : roots) {
                WorkspaceIndexService.BuildResult indexed = persistentIndexEnabled
                    ? indexService.recover(true, workspace, stop, WorkspaceIndexService.Observer.NO_OP)
                    : indexService.scan(workspace, stop, WorkspaceIndexService.Observer.NO_OP);
                if (stop.isCancelled() || indexed.status().state() == WorkspaceIndexService.State.CANCELLED)
                    throw new java.util.concurrent.CancellationException();
                if (indexed.status().state() != WorkspaceIndexService.State.READY || indexed.index() == null)
                    return new SearchResult(source, State.FAILED, matches, false, filesRead, unreadable, indexedFiles, indexed.status().message());
                Path root = Path.of(indexed.index().root()).toAbsolutePath().normalize();
                indexedFiles += indexed.index().entries().size();
                for (WorkspaceIndexService.Entry entry : indexed.index().entries()) {
                    if (stop.isCancelled()) throw new java.util.concurrent.CancellationException();
                    Path relative = Path.of(entry.relativePath());
                    if (!filter.accepts(relative, stop::isCancelled)) continue;
                    Path file = root.resolve(relative).normalize();
                    if (!file.startsWith(root) || !readableRegularFile(file)) { unreadable++; continue; }
                    if (!visited.add(WorkspaceRootResolver.normalizedIdentity(file))) continue;
                    try (BufferedReader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
                        filesRead++;
                        int lineNumber = 0;
                        String line;
                        while ((line = reader.readLine()) != null) {
                            if (stop.isCancelled()) throw new java.util.concurrent.CancellationException();
                            if (line.indexOf('\0') >= 0) break;
                            lineNumber++;
                            java.util.regex.Matcher matcher = query.pattern().matcher(compiled, line, stop::isCancelled);
                            while (matcher.find()) {
                                if (stop.isCancelled()) throw new java.util.concurrent.CancellationException();
                                if (matches.size() == maxResults) { truncated = true; break outer; }
                                Match match = new Match(file.toString(), lineNumber, matcher.start() + 1, preview(line));
                                matches.add(match);
                                batch.add(match);
                                if (batch.size() >= batchSize) publish(batch, updates);
                            }
                        }
                    } catch (IOException | SecurityException error) { unreadable++; }
                    updates.onProgress(filesRead, indexedFiles);
                }
            }
        } catch (java.util.concurrent.CancellationException cancelled) {
            publish(batch, updates);
            return new SearchResult(source, State.CANCELLED, matches, false, filesRead, unreadable, indexedFiles, "search cancelled");
        } catch (IllegalArgumentException | StackOverflowError error) {
            publish(batch, updates);
            return new SearchResult(source, State.FAILED, matches, false, filesRead, unreadable, indexedFiles,
                error instanceof StackOverflowError ? "Expression is too complex; simplify the regular expression" : error.getMessage());
        }
        publish(batch, updates);
        return new SearchResult(source, roots.isEmpty() ? State.FAILED : State.COMPLETE, matches, truncated,
            filesRead, unreadable, indexedFiles, roots.isEmpty() ? "no workspace folders"
                : truncated ? "result limit reached" : matches.isEmpty() ? "no matches" : "search complete");
    }

    private static boolean readableRegularFile(Path file) {
        try {
            BasicFileAttributes attributes = Files.readAttributes(file, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            return attributes.isRegularFile() && !attributes.isSymbolicLink();
        } catch (IOException | SecurityException error) {
            return false;
        }
    }

    private static void publish(List<Match> batch, Observer observer) {
        if (batch.isEmpty()) {
            return;
        }
        observer.onMatches(List.copyOf(batch));
        batch.clear();
    }

    private static String preview(String line) {
        String normalized = line.strip();
        return normalized.length() <= 240 ? normalized : normalized.substring(0, 239) + "…";
    }

    private static String requireQuery(String query) {
        String value = query == null ? "" : query.trim();
        if (value.isEmpty() || value.indexOf('\0') >= 0 || value.indexOf('\n') >= 0 || value.indexOf('\r') >= 0) {
            throw new IllegalArgumentException("search query must be a non-empty single line");
        }
        return value;
    }

    private static int positive(int value, String name) {
        if (value <= 0) {
            throw new IllegalArgumentException(name + " must be positive");
        }
        return value;
    }

    interface Observer {
        Observer NO_OP = matches -> { };

        void onMatches(List<Match> matches);
        default void onProgress(int filesRead, int indexedFiles) { }
    }

    enum Source {
        AD_HOC,
        PERSISTENT_INDEX
    }

    enum State {
        COMPLETE,
        CANCELLED,
        FAILED
    }

    record Match(String filePath, int line, int column, String preview) {
        Match {
            filePath = Objects.requireNonNull(filePath, "filePath");
            preview = Objects.requireNonNull(preview, "preview");
            if (filePath.isBlank() || line < 1 || column < 1) {
                throw new IllegalArgumentException("search match location is invalid");
            }
        }
    }

    record SearchResult(Source source, State state, List<Match> matches, boolean truncated, int filesRead, int unreadableFiles,
                        int indexedFiles, String message) {
        SearchResult {
            source = Objects.requireNonNull(source, "source");
            state = Objects.requireNonNull(state, "state");
            matches = List.copyOf(Objects.requireNonNull(matches, "matches"));
            message = Objects.requireNonNull(message, "message");
            if (filesRead < 0 || unreadableFiles < 0 || indexedFiles < 0) {
                throw new IllegalArgumentException("search counters must be non-negative");
            }
        }
    }
}
