package shed;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** Uses Git's file index for quick-open candidates when the workspace is in a repository. */
final class GitProjectFileListing {
    private GitProjectFileListing() { }

    static List<String> list(Path root, int limit, ProjectFileScanner.Cancellation cancellation) {
        if (cancellation.isCancelled() || limit <= 0) return List.of();
        Process process;
        try {
            process = new ProcessBuilder("git", "-C", root.toString(), "ls-files", "--cached", "--others",
                "--exclude-standard", "-z").redirectErrorStream(true).start();
        } catch (IOException | SecurityException unavailable) {
            return null;
        }
        try (BufferedInputStream input = new BufferedInputStream(process.getInputStream())) {
            List<String> files = new ArrayList<>();
            ByteArrayOutputStream pathBytes = new ByteArrayOutputStream(128);
            int next;
            while ((next = input.read()) >= 0) {
                if (cancellation.isCancelled()) return List.of();
                if (next != 0) {
                    pathBytes.write(next);
                    continue;
                }
                String relative = pathBytes.toString(StandardCharsets.UTF_8);
                pathBytes.reset();
                Path file = root.resolve(relative).normalize();
                if (file.startsWith(root) && Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) files.add(relative);
                if (files.size() >= limit) {
                    files.sort(String::compareTo);
                    return files;
                }
            }
            if (process.waitFor() != 0) return null;
            files.sort(String::compareTo);
            return files;
        } catch (IOException | InterruptedException error) {
            if (error instanceof InterruptedException) Thread.currentThread().interrupt();
            return List.of();
        } finally {
            process.destroyForcibly();
        }
    }
}
