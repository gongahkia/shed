package shed;

import static org.junit.jupiter.api.Assertions.*;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PrivateWorkspaceFilesTest {
    @TempDir Path root;

    @Test void privateStateIsBoundedAndCleanupNeverDeletesReplacedContent() throws Exception {
        Path directory = PrivateWorkspaceFiles.directory(root.resolve("private"));
        byte[] data = "secret".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        Path file = PrivateWorkspaceFiles.create(directory, "connection.json", data);
        assertArrayEquals(data, PrivateWorkspaceFiles.read(file, data.length));
        assertThrows(IOException.class, () -> PrivateWorkspaceFiles.read(file, 2));
        Files.writeString(file, "changed");
        assertThrows(IOException.class, () -> PrivateWorkspaceFiles.deleteIfMatching(file, data));
        assertEquals("changed", Files.readString(file));
        assertThrows(IOException.class, () -> PrivateWorkspaceFiles.create(directory, "../escape", data));
    }

    @Test void sharedPermissionsAndSymlinksAreRejected() throws Exception {
        Path directory = PrivateWorkspaceFiles.directory(root.resolve("private"));
        Path file = PrivateWorkspaceFiles.create(directory, "state", new byte[]{1});
        if (directory.getFileSystem().supportedFileAttributeViews().contains("posix")) {
            Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-r--r--"));
            assertThrows(IOException.class, () -> PrivateWorkspaceFiles.read(file, 100));
            Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-------"));
            Files.setPosixFilePermissions(directory, PosixFilePermissions.fromString("rwxr-xr-x"));
            assertThrows(IOException.class, () -> PrivateWorkspaceFiles.read(file, 100));
            Files.setPosixFilePermissions(directory, PosixFilePermissions.fromString("rwx------"));
        }
        try { Files.createSymbolicLink(directory.resolve("alias"), file); }
        catch (UnsupportedOperationException | IOException unavailable) { return; }
        assertThrows(IOException.class, () -> PrivateWorkspaceFiles.read(directory.resolve("alias"), 100));
        PrivateWorkspaceFiles.deleteIfMatching(file, new byte[]{1});
        assertFalse(Files.exists(file));
    }
}
