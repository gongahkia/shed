package shed;

import java.io.IOException;
import java.io.InputStream;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Objects;

final class AtomicFileWriter {
    private AtomicFileWriter() {
    }

    static void write(Path target, byte[] expected) throws IOException {
        write(target, expected, AtomicFileWriter::verify);
    }

    static void write(Path target, byte[] expected, Verifier verifier) throws IOException {
        Path resolvedTarget = Objects.requireNonNull(target, "target").toAbsolutePath().normalize();
        byte[] content = expected == null ? new byte[0] : expected;
        Path parent = resolvedTarget.getParent();
        if (parent == null || !Files.isDirectory(parent)) {
            throw failure(resolvedTarget, "parent directory is unavailable", null);
        }
        if (Files.exists(resolvedTarget) && !Files.isRegularFile(resolvedTarget)) {
            throw failure(resolvedTarget, "target is not a regular file", null);
        }

        Path temporary = null;
        Path originalBackup = null;
        boolean moved = false;
        boolean retainBackup = false;
        try {
            if (Files.isRegularFile(resolvedTarget)) {
                originalBackup = Files.createTempFile(parent, ".shed-original-", ".tmp");
                Files.copy(resolvedTarget, originalBackup, StandardCopyOption.REPLACE_EXISTING);
                force(originalBackup);
            }
            temporary = Files.createTempFile(parent, ".shed-write-", ".tmp");
            Files.write(temporary, content, StandardOpenOption.TRUNCATE_EXISTING);
            force(temporary);
            Files.move(temporary, resolvedTarget, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            temporary = null;
            moved = true;
            Objects.requireNonNull(verifier, "verifier").verify(resolvedTarget, content);
        } catch (IOException error) {
            String recovery = "target was not replaced";
            if (moved) {
                try {
                    if (originalBackup != null) {
                        Files.move(originalBackup, resolvedTarget, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                        originalBackup = null;
                        recovery = "original source was restored";
                    } else {
                        Files.deleteIfExists(resolvedTarget);
                        recovery = "new target was removed";
                    }
                } catch (IOException restoreError) {
                    error.addSuppressed(restoreError);
                    if (originalBackup != null) {
                        retainBackup = true;
                        recovery = "original source is retained at " + originalBackup;
                    } else {
                        recovery = "target state could not be restored";
                    }
                }
            }
            throw failure(resolvedTarget, recovery, error);
        } finally {
            deleteIfPresent(temporary);
            if (!retainBackup) {
                deleteIfPresent(originalBackup);
            }
        }
    }

    private static void verify(Path target, byte[] expected) throws IOException {
        if (!Files.isRegularFile(target)) {
            throw new IOException("target is not a regular file after atomic move");
        }
        if (Files.size(target) != expected.length) {
            throw new IOException("target size does not match saved content");
        }
        if (!MessageDigest.isEqual(digest(expected), digest(target))) {
            throw new IOException("target content digest does not match saved content");
        }
    }

    private static void force(Path path) throws IOException {
        try (FileChannel channel = FileChannel.open(path, StandardOpenOption.WRITE)) {
            channel.force(true);
        }
    }

    private static byte[] digest(byte[] content) throws IOException {
        return digest(new java.io.ByteArrayInputStream(content));
    }

    private static byte[] digest(Path path) throws IOException {
        try (InputStream input = Files.newInputStream(path)) {
            return digest(input);
        }
    }

    private static byte[] digest(InputStream input) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[8192];
            int read;
            while ((read = input.read(buffer)) != -1) {
                digest.update(buffer, 0, read);
            }
            return digest.digest();
        } catch (NoSuchAlgorithmException error) {
            throw new IOException("SHA-256 is unavailable", error);
        }
    }

    private static IOException failure(Path target, String outcome, IOException cause) {
        String message = "Save failed for " + target + ": " + outcome + ". Check disk space and permissions, then retry.";
        return cause == null ? new IOException(message) : new IOException(message, cause);
    }

    private static void deleteIfPresent(Path path) {
        if (path == null) {
            return;
        }
        try {
            Files.deleteIfExists(path);
        } catch (IOException ignored) {
        }
    }

    interface Verifier {
        void verify(Path target, byte[] expected) throws IOException;
    }
}
