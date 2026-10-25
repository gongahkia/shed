package shed;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Arrays;
import java.util.Objects;

/** Bounded disk snapshots for requests that have no open editor document. */
final class WorkspaceTextFile {
    static final int MAX_BYTES = 25 * 1024 * 1024;
    private WorkspaceTextFile() { }

    static WorkspaceDocument read(String workspaceId, Path file) {
        try {
            BasicFileAttributes before = Files.readAttributes(file, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            if (!before.isRegularFile()) throw new WorkspaceApiException(WorkspaceApiException.Code.UNSUPPORTED, "Only regular text files are supported");
            if (before.size() > MAX_BYTES) throw new WorkspaceApiException(WorkspaceApiException.Code.LIMIT_EXCEEDED, "File exceeds the machine snapshot limit");
            ByteArrayOutputStream output = new ByteArrayOutputStream((int) before.size());
            try (var channel = Files.newByteChannel(file, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)) {
                ByteBuffer buffer = ByteBuffer.allocate(8192);
                while (channel.read(buffer) != -1) {
                    buffer.flip();
                    if ((long) output.size() + buffer.remaining() > MAX_BYTES)
                        throw new WorkspaceApiException(WorkspaceApiException.Code.LIMIT_EXCEEDED, "File grew beyond the machine snapshot limit");
                    output.write(buffer.array(), 0, buffer.remaining()); buffer.clear();
                }
            }
            BasicFileAttributes after = Files.readAttributes(file, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            if (!after.isRegularFile() || before.size() != after.size() || !before.lastModifiedTime().equals(after.lastModifiedTime())
                || !Objects.equals(before.fileKey(), after.fileKey()))
                throw new WorkspaceApiException(WorkspaceApiException.Code.STALE_VERSION, "File changed while its snapshot was read");
            byte[] bytes = output.toByteArray();
            Charset charset = StandardCharsets.UTF_8;
            int skip = 0;
            if (prefix(bytes, 0xef, 0xbb, 0xbf)) skip = 3;
            else if (prefix(bytes, 0xff, 0xfe)) { skip = 2; charset = StandardCharsets.UTF_16LE; }
            else if (prefix(bytes, 0xfe, 0xff)) { skip = 2; charset = StandardCharsets.UTF_16BE; }
            String decoded = charset.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes, skip, bytes.length - skip)).toString();
            if (decoded.indexOf('\0') >= 0) throw new WorkspaceApiException(WorkspaceApiException.Code.UNSUPPORTED, "Binary content is not exposed as text");
            String ending = lineEnding(decoded);
            String text = decoded.replace("\r\n", "\n").replace('\r', '\n');
            String identity = file.toUri() + "\n" + after.fileKey() + "\n" + after.lastModifiedTime() + "\n" + WorkspacePaths.hash(bytes);
            return new WorkspaceDocument(workspaceId, file.toUri().toString(), file.toUri().toString(),
                "disk-" + WorkspacePaths.hash(identity), text, false, !Files.isWritable(file), true, charset.name(), ending, skip > 0,
                WorkspaceDocument.Source.DISK);
        } catch (java.nio.file.NoSuchFileException error) {
            throw new WorkspaceApiException(WorkspaceApiException.Code.NOT_FOUND, "Document does not exist");
        } catch (CharacterCodingException error) {
            throw new WorkspaceApiException(WorkspaceApiException.Code.UNSUPPORTED, "Text encoding is not supported; UTF-8 or BOM-marked UTF-16 is required");
        } catch (IOException | SecurityException error) {
            throw new WorkspaceApiException(WorkspaceApiException.Code.IO_ERROR, "Document could not be read");
        }
    }

    private static boolean prefix(byte[] bytes, int... prefix) {
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
