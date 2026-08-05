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

    SearchResult search(boolean persistentIndexEnabled, Path workspaceRoot, String query, WorkspaceIndexService.Cancellation cancellation,
                        Observer observer) {
        String needle = requireQuery(query);
        WorkspaceIndexService.Cancellation effectiveCancellation = cancellation == null ? WorkspaceIndexService.Cancellation.NONE : cancellation;
        Observer effectiveObserver = observer == null ? Observer.NO_OP : observer;
        Source source = persistentIndexEnabled ? Source.PERSISTENT_INDEX : Source.AD_HOC;
        WorkspaceIndexService.BuildResult indexed = persistentIndexEnabled
            ? indexService.recover(true, workspaceRoot, effectiveCancellation, WorkspaceIndexService.Observer.NO_OP)
            : indexService.scan(workspaceRoot, effectiveCancellation, WorkspaceIndexService.Observer.NO_OP);
        if (indexed.status().state() == WorkspaceIndexService.State.CANCELLED) {
            return new SearchResult(source, State.CANCELLED, List.of(), false, 0, 0, 0, indexed.status().message());
        }
        if (indexed.status().state() != WorkspaceIndexService.State.READY || indexed.index() == null) {
            return new SearchResult(source, State.FAILED, List.of(), false, 0, 0, 0, indexed.status().message());
        }

        Path root = Path.of(indexed.index().root()).toAbsolutePath().normalize();
        List<Match> matches = new ArrayList<>();
        List<Match> batch = new ArrayList<>();
        int filesRead = 0;
        int unreadableFiles = 0;
        boolean truncated = false;
        for (WorkspaceIndexService.Entry entry : indexed.index().entries()) {
            if (effectiveCancellation.isCancelled()) {
                publish(batch, effectiveObserver);
                return new SearchResult(source, State.CANCELLED, matches, false, filesRead, unreadableFiles, indexed.index().entries().size(),
                    "search cancelled");
            }
            Path file = root.resolve(entry.relativePath()).normalize();
            if (!file.startsWith(root) || !readableRegularFile(file)) {
                unreadableFiles++;
                continue;
            }
            try (BufferedReader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
                filesRead++;
                int lineNumber = 0;
                String line;
                while ((line = reader.readLine()) != null) {
                    lineNumber++;
                    int offset = 0;
                    while (offset < line.length()) {
                        if (effectiveCancellation.isCancelled()) {
                            publish(batch, effectiveObserver);
                            return new SearchResult(source, State.CANCELLED, matches, false, filesRead, unreadableFiles,
                                indexed.index().entries().size(), "search cancelled");
                        }
                        int matchOffset = line.indexOf(needle, offset);
                        if (matchOffset < 0) {
                            break;
                        }
                        Match match = new Match(file.toString(), lineNumber, matchOffset + 1, preview(line));
                        matches.add(match);
                        batch.add(match);
                        if (batch.size() >= batchSize) {
                            publish(batch, effectiveObserver);
                        }
                        if (matches.size() >= maxResults) {
                            truncated = true;
                            break;
                        }
                        offset = matchOffset + needle.length();
                    }
                    if (truncated) {
                        break;
                    }
                }
            } catch (IOException | SecurityException error) {
                unreadableFiles++;
            }
            if (truncated) {
                break;
            }
        }
        publish(batch, effectiveObserver);
        String message = matches.isEmpty() ? "no matches" : truncated ? "result limit reached" : "search complete";
        return new SearchResult(source, State.COMPLETE, matches, truncated, filesRead, unreadableFiles, indexed.index().entries().size(), message);
    }

    SearchResult search(boolean persistentIndexEnabled, List<Path> workspaceRoots, String query, WorkspaceIndexService.Cancellation cancellation,
                        Observer observer) {
        String needle = requireQuery(query);
        WorkspaceIndexService.Cancellation effectiveCancellation = cancellation == null ? WorkspaceIndexService.Cancellation.NONE : cancellation;
        Observer effectiveObserver = observer == null ? Observer.NO_OP : observer;
        List<Path> roots = workspaceRoots == null ? List.of() : workspaceRoots.stream().filter(Objects::nonNull).distinct().toList();
        if (roots.isEmpty()) {
            return new SearchResult(persistentIndexEnabled ? Source.PERSISTENT_INDEX : Source.AD_HOC, State.FAILED, List.of(), false, 0, 0, 0,
                "no workspace folders");
        }
        List<Match> matches = new ArrayList<>();
        int filesRead = 0;
        int unreadableFiles = 0;
        int indexedFiles = 0;
        for (Path root : roots) {
            if (effectiveCancellation.isCancelled()) {
                return new SearchResult(persistentIndexEnabled ? Source.PERSISTENT_INDEX : Source.AD_HOC, State.CANCELLED, matches, false,
                    filesRead, unreadableFiles, indexedFiles, "search cancelled");
            }
            int remaining = maxResults - matches.size();
            if (remaining <= 0) {
                return new SearchResult(persistentIndexEnabled ? Source.PERSISTENT_INDEX : Source.AD_HOC, State.COMPLETE, matches, true,
                    filesRead, unreadableFiles, indexedFiles, "result limit reached");
            }
            SearchResult result = new WorkspaceTextSearchService(indexService, remaining, batchSize)
                .search(persistentIndexEnabled, root, needle, effectiveCancellation, effectiveObserver);
            matches.addAll(result.matches());
            filesRead += result.filesRead();
            unreadableFiles += result.unreadableFiles();
            indexedFiles += result.indexedFiles();
            if (result.state() != State.COMPLETE) {
                return new SearchResult(result.source(), result.state(), matches, false, filesRead, unreadableFiles, indexedFiles,
                    root + ": " + result.message());
            }
            if (result.truncated()) {
                return new SearchResult(result.source(), State.COMPLETE, matches, true, filesRead, unreadableFiles, indexedFiles,
                    "result limit reached");
            }
        }
        return new SearchResult(persistentIndexEnabled ? Source.PERSISTENT_INDEX : Source.AD_HOC, State.COMPLETE, matches, false,
            filesRead, unreadableFiles, indexedFiles, matches.isEmpty() ? "no matches" : "search complete");
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
