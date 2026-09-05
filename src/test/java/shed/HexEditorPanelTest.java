package shed;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class HexEditorPanelTest {
    @TempDir Path tempDir;

    @Test
    void supportsOnlyBoundedBinaryFiles() throws Exception {
        Path binary = Files.write(tempDir.resolve("asset.bin"), new byte[] {'A', 0, 'B'});
        Path text = Files.writeString(tempDir.resolve("notes.txt"), "plain text\n");

        assertTrue(HexEditorPanel.supports(binary));
        assertFalse(HexEditorPanel.supports(text));
        assertFalse(HexEditorPanel.supports(tempDir.resolve("missing.bin")));
    }

    @Test
    void rendersAlignedPagesAndAppliesValidatedSingleByteChanges() {
        byte[] source = new byte[32];
        source[0] = 0;
        source[1] = 0x20;
        source[2] = 'A';
        source[3] = 0x7F;
        source[4] = (byte) 0xFF;

        String first = HexEditorPanel.renderPage(source, 0, 16);
        String second = HexEditorPanel.renderPage(source, 20, 16);
        byte[] changed = HexEditorPanel.replaceByte(source, HexEditorPanel.parseOffset("0x2", source.length), HexEditorPanel.parseByte("FE"));

        assertTrue(first.contains("00000000"));
        assertTrue(first.contains("00 20 41 7F FF"));
        assertTrue(first.contains("| . A.."));
        assertTrue(second.contains("00000010"));
        assertEquals(15, HexEditorPanel.parseOffset("15", source.length));
        assertEquals(0xFE, Byte.toUnsignedInt(changed[2]));
        assertEquals('A', Byte.toUnsignedInt(source[2]));
        assertArrayEquals(source, HexEditorPanel.replaceByte(source, 2, 'A'));
        assertThrows(IllegalArgumentException.class, () -> HexEditorPanel.parseOffset("0x20", source.length));
        assertThrows(IllegalArgumentException.class, () -> HexEditorPanel.parseByte("ABC"));
        assertThrows(IllegalArgumentException.class, () -> HexEditorPanel.replaceByte(source, 32, 0));
    }
}
