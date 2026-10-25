package shed;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class TextFileCodecTest {
    @Test void mixedNewlinesAndBomSurviveEditsWithoutRewritingUnchangedLineEndings() throws Exception {
        String raw = "first\r\nmiddle\nlast\r";
        byte[] original = TextFileCodec.encodeRaw(raw, "UTF-16LE", true);
        var image = TextFileCodec.decode(original, false);
        assertEquals("MIXED", image.lineEnding());
        assertArrayEquals(original, image.encode(image.text()));
        var changed = TextFileCodec.decode(image.encode("first\nMIDDLE\nlast\n"), false);
        assertEquals("first\r\nMIDDLE\nlast\r", changed.rawText());
        assertEquals("first\r\ninserted\r\nmiddle\nlast\r", image.render("first\ninserted\nmiddle\nlast\n"));
    }

    @Test void strictAndLegacyDecodingHaveExplicitBoundariesAndRejectLossyWrites() throws Exception {
        byte[] latin = new byte[]{(byte) 0xe9};
        assertThrows(java.nio.charset.CharacterCodingException.class, () -> TextFileCodec.decode(latin, false));
        var decoded = TextFileCodec.decode(latin, true);
        assertEquals("ISO-8859-1", decoded.encoding()); assertArrayEquals(latin, decoded.encode(decoded.text()));
        assertThrows(java.io.IOException.class, () -> decoded.encode("👩‍💻"));
        assertThrows(java.nio.charset.CharacterCodingException.class, () -> TextFileCodec.decode(new byte[]{(byte) 0xff, (byte) 0xfe, 1}, true));
        assertArrayEquals(new byte[]{(byte) 0xfe, (byte) 0xff}, TextFileCodec.encodeRaw("", "UTF-16", true));
        assertArrayEquals("plain".getBytes(StandardCharsets.UTF_8), TextFileCodec.encodeRaw("plain", "UTF-8", false));
    }
}
