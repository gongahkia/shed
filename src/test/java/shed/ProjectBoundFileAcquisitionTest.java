package shed;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SecureDirectoryStream;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class ProjectBoundFileAcquisitionTest {
    @TempDir Path fixture;

    @Test void readsThroughBoundedAcquiredResourceAndPreservesBytes() throws Exception {
        Path root = Files.createDirectory(fixture.resolve("project"));
        requireSecureProvider(root);
        Files.createDirectories(root.resolve("src"));
        byte[] expected = "class Example { String s = \"é\"; }\r\n".getBytes(StandardCharsets.UTF_8);
        Files.write(root.resolve("src/Example.java"), expected);
        var authorized = ProjectBoundFileAcquisition.pinAuthorizedRoot(root);
        var acquired = ProjectBoundFileAcquisition.acquire(authorized, "src/Example.java", 1024, () -> false);
        assertArrayEquals(expected, acquired.bytes());
        assertEquals(root.resolve("src/Example.java"), acquired.path());
        byte[] altered = acquired.bytes(); altered[0] = 0;
        assertArrayEquals(expected, acquired.bytes());
    }

    @Test void rejectsPathsOutsideApprovedProjectAndUnsupportedTargets() throws Exception {
        Path root = Files.createDirectory(fixture.resolve("project"));
        requireSecureProvider(root);
        Files.writeString(root.resolve("valid.txt"), "synthetic");
        var authorized = ProjectBoundFileAcquisition.pinAuthorizedRoot(root);
        for (String path : new String[]{"../sibling", "/tmp/absolute", "not-found", "./valid.txt"}) {
            assertThrows(IOException.class, () -> ProjectBoundFileAcquisition.acquire(authorized, path, 1024, () -> false));
        }
        assertThrows(IOException.class, () -> ProjectBoundFileAcquisition.acquire(authorized, "valid.txt", 2, () -> false));
        assertThrows(IOException.class, () -> ProjectBoundFileAcquisition.acquire(authorized, "valid.txt", 1024, () -> true));
    }

    @Test void rejectsLinksForAncestorAndFinalFile() throws Exception {
        Path root = Files.createDirectory(fixture.resolve("project"));
        Path sibling = Files.createDirectory(fixture.resolve("synthetic-sibling"));
        requireSecureProvider(root);
        Files.writeString(sibling.resolve("data.txt"), "synthetic");
        try {
            Files.createSymbolicLink(root.resolve("linked"), sibling);
            Files.createSymbolicLink(root.resolve("file.txt"), sibling.resolve("data.txt"));
        } catch (UnsupportedOperationException | IOException unsupported) {
            assumeTrue(false, "symlink creation unavailable in test provider");
        }
        var authorized = ProjectBoundFileAcquisition.pinAuthorizedRoot(root);
        assertThrows(IOException.class, () -> ProjectBoundFileAcquisition.acquire(authorized, "linked/data.txt", 1024, () -> false));
        assertThrows(IOException.class, () -> ProjectBoundFileAcquisition.acquire(authorized, "file.txt", 1024, () -> false));
    }

    @Test void refusesReplacementOfPreviouslyApprovedProjectRoot() throws Exception {
        Path root = Files.createDirectory(fixture.resolve("project"));
        requireSecureProvider(root);
        var authorized = ProjectBoundFileAcquisition.pinAuthorizedRoot(root);
        Files.move(root, fixture.resolve("old-project"));
        Files.createDirectory(root);
        Files.writeString(root.resolve("data.txt"), "different contents");
        assertThrows(IOException.class, () -> ProjectBoundFileAcquisition.acquire(authorized, "data.txt", 1024, () -> false));
    }

    @Test void preparesExistingCanonicalBufferWithoutInstallingIt() throws Exception {
        Path root = Files.createDirectory(fixture.resolve("project"));
        requireSecureProvider(root);
        Files.createDirectories(root.resolve("src"));
        Files.writeString(root.resolve("src/Example.java"), "class Example {}\n");
        var store = new LegacyDocumentStore(null);
        FileBuffer prepared = store.loadProjectFile(
                ProjectBoundFileAcquisition.pinAuthorizedRoot(root), "src/Example.java", () -> false);
        assertEquals("class Example {}\n", prepared.getContent());
        assertFalse(prepared.isModified());
        assertEquals(root.resolve("src/Example.java").toString(), prepared.getFilePath());
        assertTrue(store.legacyBuffers().isEmpty(), "acquisition must not install a document");
    }

    @Test void observesByteLevelLineLimitsWithoutAnotherRead() {
        assertFalse(ProjectBoundFileAcquisition.exceedsLineLimit(new byte[0], 1));
        assertFalse(ProjectBoundFileAcquisition.exceedsLineLimit("one\r\ntwo".getBytes(StandardCharsets.UTF_8), 2));
        assertTrue(ProjectBoundFileAcquisition.exceedsLineLimit("one\ntwo\nthree".getBytes(StandardCharsets.UTF_8), 2));
    }

    private static void requireSecureProvider(Path root) throws IOException {
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(root)) {
            assumeTrue(stream instanceof SecureDirectoryStream<?>, "provider lacks SecureDirectoryStream");
        }
    }
}
