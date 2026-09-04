package shed;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/** Persists explicit workspace overrides for adapter-provided exception breakpoint filters. */
final class ExceptionBreakpointStore {
    record Setting(String filter, boolean enabled) {
        Setting {
            filter = filter == null ? "" : filter.trim();
            if (!filter.matches("[A-Za-z0-9._-]{1,128}")) throw new IllegalArgumentException("exception breakpoint filter is invalid");
        }
    }

    private static final int VERSION = 1;
    private final Path directory;
    private final Map<Path, Map<String, Setting>> workspaces = new LinkedHashMap<>();

    ExceptionBreakpointStore(Path directory) {
        this.directory = Objects.requireNonNull(directory, "directory").toAbsolutePath().normalize();
    }

    synchronized Map<String, Setting> settings(Path workspace) throws IOException {
        return Map.copyOf(loaded(root(workspace)));
    }

    synchronized Setting configure(Path workspace, String filter, boolean enabled) throws IOException {
        Path root = root(workspace);
        Setting setting = new Setting(filter, enabled);
        Map<String, Setting> settings = loaded(root);
        settings.put(setting.filter(), setting);
        save(root, settings);
        return setting;
    }

    private Map<String, Setting> loaded(Path workspace) throws IOException {
        Map<String, Setting> loaded = workspaces.get(workspace);
        if (loaded != null) return loaded;
        Map<String, Setting> parsed = read(workspace);
        workspaces.put(workspace, parsed);
        return parsed;
    }

    private Map<String, Setting> read(Path workspace) throws IOException {
        Path target = target(workspace);
        if (!Files.exists(target)) return new LinkedHashMap<>();
        try {
            Map<String, Object> root = MiniJson.asObject(MiniJson.parse(Files.readString(target, StandardCharsets.UTF_8)));
            if (root == null || root.size() != 3 || !root.containsKey("version") || !root.containsKey("workspace") || !root.containsKey("settings")) {
                throw new IllegalArgumentException("root fields are invalid");
            }
            Object version = root.get("version");
            if (!(version instanceof Number number) || number.intValue() != VERSION || number.doubleValue() != number.intValue()) {
                throw new IllegalArgumentException("version is unsupported");
            }
            String persistedWorkspace = MiniJson.asString(root.get("workspace"));
            if (persistedWorkspace == null || !workspace.equals(Path.of(persistedWorkspace).toAbsolutePath().normalize())) {
                throw new IllegalArgumentException("workspace does not match its storage target");
            }
            java.util.List<Object> values = MiniJson.asArray(root.get("settings"));
            if (values == null) throw new IllegalArgumentException("settings is not an array");
            Map<String, Setting> settings = new LinkedHashMap<>();
            for (Object value : values) {
                Map<String, Object> fields = MiniJson.asObject(value);
                if (fields == null || fields.size() != 2 || !fields.containsKey("filter") || !fields.containsKey("enabled")) {
                    throw new IllegalArgumentException("setting fields are invalid");
                }
                String filter = MiniJson.asString(fields.get("filter"));
                if (!(fields.get("enabled") instanceof Boolean enabled)) throw new IllegalArgumentException("setting values are invalid");
                Setting setting = new Setting(filter, enabled);
                if (settings.put(setting.filter(), setting) != null) throw new IllegalArgumentException("setting filters are duplicated");
            }
            return settings;
        } catch (RuntimeException error) {
            throw new IOException("Exception breakpoint storage is invalid: " + error.getMessage(), error);
        }
    }

    private void save(Path workspace, Map<String, Setting> settings) throws IOException {
        Files.createDirectories(directory);
        java.util.List<Object> values = new java.util.ArrayList<>();
        for (Setting setting : new TreeMap<>(settings).values()) values.add(Map.of("filter", setting.filter(), "enabled", setting.enabled()));
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("version", VERSION);
        root.put("workspace", workspace.toString());
        root.put("settings", values);
        AtomicFileWriter.write(target(workspace), MiniJson.stringify(root).getBytes(StandardCharsets.UTF_8));
    }

    private Path target(Path workspace) { return directory.resolve("exception-breakpoints-" + hash(workspace.toString()).substring(0, 16) + ".json"); }

    private static Path root(Path workspace) {
        if (workspace == null) throw new IllegalArgumentException("workspace is required");
        return workspace.toAbsolutePath().normalize();
    }

    private static String hash(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder(digest.length * 2);
            for (byte valueByte : digest) result.append(String.format("%02x", valueByte));
            return result.toString();
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException("SHA-256 is unavailable", error);
        }
    }
}
