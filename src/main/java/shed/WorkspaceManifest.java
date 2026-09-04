package shed;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Reads portable workspace manifests without applying editor settings or executing configuration. */
final class WorkspaceManifest {
    private static final long MAX_BYTES = 1024L * 1024L;
    private static final int MAX_FOLDERS = 100;

    private WorkspaceManifest() {
    }

    /**
     * A validated portable workspace document. The raw task and launch values stay inert here;
     * their deliberately narrow compatibility importers validate them again before exposing a
     * session-only task or debug configuration.
     */
    record Document(Path source, List<Path> folders, boolean standardVsCodeWorkspace,
                    boolean hasSettings, Object settings, boolean hasTasks, Object tasks, boolean hasLaunch, Object launch) {
        Document {
            source = source == null ? null : source.toAbsolutePath().normalize();
            folders = folders == null ? List.of() : List.copyOf(folders);
        }
    }

    /** Runtime state for the constrained task/launch bridge of an imported VS Code workspace. */
    record ImportedConfiguration(Path source, boolean hasTasks, Object tasks, boolean hasLaunch, Object launch, String failure) {
        ImportedConfiguration {
            source = source == null ? null : source.toAbsolutePath().normalize();
            failure = failure == null ? "" : failure;
        }

        boolean present() {
            return source != null;
        }

        boolean usable() {
            return present() && failure.isEmpty();
        }
    }

    static List<Path> read(Path source) throws IOException {
        return readDocument(source).folders();
    }

    static Document readDocument(Path source) throws IOException {
        Path manifest = regularManifest(source);
        if (Files.size(manifest) > MAX_BYTES) throw new IOException("Workspace manifest exceeds 1 MiB");
        Map<String, Object> root = Jsonc.parseObject(Files.readString(manifest, StandardCharsets.UTF_8));
        List<Object> folders = MiniJson.asArray(root.get("folders"));
        if (folders == null || folders.isEmpty()) throw new IOException("Workspace manifest requires at least one folder");
        if (folders.size() > MAX_FOLDERS) throw new IOException("Workspace manifest has too many folders");

        Path base = manifest.getParent();
        Set<Path> unique = new LinkedHashSet<>();
        for (int index = 0; index < folders.size(); index++) {
            String raw = folderPath(folders.get(index));
            if (raw == null || raw.isBlank()) throw new IOException("Workspace folder " + (index + 1) + " requires a path");
            final Path parsed;
            try {
                parsed = Path.of(raw);
            } catch (RuntimeException error) {
                throw new IOException("Workspace folder " + (index + 1) + " has an invalid path", error);
            }
            Path resolved = (parsed.isAbsolute() ? parsed : base.resolve(parsed)).normalize();
            if (!Files.isDirectory(resolved)) throw new IOException("Workspace folder is not an existing directory: " + raw);
            unique.add(resolved.toRealPath());
        }
        return new Document(manifest, List.copyOf(unique), manifest.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".code-workspace"),
            root.containsKey("settings"), root.get("settings"),
            root.containsKey("tasks"), root.get("tasks"),
            root.containsKey("launch"), root.get("launch"));
    }

    static void write(Path target, List<Path> roots) throws IOException {
        Path manifest = writableManifest(target);
        if (roots == null || roots.isEmpty()) throw new IOException("At least one workspace folder is required");
        if (roots.size() > MAX_FOLDERS) throw new IOException("Workspace has too many folders");

        Path base = manifest.getParent();
        Set<Path> unique = new LinkedHashSet<>();
        for (Path root : roots) {
            if (root == null || !Files.isDirectory(root)) throw new IOException("Workspace folder is not an existing directory: " + root);
            unique.add(root.toRealPath());
        }
        Map<String, Object> document = new LinkedHashMap<>();
        List<Object> folders = new ArrayList<>();
        for (Path root : unique) {
            Map<String, Object> folder = new LinkedHashMap<>();
            folder.put("path", portablePath(base, root));
            folders.add(folder);
        }
        document.put("folders", folders);
        AtomicFileWriter.write(manifest, (MiniJson.stringify(document) + "\n").getBytes(StandardCharsets.UTF_8));
    }

    private static String folderPath(Object value) {
        String direct = MiniJson.asString(value);
        if (direct != null) return direct;
        Map<String, Object> object = MiniJson.asObject(value);
        return object == null ? null : MiniJson.asString(object.get("path"));
    }

    private static String portablePath(Path base, Path root) {
        try {
            Path relative = base.relativize(root);
            return relative.toString().isEmpty() ? "." : relative.toString();
        } catch (IllegalArgumentException error) {
            return root.toString();
        }
    }

    private static Path regularManifest(Path source) throws IOException {
        Path manifest = normalized(source);
        if (!Files.isRegularFile(manifest)) throw new IOException("Workspace manifest is not a regular file: " + manifest);
        return manifest;
    }

    private static Path writableManifest(Path source) throws IOException {
        Path manifest = normalized(source);
        Path parent = manifest.getParent();
        if (parent == null || !Files.isDirectory(parent)) throw new IOException("Workspace manifest parent directory is unavailable");
        if (Files.exists(manifest) && !Files.isRegularFile(manifest)) throw new IOException("Workspace manifest is not a regular file: " + manifest);
        return manifest;
    }

    private static Path normalized(Path source) throws IOException {
        if (source == null) throw new IOException("Workspace manifest path is required");
        Path manifest = source.toAbsolutePath().normalize();
        String name = manifest.getFileName() == null ? "" : manifest.getFileName().toString().toLowerCase(Locale.ROOT);
        if (!name.endsWith(".shed-workspace") && !name.endsWith(".code-workspace")) {
            throw new IOException("Workspace manifest must end in .shed-workspace or .code-workspace");
        }
        return manifest;
    }
}
