package shed;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.GZIPOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TarGzExtractorTest {
    @TempDir Path tempDir;

    @Test
    void extractsRegularFilesUnderTheManagedDestination() throws Exception {
        Path archive = writeArchive("bin/jdtls", "launcher");
        Path destination = tempDir.resolve("runtime");

        TarGzExtractor.extract(archive, destination, () -> false);

        assertEquals("launcher", Files.readString(destination.resolve("bin/jdtls")));
    }

    @Test
    void rejectsPathTraversalBeforeWritingOutsideTheDestination() throws Exception {
        Path archive = writeArchive("../escape", "nope");

        assertThrows(IOException.class, () -> TarGzExtractor.extract(archive, tempDir.resolve("runtime"), () -> false));
        assertEquals(false, Files.exists(tempDir.resolve("escape")));
    }

    @Test
    void catalogsThePinnedOfficialJdtLanguageServerArchive() {
        ManagedLanguageDistributionCatalog.Distribution distribution =
            ManagedLanguageDistributionCatalog.forEntry(ManagedLanguageCatalog.java());

        assertEquals("java.eclipse-jdtls@1.60.0", distribution.artifact().coordinate().displayName());
        assertEquals("e94c303d8198f977930803582738771fd18c52c5492878410bf222b1aa81ef1d", distribution.artifact().sha256());
        assertEquals("bin/jdtls", distribution.launchPath(ManagedLanguageSupportTrust.Platform.MACOS));
    }

    private Path writeArchive(String name, String content) throws Exception {
        Path archive = tempDir.resolve("server.tar.gz");
        byte[] payload = content.getBytes(StandardCharsets.UTF_8);
        try (GZIPOutputStream output = new GZIPOutputStream(Files.newOutputStream(archive))) {
            byte[] header = new byte[512];
            byte[] path = name.getBytes(StandardCharsets.UTF_8);
            System.arraycopy(path, 0, header, 0, path.length);
            byte[] size = String.format("%011o", payload.length).getBytes(StandardCharsets.US_ASCII);
            System.arraycopy(size, 0, header, 124, size.length);
            header[156] = '0';
            output.write(header);
            output.write(payload);
            output.write(new byte[(512 - payload.length % 512) % 512]);
            output.write(new byte[1024]);
        }
        return archive;
    }
}
