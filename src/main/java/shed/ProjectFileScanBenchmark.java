package shed;

import java.io.File;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

final class ProjectFileScanBenchmark {
    private final ProjectFileScanner scanner;

    ProjectFileScanBenchmark(ProjectFileScanner scanner) {
        this.scanner = Objects.requireNonNull(scanner, "scanner");
    }

    Report measure(Path root, int limit) {
        Path normalizedRoot = Objects.requireNonNull(root, "root").toAbsolutePath().normalize();
        long legacyStartedAt = System.nanoTime();
        List<String> legacy = legacyScan(normalizedRoot.toFile(), normalizedRoot.toString(), limit);
        long legacyDuration = System.nanoTime() - legacyStartedAt;
        long optimizedStartedAt = System.nanoTime();
        ProjectFileScanner.ScanResult optimized = scanner.scan(normalizedRoot, normalizedRoot, limit, ProjectFileScanner.Cancellation.NONE);
        long optimizedDuration = System.nanoTime() - optimizedStartedAt;
        List<String> legacySorted = new ArrayList<>(legacy);
        Collections.sort(legacySorted);
        List<String> optimizedSorted = new ArrayList<>(optimized.files());
        Collections.sort(optimizedSorted);
        return new Report(normalizedRoot.toString(), limit, legacyDuration, optimizedDuration, legacy.size(), optimized.files().size(),
            legacySorted.equals(optimizedSorted), optimized.scannedDirectories());
    }

    private static List<String> legacyScan(File root, String rootPath, int limit) {
        List<String> files = new ArrayList<>();
        collect(root, rootPath, files, Math.max(0, limit));
        return files;
    }

    private static void collect(File directory, String rootPath, List<String> result, int limit) {
        if (result.size() >= limit || directory == null || !directory.isDirectory()) {
            return;
        }
        File[] children = directory.listFiles();
        if (children == null) {
            return;
        }
        for (File child : children) {
            if (result.size() >= limit) {
                return;
            }
            String name = child.getName();
            if (name.startsWith(".") || name.equals("node_modules") || name.equals("target") || name.equals("build")
                || name.equals("__pycache__") || name.equals(".git")) {
                continue;
            }
            if (child.isFile()) {
                String relative = child.getAbsolutePath().substring(rootPath.length());
                result.add(relative.startsWith(File.separator) ? relative.substring(1) : relative);
            } else if (child.isDirectory()) {
                collect(child, rootPath, result, limit);
            }
        }
    }

    record Report(String root, int limit, long legacyDurationNanos, long optimizedDurationNanos, int legacyFiles,
                  int optimizedFiles, boolean equivalentFileSet, int optimizedDirectories) {
        String format() {
            return "root=" + root + "\nlimit=" + limit + "\nlegacyDurationNanos=" + legacyDurationNanos
                + "\noptimizedDurationNanos=" + optimizedDurationNanos + "\nlegacyFiles=" + legacyFiles
                + "\noptimizedFiles=" + optimizedFiles + "\nequivalentFileSet=" + equivalentFileSet
                + "\noptimizedDirectories=" + optimizedDirectories + "\n";
        }
    }
}
