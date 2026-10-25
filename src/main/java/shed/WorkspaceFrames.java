package shed;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;

/** Shared newline framing for stdio and authenticated local connections. */
final class WorkspaceFrames {
    static final int MAX_REQUEST_BYTES = 1024 * 1024;
    static final int MAX_RESPONSE_BYTES = 16 * 1024 * 1024;
    private WorkspaceFrames() { }

    static byte[] read(InputStream input, int maximum) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        int next;
        while ((next = input.read()) != -1) {
            if (next == '\n') break;
            if (bytes.size() == maximum) throw new WorkspaceApiException(WorkspaceApiException.Code.LIMIT_EXCEEDED, "Frame exceeds its byte limit");
            bytes.write(next);
        }
        if (next == -1 && bytes.size() == 0) return null;
        byte[] frame = bytes.toByteArray();
        return frame.length > 0 && frame[frame.length - 1] == '\r' ? java.util.Arrays.copyOf(frame, frame.length - 1) : frame;
    }

    static String decode(byte[] bytes) throws java.nio.charset.CharacterCodingException {
        return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
    }

    static void write(OutputStream output, String frame, int maximum) throws IOException {
        byte[] bytes = frame.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > maximum) throw new WorkspaceApiException(WorkspaceApiException.Code.LIMIT_EXCEEDED, "Frame exceeds its byte limit");
        output.write(bytes); output.write('\n'); output.flush();
    }
}
