package shed;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

public class LargeFileStoreTest {
    @TempDir
    Path tempDir;

    @Test
    void opensUtf8AcrossPageBoundaryWithoutMaterializingTheSource() throws Exception {
        Path file = tempDir.resolve("boundary.txt");
        byte[] prefix = "a".repeat(LargeFileStore.PAGE_BYTES + 3).getBytes(StandardCharsets.UTF_8);
        byte[] suffix = "é\nnext".getBytes(StandardCharsets.UTF_8);
        byte[] content = new byte[prefix.length + suffix.length];
        System.arraycopy(prefix, 0, content, 0, prefix.length);
        System.arraycopy(suffix, 0, content, prefix.length, suffix.length);
        Files.write(file, content);

        LargeFileStore.OpenResult result = LargeFileStore.open(file, 10);

        assertTrue(result.opened());
        assertEquals(content.length, result.store().byteSize());
        assertEquals(2, result.store().lineCount());
        assertTrue(result.store().preview().endsWith("é\nnext"));
        assertFalse(result.store().previewTruncated());
        assertTrue(result.store().preview().length() < LargeFileStore.MAX_PREVIEW_CHARS);
    }

    @Test
    void capsPreviewMemoryForAnUnbrokenLine() throws Exception {
        Path file = tempDir.resolve("wide.txt");
        Files.writeString(file, "x".repeat(LargeFileStore.MAX_PREVIEW_CHARS + 1), StandardCharsets.UTF_8);

        LargeFileStore.OpenResult result = LargeFileStore.open(file, 1);

        assertTrue(result.opened());
        assertEquals(LargeFileStore.MAX_PREVIEW_CHARS, result.store().preview().length());
        assertTrue(result.store().previewTruncated());
    }

    @Test
    void countsCrLfAsOneLogicalLineAndReportsMalformedUtf8() throws Exception {
        Path lines = tempDir.resolve("lines.txt");
        Files.writeString(lines, "one\r\ntwo\nthree\r", StandardCharsets.UTF_8);
        assertTrue(LargeFileStore.exceedsLineLimit(lines, 3));
        assertFalse(LargeFileStore.exceedsLineLimit(lines, 4));

        Path malformed = tempDir.resolve("malformed.txt");
        Files.write(malformed, new byte[] {(byte) 0xC3, 0x28});
        LargeFileStore.OpenResult result = LargeFileStore.open(malformed, 1);
        assertFalse(result.opened());
        assertTrue(result.error().contains("well-formed UTF-8"));
    }
}
