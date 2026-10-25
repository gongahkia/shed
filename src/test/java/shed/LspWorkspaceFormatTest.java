package shed;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Exercises the actual disk-only LSP application path without constructing a frame. */
class LspWorkspaceFormatTest {
    @TempDir Path temporary;

    @Test void workspaceEditsUseNormalizedOffsetsAndPreserveBomEncodingAndMixedNewlines() throws Exception {
        Path root = temporary.toRealPath();
        for (String encoding : List.of("UTF-8", "UTF-16LE", "UTF-16BE")) {
            Path file = root.resolve(encoding + ".txt");
            Files.write(file, TextFileCodec.encodeRaw("first\r\nmiddle\nlast\r", encoding, true));
            LspController controller = new LspController(null);
            var result = controller.applyWorkspaceTextEdits(List.of(
                new LspClient.TextEdit(file.toUri().toString(), 0, 0, 0, 5, "FIRST"),
                new LspClient.TextEdit(file.toUri().toString(), 1, 0, 1, 6, "MIDDLE")));
            assertEquals(0, result.failedFiles, result.failureReason);
            assertEquals(2, result.appliedEditCount);
            assertArrayEquals(TextFileCodec.encodeRaw("FIRST\r\nMIDDLE\nlast\r", encoding, true), Files.readAllBytes(file));
        }
    }

    @Test void renameThenEditRetainsTheSourcesFormatAndUndecodableResourceOnlyRenamesRemainLossless() throws Exception {
        Path root = temporary.toRealPath(), old = root.resolve("old.txt"), target = root.resolve("renamed.txt");
        Files.write(old, TextFileCodec.encodeRaw("before\r\n", "UTF-16LE", true));
        var result = new LspController(null).applyWorkspaceOperations(List.of(
            LspClient.WorkspaceEditOperation.rename(old.toUri().toString(), target.toUri().toString(), false, false),
            LspClient.WorkspaceEditOperation.textEdit(new LspClient.TextEdit(target.toUri().toString(), 0, 0, 0, 6, "after"))));
        assertEquals(0, result.failedFiles, result.failureReason); assertFalse(Files.exists(old));
        assertArrayEquals(TextFileCodec.encodeRaw("after\r\n", "UTF-16LE", true), Files.readAllBytes(target));
        Path binary = root.resolve("binary"), moved = root.resolve("moved");
        byte[] bytes = {(byte) 0xff, (byte) 0xfe, 0, 1, 0}; Files.write(binary, bytes);
        result = new LspController(null).applyWorkspaceOperations(List.of(
            LspClient.WorkspaceEditOperation.rename(binary.toUri().toString(), moved.toUri().toString(), false, false)));
        assertEquals(0, result.failedFiles, result.failureReason); assertArrayEquals(bytes, Files.readAllBytes(moved));
    }
}
