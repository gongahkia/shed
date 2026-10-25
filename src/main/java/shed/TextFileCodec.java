package shed;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.List;

/** Shared strict encoding and normalized-offset mapping for editor and workspace writes. */
final class TextFileCodec {
    record Image(String rawText, String text, String encoding, boolean bom, String lineEnding) {
        String render(String updated) {
            if (!lineEnding.equals("MIXED")) return updated.replace("\n",
                lineEnding.equals("CRLF") ? "\r\n" : lineEnding.equals("CR") ? "\r" : "\n");
            List<WorkspaceTextEdits.Hunk> changes = WorkspaceTextEdits.diff(text, updated);
            if (changes.isEmpty()) return rawText;
            int[] boundaries = changes.stream().flatMapToInt(change -> java.util.stream.IntStream.of(change.start(), change.end())).distinct().sorted().toArray();
            java.util.Map<Integer, Integer> offsets = new java.util.HashMap<>();
            int normalized = 0, next = 0;
            for (int i = 0; i < rawText.length(); i++) {
                if (next < boundaries.length && normalized == boundaries[next]) { offsets.put(normalized, i); next++; }
                normalized++;
                if (rawText.charAt(i) == '\r' && i + 1 < rawText.length() && rawText.charAt(i + 1) == '\n') i++;
            }
            offsets.put(normalized, rawText.length());
            StringBuilder result = new StringBuilder(rawText);
            for (int i = changes.size() - 1; i >= 0; i--) {
                var change = changes.get(i);
                String replacement = change.replacement().replace("\n", preferredEnding(offsets.get(change.start())));
                result.replace(offsets.get(change.start()), offsets.get(change.end()), replacement);
            }
            return result.toString();
        }
        private String preferredEnding(int offset) {
            // Match the nearest preceding newline, then the nearest following newline for a new first line.
            for (int i = Math.min(offset - 1, rawText.length() - 1); i >= 0; i--) {
                if (rawText.charAt(i) == '\n') return i > 0 && rawText.charAt(i - 1) == '\r' ? "\r\n" : "\n";
                if (rawText.charAt(i) == '\r') return i + 1 < rawText.length() && rawText.charAt(i + 1) == '\n' ? "\r\n" : "\r";
            }
            for (int i = offset; i < rawText.length(); i++) {
                if (rawText.charAt(i) == '\n') return "\n";
                if (rawText.charAt(i) == '\r') return i + 1 < rawText.length() && rawText.charAt(i + 1) == '\n' ? "\r\n" : "\r";
            }
            return "\n";
        }
        byte[] encode(String updated) throws IOException { return encodeRaw(render(updated), encoding, bom); }
    }
    private TextFileCodec() { }

    static Image decode(byte[] bytes, boolean allowLatin1) throws CharacterCodingException {
        Charset charset = StandardCharsets.UTF_8;
        int prefix = 0;
        if (starts(bytes, 0xef, 0xbb, 0xbf)) prefix = 3;
        else if (starts(bytes, 0xff, 0xfe)) { prefix = 2; charset = StandardCharsets.UTF_16LE; }
        else if (starts(bytes, 0xfe, 0xff)) { prefix = 2; charset = StandardCharsets.UTF_16BE; }
        String raw;
        try {
            raw = charset.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes, prefix, bytes.length - prefix)).toString();
        } catch (CharacterCodingException malformed) {
            if (!allowLatin1 || prefix > 0) throw malformed;
            charset = StandardCharsets.ISO_8859_1; raw = new String(bytes, charset);
        }
        return image(raw, charset.name(), prefix > 0);
    }

    static Image image(String raw, String encoding, boolean bom) {
        return new Image(raw, raw.replace("\r\n", "\n").replace('\r', '\n'), encoding, bom, lineEnding(raw));
    }

    static byte[] encodeRaw(String content, String encoding, boolean bom) throws IOException {
        try {
            Charset charset = Charset.forName(encoding);
            ByteBuffer encoded = charset.newEncoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).encode(CharBuffer.wrap(content));
            byte[] bytes = new byte[encoded.remaining()]; encoded.get(bytes);
            if (!bom) return bytes;
            byte[] prefix = charset.equals(StandardCharsets.UTF_8) ? new byte[]{(byte) 0xef, (byte) 0xbb, (byte) 0xbf}
                : charset.equals(StandardCharsets.UTF_16LE) ? new byte[]{(byte) 0xff, (byte) 0xfe}
                : charset.equals(StandardCharsets.UTF_16BE) || charset.equals(StandardCharsets.UTF_16) ? new byte[]{(byte) 0xfe, (byte) 0xff} : new byte[0];
            if (charset.equals(StandardCharsets.UTF_16) && starts(bytes, 0xfe, 0xff)) return bytes;
            byte[] result = new byte[prefix.length + bytes.length];
            System.arraycopy(prefix, 0, result, 0, prefix.length); System.arraycopy(bytes, 0, result, prefix.length, bytes.length);
            return result;
        } catch (CharacterCodingException | IllegalArgumentException unsupported) {
            throw new IOException("Content cannot be encoded as " + encoding, unsupported);
        }
    }

    private static boolean starts(byte[] bytes, int... prefix) {
        if (bytes.length < prefix.length) return false;
        for (int i = 0; i < prefix.length; i++) if ((bytes[i] & 255) != prefix[i]) return false;
        return true;
    }

    static String lineEnding(String text) {
        boolean crlf = text.contains("\r\n");
        String other = text.replace("\r\n", "");
        boolean cr = other.indexOf('\r') >= 0, lf = other.indexOf('\n') >= 0;
        if ((crlf ? 1 : 0) + (cr ? 1 : 0) + (lf ? 1 : 0) > 1) return "MIXED";
        return crlf ? "CRLF" : cr ? "CR" : lf ? "LF" : "NONE";
    }
}
