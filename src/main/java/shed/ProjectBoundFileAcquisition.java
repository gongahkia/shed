package shed;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.nio.file.SecureDirectoryStream;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributeView;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.BooleanSupplier;

/**
 * Internal-only, fail-closed, project-relative acquisition of existing regular files.
 *
 * <p>The caller must obtain a RootPin when the human authorizes a project. Secure
 * directory handles are used where available. Other providers use validated paths,
 * no-follow file opens and before/after identity checks for trusted local projects.
 * That portable path is NOT a sandbox against hostile concurrent path replacement.
 * Neither path promises a stable snapshot against concurrent in-place writes.
 */
final class ProjectBoundFileAcquisition {
    static final int MAX_BYTES = 25 * 1024 * 1024;

    /** Non-serializable local grant state, minted only after human project authorization. */
    record RootPin(Path path, Object fileKey) {
        RootPin {
            Objects.requireNonNull(path, "path");
            Objects.requireNonNull(fileKey, "fileKey");
            if (!path.isAbsolute() || !path.equals(path.normalize())) {
                throw new IllegalArgumentException("Expected a normalized absolute project path");
            }
        }
    }

    /** Owned, bounded bytes from one acquired resource, with conservative disk metadata. */
    record Snapshot(Path path, byte[] bytes, Object fileKey,
                    FileTime created, FileTime modified, long size) {
        Snapshot {
            Objects.requireNonNull(path, "path");
            bytes = bytes.clone();
            Objects.requireNonNull(fileKey, "fileKey");
            Objects.requireNonNull(created, "created");
            Objects.requireNonNull(modified, "modified");
            if (size != bytes.length) throw new IllegalArgumentException("Inconsistent acquired content size");
        }
        @Override public byte[] bytes() { return bytes.clone(); }
    }

    private ProjectBoundFileAcquisition() { }

    /** Call only at the existing explicit project authorization boundary. */
    static RootPin pinAuthorizedRoot(Path approvedRoot) throws IOException {
        Objects.requireNonNull(approvedRoot, "approvedRoot");
        Path path = approvedRoot.toAbsolutePath().normalize();
        if (!path.equals(path.toRealPath())) throw new IOException("Project root must be canonical");
        BasicFileAttributes attributes = Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (!attributes.isDirectory() || attributes.fileKey() == null) {
            throw new IOException("Project root does not have a supported stable identity");
        }
        // The identity will be independently checked against the opened directory handle.
        return new RootPin(path, attributes.fileKey());
    }

    static Snapshot acquire(RootPin root, String relativeName, int maximumBytes,
                            BooleanSupplier cancelled) throws IOException {
        Objects.requireNonNull(root, "root");
        Objects.requireNonNull(cancelled, "cancelled");
        if (maximumBytes < 1 || maximumBytes > MAX_BYTES) {
            throw new IllegalArgumentException("Invalid bounded file size");
        }
        Path relative = checkedRelativePath(relativeName);
        checkCancelled(cancelled);

        // Opening the root by name is safe only after we verify the opened HANDLE
        // matches the identity pinned when the user approved this project.
        DirectoryStream<Path> rootStream = Files.newDirectoryStream(root.path());
        List<SecureDirectoryStream<Path>> handles = new ArrayList<>();
        try {
            if (!(rootStream instanceof SecureDirectoryStream<?>)) {
                return acquirePortable(root, relativeName, maximumBytes, cancelled);
            }
            @SuppressWarnings("unchecked")
            SecureDirectoryStream<Path> rootHandle = (SecureDirectoryStream<Path>) rootStream;
            handles.add(rootHandle);
            BasicFileAttributes openedRoot = attributes(rootHandle);
            if (!openedRoot.isDirectory() || !Objects.equals(root.fileKey(), openedRoot.fileKey())) {
                throw new IOException("Authorized project directory has changed");
            }

            SecureDirectoryStream<Path> parent = rootHandle;
            for (int i = 0; i < relative.getNameCount() - 1; i++) {
                checkCancelled(cancelled);
                parent = parent.newDirectoryStream(relative.getName(i), LinkOption.NOFOLLOW_LINKS);
                handles.add(parent);
            }
            Path leaf = relative.getFileName();
            BasicFileAttributes before = attributes(parent, leaf);
            if (!before.isRegularFile() || before.fileKey() == null || before.size() > maximumBytes) {
                throw new IOException("File is unsupported or exceeds the bounded acquisition limit");
            }

            byte[] bytes;
            try (SeekableByteChannel channel = parent.newByteChannel(leaf,
                    Set.<OpenOption>of(StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS))) {
                bytes = readBounded(channel, maximumBytes, cancelled);
            }
            BasicFileAttributes after = attributes(parent, leaf);
            checkCancelled(cancelled);
            if (!after.isRegularFile() || !Objects.equals(before.fileKey(), after.fileKey())
                    || !Objects.equals(before.creationTime(), after.creationTime())
                    || !Objects.equals(before.lastModifiedTime(), after.lastModifiedTime())
                    || before.size() != after.size() || bytes.length != after.size()) {
                throw new IOException("Resource identity or contents changed during acquisition");
            }
            return new Snapshot(root.path().resolve(relative).normalize(), bytes, after.fileKey(),
                    after.creationTime(), after.lastModifiedTime(), after.size());
        } finally {
            // Close in reverse order. If the provider isn't secure, still close the root.
            if (handles.isEmpty()) rootStream.close();
            else for (int i = handles.size() - 1; i >= 0; i--) handles.get(i).close();
        }
    }

    /** Trusted-local contract: detects observed changes, not replace-and-restore races. */
    static Snapshot acquirePortable(RootPin root, String relativeName, int maximumBytes,
                                    BooleanSupplier cancelled) throws IOException {
        Objects.requireNonNull(root, "root");
        Objects.requireNonNull(cancelled, "cancelled");
        if (maximumBytes < 1 || maximumBytes > MAX_BYTES) throw new IllegalArgumentException("Invalid bounded file size");
        Path relative = checkedRelativePath(relativeName);
        checkCancelled(cancelled);
        Path target = root.path().resolve(relative);
        List<BasicFileAttributes> before = pathAttributes(root, relative);
        BasicFileAttributes file = before.getLast();
        if (file.size() > maximumBytes) throw new IOException("File exceeds bounded acquisition limit");
        byte[] bytes;
        try (SeekableByteChannel channel = Files.newByteChannel(target,
                Set.<OpenOption>of(StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS))) {
            bytes = readBounded(channel, maximumBytes, cancelled);
        }
        List<BasicFileAttributes> after = pathAttributes(root, relative);
        checkCancelled(cancelled);
        for (int i = 0; i < before.size(); i++) {
            if (!Objects.equals(before.get(i).fileKey(), after.get(i).fileKey())
                    || !before.get(i).creationTime().equals(after.get(i).creationTime()))
                throw new IOException("Project resource identity changed during acquisition");
        }
        BasicFileAttributes latest = after.getLast();
        if (file.size() != latest.size() || bytes.length != latest.size()
                || !file.lastModifiedTime().equals(latest.lastModifiedTime()))
            throw new IOException("Resource contents changed during acquisition");
        return new Snapshot(target, bytes, latest.fileKey(), latest.creationTime(), latest.lastModifiedTime(), latest.size());
    }

    private static List<BasicFileAttributes> pathAttributes(RootPin root, Path relative) throws IOException {
        Path current = root.path();
        List<BasicFileAttributes> attributes = new ArrayList<>();
        for (int i = 0; i <= relative.getNameCount(); i++) {
            if (i > 0) current = current.resolve(relative.getName(i - 1));
            BasicFileAttributes value = Files.readAttributes(current, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            boolean leaf = i == relative.getNameCount();
            if (value.fileKey() == null || (leaf ? !value.isRegularFile() : !value.isDirectory()))
                throw new IOException("Unsupported project resource or symbolic link");
            attributes.add(value);
        }
        if (!root.fileKey().equals(attributes.getFirst().fileKey()) || !current.equals(current.toRealPath()))
            throw new IOException("Authorized project path changed");
        return attributes;
    }

    private static byte[] readBounded(SeekableByteChannel channel, int maximumBytes,
                                      BooleanSupplier cancelled) throws IOException {
        ByteArrayOutputStream collected = new ByteArrayOutputStream();
        ByteBuffer chunk = ByteBuffer.allocate(8192);
        int idleReads = 0;
        while (true) {
            checkCancelled(cancelled);
            int count = channel.read(chunk);
            if (count < 0) break;
            if (count == 0) {
                if (++idleReads > 16) throw new IOException("Resource read made no progress");
                continue;
            }
            idleReads = 0;
            if ((long) collected.size() + count > maximumBytes)
                throw new IOException("Resource grew beyond bounded acquisition limit");
            collected.write(chunk.array(), 0, count);
            chunk.clear();
        }
        return collected.toByteArray();
    }

    private static BasicFileAttributes attributes(SecureDirectoryStream<Path> directory) throws IOException {
        BasicFileAttributeView view = directory.getFileAttributeView(BasicFileAttributeView.class);
        if (view == null) throw new IOException("Directory handle metadata unavailable");
        return view.readAttributes();
    }

    private static BasicFileAttributes attributes(SecureDirectoryStream<Path> directory, Path leaf) throws IOException {
        BasicFileAttributeView view = directory.getFileAttributeView(
                leaf, BasicFileAttributeView.class, LinkOption.NOFOLLOW_LINKS);
        if (view == null) throw new IOException("File metadata unavailable");
        return view.readAttributes();
    }

    /** Mirrors the existing LargeFileStore byte-level CR/LF line limit without another pathname read. */
    static boolean exceedsLineLimit(byte[] bytes, int maximumLines) {
        Objects.requireNonNull(bytes, "bytes");
        if (maximumLines < 1) throw new IllegalArgumentException("maximumLines must be positive");
        boolean previousCr = false;
        long breaks = 0;
        for (byte value : bytes) {
            if (value == '\r') { breaks++; previousCr = true; }
            else if (value == '\n') { if (!previousCr) breaks++; previousCr = false; }
            else previousCr = false;
            if (breaks + 1 > maximumLines) return true;
        }
        return false;
    }

    static Path checkedRelativePath(String name) throws IOException {
        if (name == null || name.isBlank() || name.length() > 8192 || name.indexOf('\0') >= 0
                || name.indexOf('\\') >= 0 || name.indexOf(':') >= 0) {
            throw new IOException("A bounded project-relative file name is required");
        }
        final Path candidate;
        try { candidate = Path.of(name); }
        catch (RuntimeException invalid) { throw new IOException("Invalid project-relative file name", invalid); }
        if (candidate.isAbsolute() || candidate.getNameCount() == 0) {
            throw new IOException("Absolute or empty path is not permitted");
        }
        for (Path part : candidate) {
            String segment = part.toString();
            if (segment.isEmpty() || segment.equals(".") || segment.equals("..")) {
                throw new IOException("Unsafe project-relative path component");
            }
        }
        return candidate;
    }

    private static void checkCancelled(BooleanSupplier cancelled) throws IOException {
        if (Thread.currentThread().isInterrupted() || cancelled.getAsBoolean()) {
            throw new IOException("Project file acquisition cancelled");
        }
    }
}
