package shed;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Objects;

/** Bounded disk snapshots for requests that have no open editor document. */
final class WorkspaceTextFile {
    record Snapshot(WorkspaceDocument document, byte[] bytes, TextFileCodec.Image image, BasicFileAttributes attributes) {
        Snapshot { bytes = bytes.clone(); }
        @Override public byte[] bytes() { return bytes.clone(); }
    }
    static final int MAX_BYTES = 25 * 1024 * 1024;
    private WorkspaceTextFile() { }

    static WorkspaceDocument read(String workspaceId, Path file) {
        return capture(workspaceId, file, false).document();
    }

    static Snapshot capture(String workspaceId, Path file, boolean allowLatin1) {
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
            TextFileCodec.Image decoded = TextFileCodec.decode(bytes, allowLatin1);
            String text = decoded.text();
            if (text.indexOf('\0') >= 0) throw new WorkspaceApiException(WorkspaceApiException.Code.UNSUPPORTED, "Binary content is not exposed as text");
            String identity = file.toUri() + "\n" + after.fileKey() + "\n" + after.lastModifiedTime() + "\n" + WorkspacePaths.hash(bytes);
            WorkspaceDocument document = new WorkspaceDocument(workspaceId, file.toUri().toString(), file.toUri().toString(),
                "disk-" + WorkspacePaths.hash(identity), text, false, !Files.isWritable(file), true, decoded.encoding(), decoded.lineEnding(), decoded.bom(),
                WorkspaceDocument.Source.DISK);
            return new Snapshot(document, bytes, decoded, after);
        } catch (java.nio.file.NoSuchFileException error) {
            throw new WorkspaceApiException(WorkspaceApiException.Code.NOT_FOUND, "Document does not exist");
        } catch (CharacterCodingException error) {
            throw new WorkspaceApiException(WorkspaceApiException.Code.UNSUPPORTED, "Text encoding is not supported; UTF-8 or BOM-marked UTF-16 is required");
        } catch (IOException | SecurityException error) {
            throw new WorkspaceApiException(WorkspaceApiException.Code.IO_ERROR, "Document could not be read");
        }
    }

    static String lineEnding(String text) { return TextFileCodec.lineEnding(text); }
}
