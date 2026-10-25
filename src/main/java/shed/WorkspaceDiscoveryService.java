package shed;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** Bounded process-free discovery, reusable by machine and human workspace consumers. */
final class WorkspaceDiscoveryService {
    record Filters(String include, String exclude, boolean includeIgnored) {
        Filters {
            include = include == null ? "" : include; exclude = exclude == null ? "" : exclude;
            if (include.length() > 4096 || exclude.length() > 4096 || include.indexOf('\0') >= 0 || exclude.indexOf('\0') >= 0)
                throw WorkspaceApiException.invalidRequest("Path filters exceed their limits or contain NUL");
            try { new SearchFileFilter(include, exclude); }
            catch (IllegalArgumentException invalid) { throw WorkspaceApiException.invalidRequest("Invalid path filter"); }
        }
    }
    record Entry(Path path, long diskBytes, long modifiedMillis, WorkspaceDocument open) {
        Map<String, Object> metadata(Path root) {
            Map<String, Object> value = new LinkedHashMap<>();
            value.put("path", WorkspaceIgnoreRules.path(root.relativize(path))); value.put("uri", path.toUri().toString());
            value.put("diskBytes", diskBytes < 0 ? null : diskBytes);
            value.put("modifiedMillis", modifiedMillis < 0 ? null : modifiedMillis);
            value.put("open", open != null);
            if (open != null) {
                value.put("documentId", open.documentId()); value.put("version", open.version()); value.put("dirty", open.dirty());
                value.put("complete", open.complete());
            }
            return value;
        }
    }

    private final WorkspaceQueryService workspace;
    private final WorkspaceQueryBudget budget;
    private final Filters filters;
    private final SearchFileFilter filter;
    private final Map<Path, WorkspaceDocument> open = new LinkedHashMap<>();
    private final List<WorkspaceDocument> scratch = new ArrayList<>();
    private final WorkspaceIgnoreRules ignores;
    private final Map<String, Entry> entries = new TreeMap<>();

    WorkspaceDiscoveryService(WorkspaceQueryService workspace, List<WorkspaceDocument> documents, Filters filters, WorkspaceQueryBudget budget) {
        this.workspace = workspace; this.filters = filters; this.budget = budget;
        this.filter = new SearchFileFilter(filters.include(), filters.exclude());
        for (WorkspaceDocument document : documents) {
            budget.check();
            if (document.uri() == null) { scratch.add(document); continue; }
            Path path = WorkspacePaths.resolve(workspace.root(), document.uri());
            if (open.putIfAbsent(path, document) != null)
                throw new WorkspaceApiException(WorkspaceApiException.Code.CONFLICT, "Several editor documents have the same file identity");
        }
        this.ignores = new WorkspaceIgnoreRules(workspace, open, budget);
    }

    List<Entry> scan(boolean openOnly) {
        try {
            if (!openOnly) walk(workspace.root(), 0);
            for (var entry : open.entrySet()) {
                budget.check();
                Path file = entry.getKey();
                if (!allowed(file, false) || !filter.accepts(workspace.root().relativize(file), budget::cancelled)) continue;
                String path = WorkspaceIgnoreRules.path(workspace.root().relativize(file));
                Entry disk = entries.get(path);
                entries.put(path, new Entry(file, disk == null ? -1 : disk.diskBytes(), disk == null ? -1 : disk.modifiedMillis(), entry.getValue()));
            }
        } catch (WorkspaceQueryBudget.Stopped stopped) { /* Retain the bounded prefix with explicit incomplete reasons. */ }
        catch (java.util.concurrent.CancellationException cancelled) {
            throw new WorkspaceApiException(WorkspaceApiException.Code.CANCELLED, "Workspace discovery cancelled");
        } catch (IllegalArgumentException | StackOverflowError expensive) { budget.incomplete("PATTERN_LIMIT"); }
        return List.copyOf(entries.values());
    }

    List<WorkspaceDocument> scratchDocuments() {
        return filters.include().isEmpty() && filters.exclude().isEmpty() ? List.copyOf(scratch) : List.of();
    }

    private void walk(Path directory, int depth) {
        budget.check();
        if (depth > 64) { budget.incomplete("DEPTH_LIMIT"); return; }
        if (depth > 0 && !allowed(directory, true)) return;
        List<Path> children = new ArrayList<>();
        try {
            if (!WorkspacePaths.resolve(workspace.root(), directory.toString()).equals(directory)) {
                budget.incomplete("PATH_CHANGED"); return;
            }
            try (DirectoryStream<Path> stream = Files.newDirectoryStream(directory)) {
                for (Path child : stream) {
                    budget.visit(child.toString()); children.add(child);
                }
            }
        } catch (IOException | SecurityException failure) {
            budget.count("unreadableDirectories"); budget.incomplete("UNREADABLE_DIRECTORY"); return;
        } catch (WorkspaceApiException failure) {
            if (failure.code() == WorkspaceApiException.Code.CANCELLED) throw failure;
            budget.count("changedDirectories"); budget.incomplete("PATH_CHANGED"); return;
        }
        children.sort(Comparator.comparing(path -> path.getFileName().toString()));
        for (Path child : children) {
            budget.check();
            try {
                BasicFileAttributes attributes = Files.readAttributes(child, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
                if (attributes.isDirectory()) walk(child, depth + 1);
                else if (attributes.isRegularFile() && allowed(child, false) && filter.accepts(workspace.root().relativize(child), budget::cancelled)) {
                    String path = WorkspaceIgnoreRules.path(workspace.root().relativize(child));
                    entries.put(path, new Entry(child, attributes.size(), attributes.lastModifiedTime().toMillis(), open.get(child)));
                } else if (attributes.isSymbolicLink()) budget.count("skippedSymbolicLinks");
            } catch (IOException | SecurityException failure) {
                budget.count("unreadableEntries"); budget.incomplete("UNREADABLE_ENTRY");
            }
        }
    }

    private boolean allowed(Path path, boolean directory) {
        Path relative = workspace.root().relativize(path);
        if (relative.getNameCount() > 64) { budget.incomplete("DEPTH_LIMIT"); return false; }
        try {
            // Apply parent exclusions as well as leaf exclusions, including for open files under a pruned directory.
            for (int i = 1; i <= relative.getNameCount(); i++) workspace.policy().checkPath(relative.subpath(0, i), AgentPolicy.Capability.READ);
        } catch (WorkspaceApiException denied) {
            if (denied.code() != WorkspaceApiException.Code.PROTECTED_PATH && denied.code() != WorkspaceApiException.Code.EXCLUDED_PATH) throw denied;
            budget.count("policyExcludedEntries"); return false;
        }
        if (!filters.includeIgnored() && ignores.ignored(relative, directory)) { budget.count("ignoredEntries"); return false; }
        return true;
    }
}
