package shed;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.AclEntry;
import java.nio.file.attribute.AclEntryFlag;
import java.nio.file.attribute.AclEntryPermission;
import java.nio.file.attribute.AclEntryType;
import java.nio.file.attribute.AclFileAttributeView;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileAttribute;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.nio.file.attribute.UserPrincipal;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/** Owner-only local credentials/state. Unsupported permission models fail closed. */
final class PrivateWorkspaceFiles {
    private PrivateWorkspaceFiles() { }

    static Path directory(Path requested) throws IOException {
        Path path = requested.toAbsolutePath().normalize();
        Files.createDirectories(path, attributes(path, true));
        check(path, true);
        return path.toRealPath();
    }

    static Path existingDirectory(Path requested) throws IOException {
        Path path = requested.toAbsolutePath().normalize(); check(path, true); return path.toRealPath();
    }

    static Path create(Path directory, String name, byte[] content) throws IOException {
        if (name == null || !name.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,127}")) throw new IOException("Invalid private state name");
        check(directory, true);
        Path path = directory.resolve(name);
        Files.createFile(path, attributes(path, false));
        try (FileChannel channel = FileChannel.open(path, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)) {
            ByteBuffer bytes = ByteBuffer.wrap(content);
            while (bytes.hasRemaining()) channel.write(bytes);
            channel.force(true);
        } catch (IOException | RuntimeException failure) {
            Files.deleteIfExists(path);
            throw failure;
        }
        return path;
    }

    static byte[] read(Path requested, int limit) throws IOException {
        Path path = requested.toAbsolutePath().normalize();
        check(path.getParent(), true);
        check(path, false);
        BasicFileAttributes before = Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (before.size() > limit) throw new IOException("Private state exceeds its size limit");
        ByteArrayOutputStream result = new ByteArrayOutputStream();
        try (FileChannel channel = FileChannel.open(path, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)) {
            ByteBuffer bytes = ByteBuffer.allocate(Math.min(8192, limit + 1));
            while (channel.read(bytes) != -1) {
                bytes.flip();
                if ((long) result.size() + bytes.remaining() > limit) throw new IOException("Private state exceeds its size limit");
                result.write(bytes.array(), 0, bytes.remaining()); bytes.clear();
            }
        }
        BasicFileAttributes after = Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (!after.isRegularFile() || !java.util.Objects.equals(before.fileKey(), after.fileKey())
            || before.size() != after.size() || !before.lastModifiedTime().equals(after.lastModifiedTime()))
            throw new IOException("Private state changed while being read");
        return result.toByteArray();
    }

    static void deleteIfMatching(Path path, byte[] expected) throws IOException {
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) return;
        if (!java.security.MessageDigest.isEqual(read(path, expected.length), expected))
            throw new IOException("Private state was replaced; it has been retained");
        Files.delete(path);
    }

    private static void check(Path path, boolean directory) throws IOException {
        if (path == null) throw new IOException("Private state directory is missing");
        BasicFileAttributes info = Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (directory ? !info.isDirectory() : !info.isRegularFile()) throw new IOException("Private state must not be a link or special file");
        UserPrincipal owner = currentUser(path);
        if (!owner.equals(Files.getOwner(path, LinkOption.NOFOLLOW_LINKS))) throw new IOException("Private state belongs to another user");
        if (path.getFileSystem().supportedFileAttributeViews().contains("posix")) {
            Set<PosixFilePermission> permissions = Files.getPosixFilePermissions(path, LinkOption.NOFOLLOW_LINKS);
            Set<PosixFilePermission> allowed = permissions(directory);
            if (!allowed.containsAll(permissions) || !permissions.contains(PosixFilePermission.OWNER_READ)
                || directory && !permissions.contains(PosixFilePermission.OWNER_EXECUTE))
                throw new IOException("Private state must be accessible only to its owner");
        } else {
            AclFileAttributeView acl = Files.getFileAttributeView(path, AclFileAttributeView.class, LinkOption.NOFOLLOW_LINKS);
            if (acl == null) throw new IOException("Owner-only private state is unsupported on this filesystem");
            for (AclEntry entry : acl.getAcl())
                if (entry.type() == AclEntryType.ALLOW && !entry.principal().equals(owner) && !entry.permissions().isEmpty())
                    throw new IOException("Private state grants access to another principal");
        }
    }

    private static UserPrincipal currentUser(Path path) throws IOException {
        return path.getFileSystem().getUserPrincipalLookupService().lookupPrincipalByName(System.getProperty("user.name"));
    }

    private static FileAttribute<?>[] attributes(Path path, boolean directory) throws IOException {
        if (path.getFileSystem().supportedFileAttributeViews().contains("posix"))
            return new FileAttribute<?>[]{PosixFilePermissions.asFileAttribute(permissions(directory))};
        if (!path.getFileSystem().supportedFileAttributeViews().contains("acl"))
            throw new IOException("Owner-only private state is unsupported on this filesystem");
        var builder = AclEntry.newBuilder().setType(AclEntryType.ALLOW).setPrincipal(currentUser(path))
            .setPermissions(EnumSet.allOf(AclEntryPermission.class));
        if (directory) builder.setFlags(AclEntryFlag.FILE_INHERIT, AclEntryFlag.DIRECTORY_INHERIT);
        List<AclEntry> entries = List.of(builder.build());
        return new FileAttribute<?>[]{new FileAttribute<List<AclEntry>>() {
            public String name() { return "acl:acl"; }
            public List<AclEntry> value() { return entries; }
        }};
    }

    private static Set<PosixFilePermission> permissions(boolean directory) {
        return directory ? PosixFilePermissions.fromString("rwx------") : PosixFilePermissions.fromString("rw-------");
    }
}
