package shed;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;

/** Strict path boundary for machine requests, including targets that do not exist yet. */
final class WorkspacePaths {
    private WorkspacePaths() { }

    static Path root(Path requested) {
        try {
            Path root = requested.toRealPath();
            if (!Files.isDirectory(root)) throw new IOException("not a directory");
            return root;
        } catch (IOException | SecurityException error) {
            throw new WorkspaceApiException(WorkspaceApiException.Code.NOT_FOUND, "Workspace directory is unavailable");
        }
    }

    static Path resolve(Path root, String requested) {
        if (requested == null || requested.isBlank() || requested.length() > 8192 || requested.indexOf('\0') >= 0)
            throw new WorkspaceApiException(WorkspaceApiException.Code.INVALID_REQUEST, "A bounded document path is required");
        Path path;
        try {
            if (requested.startsWith("file:")) path = Path.of(URI.create(requested));
            else {
                if (requested.contains("://")) throw new IllegalArgumentException("unsupported scheme");
                path = Path.of(requested);
            }
            if (!path.isAbsolute()) path = root.resolve(path);
            path = path.toAbsolutePath().normalize();
        } catch (IllegalArgumentException error) {
            throw new WorkspaceApiException(WorkspaceApiException.Code.INVALID_REQUEST, "Expected a local workspace path or file URI");
        }
        try {
            Path existing = path;
            while (existing != null && !Files.exists(existing, LinkOption.NOFOLLOW_LINKS)) existing = existing.getParent();
            if (existing == null) throw new IOException("no existing parent");
            Path resolved = existing.toRealPath().resolve(existing.relativize(path)).normalize();
            if (!resolved.startsWith(root)) throw new WorkspaceApiException(
                WorkspaceApiException.Code.OUTSIDE_WORKSPACE, "Path is outside the requested workspace");
            return resolved;
        } catch (IOException | SecurityException error) {
            throw new WorkspaceApiException(WorkspaceApiException.Code.IO_ERROR, "Workspace path cannot be resolved safely");
        }
    }

    static String id(Path root) { return "workspace-" + hash(root.toUri().toString()).substring(0, 24); }
    static String hash(String value) { return hash(value.getBytes(java.nio.charset.StandardCharsets.UTF_8)); }
    static String hash(byte[] value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value)); }
        catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
}
