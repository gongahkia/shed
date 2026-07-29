package shed;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
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
                if (excluded(name)) {
                    continue;
                }
                if (Files.isRegularFile(child)) {
                    files.add(relativePath(workspaceRoot, child));
                    if (files.size() >= effectiveLimit) {
                        break;
                    }
                } else if (Files.isDirectory(child)) {
                    directories.addLast(child);
                }
            }
        }
        return new ScanResult(List.copyOf(files), false, scannedDirectories);
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

    private static boolean excluded(String name) {
        return name.startsWith(".") || name.equals("node_modules") || name.equals("target") || name.equals("build")
            || name.equals("__pycache__") || name.equals(".git");
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
