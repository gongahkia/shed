package shed;

import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.Objects;

/** Captured off the EDT after a filesystem transaction; adoption performs no filesystem reads. */
record WorkspaceDiskBinding(Path file, TextFileCodec.Image image, Object fileKey, FileTime created,
                            FileTime modified, long size, byte[] digest) {
    WorkspaceDiskBinding {
        file = Objects.requireNonNull(file).toAbsolutePath().normalize();
        Objects.requireNonNull(image); Objects.requireNonNull(created); Objects.requireNonNull(modified);
        digest = digest.clone();
    }
    @Override public byte[] digest() { return digest.clone(); }
    static WorkspaceDiskBinding capture(String workspaceId, Path file) {
        var snapshot = WorkspaceTextFile.capture(workspaceId, file, true);
        var attributes = snapshot.attributes();
        return new WorkspaceDiskBinding(file, snapshot.image(), attributes.fileKey(), attributes.creationTime(),
            attributes.lastModifiedTime(), attributes.size(), java.util.HexFormat.of().parseHex(WorkspacePaths.hash(snapshot.bytes())));
    }
}
