package shed;

import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.eclipse.jgit.ignore.FastIgnoreRule;

/** Workspace-local .gitignore rules only. Never opens a Git repository or reads global Git configuration. */
final class WorkspaceIgnoreRules {
    private static final int MAX_FILE_BYTES = 256 * 1024;
    private static final int MAX_RULES = 16_384;
    private final WorkspaceQueryService workspace;
    private final Map<Path, WorkspaceDocument> open;
    private final WorkspaceQueryBudget budget;
    private final Map<Path, Rules> cache = new HashMap<>();
    private int ruleCount;
    private record Rules(List<FastIgnoreRule> values, boolean available) { }

    WorkspaceIgnoreRules(WorkspaceQueryService workspace, Map<Path, WorkspaceDocument> open, WorkspaceQueryBudget budget) {
        this.workspace = workspace; this.open = open; this.budget = budget;
    }

    boolean ignored(Path relative, boolean directory) {
        // Test each ancestor separately: a negation cannot re-include a descendant of an ignored directory.
        for (int end = 1; end <= relative.getNameCount(); end++) {
            budget.check();
            Path candidate = relative.subpath(0, end);
            boolean isDirectory = end < relative.getNameCount() || directory;
            Boolean ignored = null;
            for (int depth = 0; depth < end; depth++) {
                Path parent = depth == 0 ? workspace.root() : workspace.root().resolve(relative.subpath(0, depth));
                Rules rules = cache.computeIfAbsent(parent, this::read);
                if (!rules.available()) return true;
                String local = path(parent.relativize(workspace.root().resolve(candidate)));
                for (int i = rules.values().size() - 1; i >= 0; i--) {
                    if ((i & 31) == 0) budget.check();
                    FastIgnoreRule rule = rules.values().get(i);
                    if (rule.isMatch(local, isDirectory, true)) { ignored = rule.getResult(); break; }
                }
            }
            if (Boolean.TRUE.equals(ignored)) return true;
        }
        return false;
    }

    private Rules read(Path directory) {
        Path file = directory.resolve(".gitignore");
        WorkspaceDocument document = open.get(file);
        if (document == null && !Files.exists(file, LinkOption.NOFOLLOW_LINKS)) return new Rules(List.of(), true);
        try {
            budget.check();
            workspace.policy().checkPath(workspace.root().relativize(file), AgentPolicy.Capability.READ);
            if (!WorkspacePaths.resolve(workspace.root(), file.toString()).equals(file)) throw WorkspaceApiException.invalidRequest("Ignore file alias is unavailable");
            if (document == null) document = WorkspaceTextFile.capture(workspace.workspaceId(), file, false, MAX_FILE_BYTES, budget::cancelled).document();
            if (!document.complete() || document.content().length() > MAX_FILE_BYTES)
                throw new WorkspaceApiException(WorkspaceApiException.Code.LIMIT_EXCEEDED, "Ignore file exceeds its snapshot limit");
            budget.text(document.content().length());
            List<FastIgnoreRule> rules = new ArrayList<>();
            int start = 0;
            while (start < document.content().length()) {
                budget.check();
                int end = document.content().indexOf('\n', start);
                if (end < 0) end = document.content().length();
                if (end - start > 4096 || ++ruleCount > MAX_RULES)
                    throw new WorkspaceApiException(WorkspaceApiException.Code.LIMIT_EXCEEDED, "Ignore rules exceed their limits");
                FastIgnoreRule rule = new FastIgnoreRule(document.content().substring(start, end));
                if (!rule.isEmpty()) rules.add(rule);
                start = end + 1;
            }
            budget.count("ignoreFiles");
            return new Rules(List.copyOf(rules), true);
        } catch (WorkspaceApiException failure) {
            if (failure.code() == WorkspaceApiException.Code.CANCELLED) throw failure;
            budget.incomplete("IGNORE_UNAVAILABLE"); budget.count("unavailableIgnoreFiles");
            return new Rules(List.of(), false);
        }
    }

    static String path(Path relative) {
        // A backslash is a legal filename character on POSIX; only real separators are translated.
        return relative.toString().replace(relative.getFileSystem().getSeparator(), "/");
    }
}
