package shed;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DocumentFormatPreservationTest {
    @TempDir Path root;

    @Test void markedUnicodeFilesKeepBomEncodingAndNewlinesThroughHumanAndSnapshotSaves() throws Exception {
        for (Charset charset : List.of(StandardCharsets.UTF_8, StandardCharsets.UTF_16LE, StandardCharsets.UTF_16BE)) {
            Path file = root.resolve(charset.name() + ".txt");
            Files.write(file, marked("before\r\n", charset));
            FileBuffer buffer = new FileBuffer(file.toFile());
            assertEquals("before\n", buffer.getContent());
            assertTrue(buffer.hasByteOrderMark()); assertEquals("CRLF", buffer.getLineEndingLabel());
            buffer.replaceContentUndoably("after\n👩‍💻\n");
            buffer.save();
            assertArrayEquals(marked("after\r\n👩‍💻\r\n", charset), Files.readAllBytes(file));
            buffer.replaceContentUndoably("snapshot\n");
            var snapshot = buffer.captureSaveSnapshot(null);
            buffer.writeSaveSnapshot(snapshot); buffer.completeSaveSnapshot(snapshot);
            assertFalse(buffer.isModified());
            assertArrayEquals(marked("snapshot\r\n", charset), Files.readAllBytes(file));
            FileBuffer reloaded = new FileBuffer(file.toFile());
            assertEquals("snapshot\n", reloaded.getContent()); assertTrue(reloaded.hasByteOrderMark());
        }
    }

    @Test void unmarkedFilesStayUnmarkedAndLiteralLeadingBomCharactersAreNotLost() throws Exception {
        Path plain = Files.writeString(root.resolve("plain.txt"), "plain\n");
        FileBuffer buffer = new FileBuffer(plain.toFile());
        assertFalse(buffer.hasByteOrderMark()); buffer.setContent("updated\n"); buffer.save();
        assertArrayEquals("updated\n".getBytes(StandardCharsets.UTF_8), Files.readAllBytes(plain));
        Path marked = root.resolve("literal.txt");
        Files.write(marked, marked("\ufeffliteral", StandardCharsets.UTF_8));
        buffer = new FileBuffer(marked.toFile()); assertEquals("\ufeffliteral", buffer.getContent()); buffer.save();
        assertArrayEquals(marked("\ufeffliteral", StandardCharsets.UTF_8), Files.readAllBytes(marked));
    }

    @Test void changingFormatDuringSnapshotSaveDoesNotMarkNewerEditorStateClean() throws Exception {
        Path file = Files.writeString(root.resolve("race.txt"), "initial\n");
        FileBuffer buffer = new FileBuffer(file.toFile()); buffer.setContent("pending\n");
        var snapshot = buffer.captureSaveSnapshot(null);
        buffer.setEncoding("UTF-16");
        buffer.writeSaveSnapshot(snapshot); buffer.completeSaveSnapshot(snapshot);
        assertTrue(buffer.isModified()); assertEquals("UTF-16", buffer.getEncoding());
        buffer.save();
        byte[] saved = Files.readAllBytes(file);
        assertEquals((byte) 0xfe, saved[0]); assertEquals((byte) 0xff, saved[1]);
        assertEquals("pending\n", new FileBuffer(file.toFile()).getContent());
    }

    @Test void formatOnlyChangesAreDirtyAndEquivalentCharsetAliasesPreserveTheMark() throws Exception {
        Path file = root.resolve("format.txt"); Files.write(file, marked("text\n", StandardCharsets.UTF_8));
        FileBuffer buffer = new FileBuffer(file.toFile());
        buffer.setEncoding("utf8"); assertFalse(buffer.isModified()); assertTrue(buffer.hasByteOrderMark());
        buffer.setLineEnding("\r\n"); assertTrue(buffer.isModified()); buffer.save();
        assertArrayEquals(marked("text\r\n", StandardCharsets.UTF_8), Files.readAllBytes(file));
        buffer.setEncoding("ISO-8859-1"); assertTrue(buffer.isModified()); assertFalse(buffer.hasByteOrderMark());
    }

    @Test void malformedMarkedFilesAreRejectedWithoutReplacingUndecodableBytes() throws Exception {
        for (byte[] bytes : List.of(new byte[]{(byte) 0xef, (byte) 0xbb, (byte) 0xbf, (byte) 0xff},
            new byte[]{(byte) 0xff, (byte) 0xfe, 0x41}, new byte[]{(byte) 0xfe, (byte) 0xff, (byte) 0xd8, 0x00})) {
            Path file = root.resolve("malformed.txt"); Files.write(file, bytes);
            assertThrows(java.io.IOException.class, () -> new FileBuffer(file.toFile()));
            assertArrayEquals(bytes, Files.readAllBytes(file));
        }
    }

    @Test void mixedNewlinesSurviveEditorSavesAndExplicitNormalizationRemainsDirtyDuringAnOlderSave() throws Exception {
        Path file = Files.writeString(root.resolve("mixed.txt"), "first\r\nmiddle\nlast\r");
        FileBuffer buffer = new FileBuffer(file.toFile());
        assertEquals("MIXED", buffer.getLineEndingLabel());
        buffer.replaceContentUndoably("first\nMIDDLE\nlast\n"); buffer.save();
        assertEquals("first\r\nMIDDLE\nlast\r", Files.readString(file));
        buffer.replaceContentUndoably("first\nPENDING\nlast\n");
        var pending = buffer.captureSaveSnapshot(null);
        // The preferred style already is CRLF; requesting it explicitly still means normalization.
        buffer.setLineEnding("\r\n");
        buffer.writeSaveSnapshot(pending); buffer.completeSaveSnapshot(pending);
        assertTrue(buffer.isModified()); assertEquals("CRLF", buffer.getLineEndingLabel());
        buffer.save();
        assertEquals("first\r\nPENDING\r\nlast\r\n", Files.readString(file));
        assertFalse(buffer.isModified());
    }

    @Test void repeatedSavesUseTheSelectedCharsetRatherThanGuessingItFromWrittenBytes() throws Exception {
        for (String name : List.of("windows-1252", "UTF-32BE")) {
            Path file = Files.writeString(root.resolve(name + ".txt"), "first\r\nmiddle\nlast\r");
            var charset = Charset.forName(name);
            FileBuffer buffer = new FileBuffer(file.toFile());
            buffer.setEncoding(name); buffer.replaceContentUndoably("first\n€ middle\nlast\n"); buffer.save();
            buffer.replaceContentUndoably("first\n€ newer\nlast\n");
            var pending = buffer.captureSaveSnapshot(null); buffer.writeSaveSnapshot(pending); buffer.completeSaveSnapshot(pending);
            assertEquals("first\r\n€ newer\nlast\r", new String(Files.readAllBytes(file), charset));
        }
    }

    private static byte[] marked(String text, Charset charset) {
        byte[] prefix = charset.equals(StandardCharsets.UTF_8) ? new byte[]{(byte) 0xef, (byte) 0xbb, (byte) 0xbf}
            : charset.equals(StandardCharsets.UTF_16LE) ? new byte[]{(byte) 0xff, (byte) 0xfe} : new byte[]{(byte) 0xfe, (byte) 0xff};
        byte[] data = text.getBytes(charset), result = new byte[prefix.length + data.length];
        System.arraycopy(prefix, 0, result, 0, prefix.length); System.arraycopy(data, 0, result, prefix.length, data.length);
        return result;
    }
}
