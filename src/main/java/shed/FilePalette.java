package shed;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/** Quick Open query semantics and acceptance, using a snapshot of the discovered paths. */
public final class FilePalette implements PaletteSession {
    public interface Opener {
        boolean isOpen(String path);
        String open(String path, int line, int column, String label);
    }
    private final Map<String, String> paths;
    private final List<String> candidates;
    private final String currentLabel;
    private final FuzzyMatchService matcher;
    private Opener opener;
    private volatile boolean closed;

    public FilePalette(Map<String, String> paths, List<String> recent, String currentPath,
                       FuzzyMatchService matcher, Opener opener) {
        this.paths = Collections.unmodifiableMap(new LinkedHashMap<>(paths));
        Map<String, String> labels = new HashMap<>();
        paths.forEach((label, path) -> labels.put(identity(path), label));
        Set<String> ordered = new LinkedHashSet<>();
        for (String path : recent) {
            String label = labels.get(identity(path));
            if (label != null) ordered.add(label);
        }
        ordered.addAll(paths.keySet());
        candidates = List.copyOf(ordered);
        currentLabel = currentPath == null ? null : labels.get(identity(currentPath));
        this.matcher = matcher;
        this.opener = opener;
    }
    private static String identity(String path) { return Path.of(path).toAbsolutePath().normalize().toString(); }
    @Override public List<String> candidates() { return candidates; }
    @Override public List<String> query(String text) {
        if (closed) return List.of();
        if (paths.containsKey(text)) return List.of(text);
        QuickOpenQuery query = QuickOpenQuery.parse(text);
        if (query.hasLocation() && query.fileQuery().isEmpty())
            return currentLabel == null ? List.of() : List.of(currentLabel);
        return matcher.matchPathStrings(query.fileQuery(), candidates, 200);
    }
    @Override public String accept(String selected, String text) {
        if (closed) return "File selection is unavailable";
        String path = paths.get(selected);
        if (path == null) return "File selection is unavailable";
        if (!opener.isOpen(path) && !Files.isRegularFile(Path.of(path)))
            return "File is no longer available: " + path;
        QuickOpenQuery query = paths.containsKey(text) ? new QuickOpenQuery(text, 0, 0) : QuickOpenQuery.parse(text);
        return opener.open(path, query.line(), query.column(), selected);
    }
    @Override public void close() { closed = true; opener = null; }
}
