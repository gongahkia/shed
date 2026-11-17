package shed;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

final class ProjectFileScanner {
    ScanResult scan(Path directory, Path root, int limit, Cancellation cancellation) {
        Path start = Objects.requireNonNull(directory, "directory").toAbsolutePath().normalize();
        Path workspaceRoot = Objects.requireNonNull(root, "root").toAbsolutePath().normalize();
        Cancellation effectiveCancellation = cancellation == null ? Cancellation.NONE : cancellation;
        int effectiveLimit = Math.max(0, limit);
        List<String> files = new ArrayList<>();
        if (effectiveLimit == 0 || !Files.isDirectory(start)) {
            return new ScanResult(List.of(), false, 0);
        }
        ArrayDeque<Path> directories = new ArrayDeque<>();
        directories.add(start);
        int scannedDirectories = 0;
        while (!directories.isEmpty() && files.size() < effectiveLimit) {
            if (effectiveCancellation.isCancelled()) {
                return new ScanResult(List.copyOf(files), true, scannedDirectories);
            }
            Path current = directories.removeFirst();
            List<Path> children = children(current);
            scannedDirectories++;
            for (Path child : children) {
                if (effectiveCancellation.isCancelled()) {
                    return new ScanResult(List.copyOf(files), true, scannedDirectories);
                }
                String name = child.getFileName().toString();
                if (Files.isSymbolicLink(child)) continue;
                boolean childDirectory = Files.isDirectory(child, LinkOption.NOFOLLOW_LINKS);
                if (excluded(name, childDirectory)) continue;
                if (Files.isRegularFile(child, LinkOption.NOFOLLOW_LINKS)) {
                    files.add(relativePath(workspaceRoot, child));
                    if (files.size() >= effectiveLimit) {
                        break;
                    }
                } else if (childDirectory) {
                    directories.addLast(child);
                }
            }
        }
        return new ScanResult(List.copyOf(files), false, scannedDirectories);
    }

    /** Streaming, non-following discovery for bounded Markdown consumers. Existing explorer scans stay unchanged. */
    MarkdownScan markdown(Path root, List<Path> nestedRoots, Cancellation cancellation) {
        var files = new ArrayList<Path>();
        int[] visited = {0}, failures = {0}; boolean[] limited = {false};
        try {
            Files.walkFileTree(root, java.util.Set.of(), 64, new java.nio.file.SimpleFileVisitor<>() {
                private java.nio.file.FileVisitResult admission() {
                    if (cancellation.isCancelled()) return java.nio.file.FileVisitResult.TERMINATE;
                    if (++visited[0] > 32768) { limited[0] = true; return java.nio.file.FileVisitResult.TERMINATE; }
                    return java.nio.file.FileVisitResult.CONTINUE;
                }
                @Override public java.nio.file.FileVisitResult preVisitDirectory(Path dir, java.nio.file.attribute.BasicFileAttributes attrs) {
                    var decision = admission(); if (decision != java.nio.file.FileVisitResult.CONTINUE) return decision;
                    if (!dir.equals(root) && (excluded(dir.getFileName().toString(), true) || nestedRoots.contains(dir)))
                        return java.nio.file.FileVisitResult.SKIP_SUBTREE;
                    return decision;
                }
                @Override public java.nio.file.FileVisitResult visitFile(Path file, java.nio.file.attribute.BasicFileAttributes attrs) {
                    var decision = admission(); if (decision != java.nio.file.FileVisitResult.CONTINUE) return decision;
                    if (attrs.isDirectory()) { limited[0] = true; return decision; } // depth cap
                    if (attrs.isRegularFile() && !excluded(file.getFileName().toString(), false) && markdown(file)) {
                        if (files.size() == 1024) { limited[0] = true; return java.nio.file.FileVisitResult.TERMINATE; }
                        files.add(file);
                    }
                    return decision;
                }
                @Override public java.nio.file.FileVisitResult visitFileFailed(Path file, IOException error) {
                    failures[0]++; return admission();
                }
            });
        } catch (IOException | SecurityException error) { failures[0]++; }
        files.sort(Comparator.comparing(Path::toString));
        return new MarkdownScan(List.copyOf(files), limited[0], failures[0], cancellation.isCancelled(), visited[0]);
    }

    static boolean markdown(Path file) {
        String name = file.getFileName().toString().toLowerCase(java.util.Locale.ROOT);
        return name.endsWith(".md") || name.endsWith(".markdown");
    }

    record MarkdownScan(List<Path> files, boolean limited, int failures, boolean cancelled, int visited) {
        boolean complete() { return !limited && failures == 0 && !cancelled; }
    }

    private static List<Path> children(Path directory) {
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(directory)) {
            List<Path> children = new ArrayList<>();
            for (Path child : stream) {
                children.add(child);
            }
            children.sort(Comparator.comparing(path -> path.getFileName().toString()));
            return children;
        } catch (IOException | SecurityException error) {
            return List.of();
        }
    }

    private static boolean excluded(String name, boolean directory) {
        return WorkspaceInternalPaths.recoveryArtifactName(name) || name.equals(".DS_Store") || directory && (name.equals(".git") || name.equals(".hg")
            || name.equals(".svn") || name.equals(".venv") || name.equals(".gradle") || name.equals(".idea")
            || name.equals("node_modules") || name.equals("target") || name.equals("build")
            || name.equals("__pycache__"));
    }

    private static String relativePath(Path root, Path child) {
        Path relative = root.relativize(child.toAbsolutePath().normalize());
        return relative.toString();
    }

    interface Cancellation {
        Cancellation NONE = () -> false;

        boolean isCancelled();
    }

    static final class CancellationSource implements Cancellation {
        private final AtomicBoolean cancelled = new AtomicBoolean();

        void cancel() {
            cancelled.set(true);
        }

        @Override
        public boolean isCancelled() {
            return cancelled.get();
        }
    }

    record ScanResult(List<String> files, boolean cancelled, int scannedDirectories) {
    }
}
