package shed;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.Comparator;
import java.util.stream.Stream;

public class TreeService {
    public File resolveRoot(String pathArgument) {
        String target = pathArgument == null ? "" : pathArgument.trim();
        return target.isEmpty() ? new File(".") : new File(target);
    }

    public String titleSuffix(File root) {
        String name = root.getName();
        if (name == null || name.isEmpty()) {
            return root.getAbsolutePath();
        }
        return name;
    }

    public File resolveActionPath(String argument, File treeRoot) {
        String trimmed = argument == null ? "" : argument.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        File candidate = new File(trimmed);
        if (candidate.isAbsolute()) {
            return candidate;
        }
        if (treeRoot != null) {
            return new File(treeRoot, trimmed);
        }
        return candidate;
    }

    public File revealRootForPath(File path) {
        if (path == null) {
            return null;
        }
        if (path.isDirectory()) {
            return path;
        }
        File parent = path.getParentFile();
        return parent == null ? path : parent;
    }

    public void createFile(File file) throws IOException {
        if (file == null) {
            throw new IOException("path required");
        }
        File parent = file.getParentFile();
        if (parent != null) {
            Files.createDirectories(parent.toPath());
        }
        if (!file.exists()) {
            Files.createFile(file.toPath());
        }
    }

    public void createDirectory(File directory) throws IOException {
        if (directory == null) {
            throw new IOException("path required");
        }
        Files.createDirectories(directory.toPath());
    }

    public void rename(File source, File target) throws IOException {
        if (source == null || target == null) {
            throw new IOException("source/target required");
        }
        File parent = target.getParentFile();
        if (parent != null) {
            Files.createDirectories(parent.toPath());
        }
        Files.move(source.toPath(), target.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
    }

    public int deleteRecursively(File target) throws IOException {
        if (target == null || !target.exists()) {
            return 0;
        }
        try (Stream<java.nio.file.Path> stream = Files.walk(target.toPath())) {
            return stream
                .sorted(Comparator.reverseOrder())
                .mapToInt(path -> {
                    try {
                        Files.deleteIfExists(path);
                        return 1;
                    } catch (IOException e) {
                        return 0;
                    }
                })
                .sum();
        }
    }
}
