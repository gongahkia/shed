package shed;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/** Bounded, lexical workspace-symbol fallback for files without an LSP result. */
final class WorkspaceSymbolService {
    static final int DEFAULT_MAX_RESULTS = 300;
    static final long DEFAULT_MAX_FILE_BYTES = 2L * 1024L * 1024L;

    private final WorkspaceIndexService indexService;
    private final SymbolService symbolService;
    private final int maxResults;
    private final long maxFileBytes;

    WorkspaceSymbolService(WorkspaceIndexService indexService, SymbolService symbolService) {
        this(indexService, symbolService, DEFAULT_MAX_RESULTS, DEFAULT_MAX_FILE_BYTES);
    }

    WorkspaceSymbolService(WorkspaceIndexService indexService, SymbolService symbolService, int maxResults, long maxFileBytes) {
        this.indexService = Objects.requireNonNull(indexService, "indexService");
        this.symbolService = Objects.requireNonNull(symbolService, "symbolService");
        if (maxResults <= 0) throw new IllegalArgumentException("maxResults must be positive");
        if (maxFileBytes <= 0) throw new IllegalArgumentException("maxFileBytes must be positive");
        this.maxResults = maxResults;
        this.maxFileBytes = maxFileBytes;
    }

    SearchResult search(boolean persistentIndexEnabled, List<Path> workspaceRoots, String query,
                        WorkspaceIndexService.Cancellation cancellation) {
        String needle = requireQuery(query);
        WorkspaceIndexService.Cancellation effectiveCancellation = cancellation == null ? WorkspaceIndexService.Cancellation.NONE : cancellation;
        List<Path> roots = workspaceRoots == null ? List.of() : workspaceRoots.stream().filter(Objects::nonNull).distinct().toList();
        if (roots.isEmpty()) return new SearchResult(State.FAILED, List.of(), false, 0, 0, 0, "no workspace folders");

        List<Match> matches = new ArrayList<>();
        int indexedFiles = 0;
        int filesRead = 0;
        int skippedFiles = 0;
        for (Path root : roots) {
            if (effectiveCancellation.isCancelled()) {
                return new SearchResult(State.CANCELLED, matches, false, indexedFiles, filesRead, skippedFiles, "symbol search cancelled");
            }
            WorkspaceIndexService.BuildResult indexed = persistentIndexEnabled
                ? indexService.recover(true, root, effectiveCancellation, WorkspaceIndexService.Observer.NO_OP)
                : indexService.scan(root, effectiveCancellation, WorkspaceIndexService.Observer.NO_OP);
            if (indexed.status().state() == WorkspaceIndexService.State.CANCELLED) {
                return new SearchResult(State.CANCELLED, matches, false, indexedFiles, filesRead, skippedFiles, "symbol search cancelled");
            }
            if (indexed.status().state() != WorkspaceIndexService.State.READY || indexed.index() == null) {
                return new SearchResult(State.FAILED, matches, false, indexedFiles, filesRead, skippedFiles,
                    root + ": " + indexed.status().message());
            }
            Path normalizedRoot = Path.of(indexed.index().root()).toAbsolutePath().normalize();
            indexedFiles += indexed.index().entries().size();
            for (WorkspaceIndexService.Entry entry : indexed.index().entries()) {
                if (effectiveCancellation.isCancelled()) {
                    return new SearchResult(State.CANCELLED, matches, false, indexedFiles, filesRead, skippedFiles, "symbol search cancelled");
                }
                if (matches.size() >= maxResults) {
                    return complete(matches, true, indexedFiles, filesRead, skippedFiles);
                }
                if (entry.size() > maxFileBytes) {
                    skippedFiles++;
                    continue;
                }
                Path file = normalizedRoot.resolve(entry.relativePath()).normalize();
                if (!file.startsWith(normalizedRoot) || !readableRegularFile(file)) {
                    skippedFiles++;
                    continue;
                }
                FileType detectedType = FileType.detect(file.toFile(), "");
                if (detectedType == FileType.TEXT) continue;
                try {
                    String text = Files.readString(file, StandardCharsets.UTF_8);
                    filesRead++;
                    FileType type = detectedType == FileType.UNKNOWN ? FileType.detect(file.toFile(), text) : detectedType;
                    if (type == FileType.UNKNOWN || type == FileType.TEXT) continue;
                    for (SymbolService.Symbol symbol : symbolService.collectSymbols(text, type)) {
                        if (effectiveCancellation.isCancelled()) {
                            return new SearchResult(State.CANCELLED, matches, false, indexedFiles, filesRead, skippedFiles, "symbol search cancelled");
                        }
                        if (!matchesQuery(symbol, file, normalizedRoot, needle)) continue;
                        matches.add(new Match(file.toString(), normalizedRoot.relativize(file).toString(), symbol.getLine(), symbol.getName(),
                            symbol.getKind(), symbol.getLevel()));
                        if (matches.size() >= maxResults) return complete(matches, true, indexedFiles, filesRead, skippedFiles);
                    }
                } catch (IOException | SecurityException error) {
                    skippedFiles++;
                }
            }
        }
        return complete(matches, false, indexedFiles, filesRead, skippedFiles);
    }

    private SearchResult complete(List<Match> matches, boolean truncated, int indexedFiles, int filesRead, int skippedFiles) {
        matches.sort(Comparator.comparing(Match::name, String.CASE_INSENSITIVE_ORDER)
            .thenComparing(Match::filePath, String.CASE_INSENSITIVE_ORDER).thenComparingInt(Match::line));
        String message = matches.isEmpty() ? "no symbols" : truncated ? "result limit reached" : "symbol search complete";
        return new SearchResult(State.COMPLETE, List.copyOf(matches), truncated, indexedFiles, filesRead, skippedFiles, message);
    }

    private static boolean matchesQuery(SymbolService.Symbol symbol, Path file, Path root, String needle) {
        String values = symbol.getName() + " " + symbol.getKind() + " " + root.relativize(file);
        return values.toLowerCase(Locale.ROOT).contains(needle);
    }

    private static boolean readableRegularFile(Path file) {
        try {
            BasicFileAttributes attributes = Files.readAttributes(file, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            return attributes.isRegularFile() && !attributes.isSymbolicLink();
        } catch (IOException | SecurityException error) {
            return false;
        }
    }

    private static String requireQuery(String query) {
        String value = query == null ? "" : query.trim();
        if (value.isEmpty() || value.indexOf('\0') >= 0 || value.indexOf('\n') >= 0 || value.indexOf('\r') >= 0) {
            throw new IllegalArgumentException("symbol query must be a non-empty single line");
        }
        return value.toLowerCase(Locale.ROOT);
    }

    record Match(String filePath, String relativePath, int line, String name, String kind, int level) {
        Match {
            filePath = filePath == null ? "" : filePath;
            relativePath = relativePath == null ? "" : relativePath;
            line = Math.max(1, line);
            name = name == null ? "" : name;
            kind = kind == null ? "symbol" : kind;
            level = Math.max(1, level);
        }
    }

    record SearchResult(State state, List<Match> matches, boolean truncated, int indexedFiles, int filesRead, int skippedFiles,
                        String message) {
        SearchResult {
            state = state == null ? State.FAILED : state;
            matches = matches == null ? List.of() : List.copyOf(matches);
            indexedFiles = Math.max(0, indexedFiles);
            filesRead = Math.max(0, filesRead);
            skippedFiles = Math.max(0, skippedFiles);
            message = message == null ? "" : message;
        }
    }

    enum State { COMPLETE, CANCELLED, FAILED }
}
