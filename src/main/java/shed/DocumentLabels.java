package shed;

import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.*;

/** Presentation labels only: retain the name and the shortest distinguishing parent suffix. */
final class DocumentLabels {
    private DocumentLabels() { }

    static Map<DocumentWorkspace.Id, String> forDocuments(List<DocumentWorkspace.Document> documents) {
        Map<String, List<DocumentWorkspace.Document>> groups = new LinkedHashMap<>();
        for (var document : documents) groups.computeIfAbsent(document.name(), ignored -> new ArrayList<>()).add(document);
        Map<DocumentWorkspace.Id, String> labels = new HashMap<>();
        for (var group : groups.values()) {
            if (group.size() == 1) { labels.put(group.getFirst().id(), group.getFirst().name()); continue; }
            Map<DocumentWorkspace.Id, Path> parents = new HashMap<>();
            Map<String, Integer> counts = new HashMap<>();
            for (var document : group) {
                try {
                    if (document.path() == null || document.path().isBlank()) continue;
                    Path parent = Path.of(document.path()).toAbsolutePath().normalize().getParent();
                    if (parent == null) continue;
                    parents.put(document.id(), parent);
                    for (int depth = 1; depth <= parent.getNameCount(); depth++) counts.merge(suffix(parent, depth), 1, Integer::sum);
                } catch (InvalidPathException ignored) { /* Non-file buffers still receive stable ordinal labels. */ }
            }
            int ordinal = 0;
            for (var document : group) {
                ordinal++;
                Path parent = parents.get(document.id());
                String context = Integer.toString(ordinal);
                if (parent != null) {
                    context = parent.toString();
                    for (int depth = 1; depth <= parent.getNameCount(); depth++) {
                        String candidate = suffix(parent, depth);
                        if (counts.getOrDefault(candidate, 0) == 1) { context = candidate; break; }
                    }
                }
                labels.put(document.id(), document.name() + " — " + context);
            }
        }
        return Map.copyOf(labels);
    }

    private static String suffix(Path parent, int depth) {
        return parent.subpath(parent.getNameCount() - depth, parent.getNameCount()).toString();
    }
}
