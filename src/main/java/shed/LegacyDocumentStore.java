package shed;

import java.io.IOException;
import java.nio.file.Path;
import java.util.*;

/** Owns the canonical document list, using the existing FileBuffer engine. The list is exposed
 * only to legacy integration code during migration; metadata never copies document text. */
final class LegacyDocumentStore {
    private final List<FileBuffer> buffers = new ArrayList<>();
    private final Map<FileBuffer, DocumentWorkspace.Id> identities = new WeakHashMap<>();
    private long nextId;
    private final ConfigManager config;

    LegacyDocumentStore(ConfigManager config) { this.config = config; }
    List<FileBuffer> legacyBuffers() { return buffers; }
    DocumentWorkspace.Id id(FileBuffer buffer) {
        return buffer == null ? null : identities.computeIfAbsent(buffer, ignored -> new DocumentWorkspace.Id(++nextId));
    }
    DocumentWorkspace.Document describe(FileBuffer buffer) {
        if (buffer == null) return null;
        return new DocumentWorkspace.Document(id(buffer), buffer.getDisplayName(), buffer.hasFilePath() ? buffer.getFilePath() : null,
            buffer.isModified(), buffer.isLargeFile(), buffer.documentRevision());
    }
    List<DocumentWorkspace.Document> documents() { return buffers.stream().map(this::describe).toList(); }
    FileBuffer resolve(DocumentWorkspace.Id id) {
        if (id == null) return null;
        for (FileBuffer buffer : buffers) if (id.equals(identities.get(buffer))) return buffer;
        return null;
    }
    FileBuffer load(Path path) throws IOException {
        var file = path.toFile();
        return file.exists() ? new FileBuffer(file, config) : new FileBuffer(file.getAbsolutePath(), config);
    }
}
