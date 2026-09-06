package shed;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Persists an explicitly accepted, already-safe launch.json compatibility snapshot per workspace. */
final class ImportedDebugProfileStore {
    record Report(Map<String, DebugAdapterRegistry.Configuration> profiles, String failure) {
        Report {
            profiles = profiles == null ? Map.of() : Map.copyOf(profiles);
            failure = failure == null ? "" : failure;
        }

        boolean usable() { return failure.isEmpty(); }
    }

    private static final int VERSION = 1;
    private static final long MAX_BYTES = 1024L * 1024L;
    private static final int MAX_PROFILES = 100;
    private final Path directory;
    private final Map<Path, Map<String, DebugAdapterRegistry.Configuration>> workspaces = new LinkedHashMap<>();

    ImportedDebugProfileStore(Path directory) {
        this.directory = Objects.requireNonNull(directory, "directory").toAbsolutePath().normalize();
    }

    synchronized Report profiles(Path workspace) {
        Path root = root(workspace);
        Map<String, DebugAdapterRegistry.Configuration> cached = workspaces.get(root);
        if (cached != null) return new Report(cached, "");
        try {
            Map<String, DebugAdapterRegistry.Configuration> loaded = read(root);
            workspaces.put(root, loaded);
            return new Report(loaded, "");
        } catch (IOException error) {
            return new Report(Map.of(), error.getMessage());
        }
    }

    synchronized int replace(Path workspace, Map<String, DebugAdapterRegistry.Configuration> profiles) throws IOException {
        Path root = root(workspace);
        Map<String, DebugAdapterRegistry.Configuration> values = validatedProfiles(profiles);
        save(root, values);
        workspaces.put(root, values);
        return values.size();
    }

    synchronized boolean clear(Path workspace) throws IOException {
        Path root = root(workspace);
        Path target = target(root);
        boolean existed = Files.deleteIfExists(target);
        workspaces.put(root, Map.of());
        return existed;
    }

    private Map<String, DebugAdapterRegistry.Configuration> read(Path workspace) throws IOException {
        Path target = target(workspace);
        if (!Files.exists(target, LinkOption.NOFOLLOW_LINKS)) return Map.of();
        if (Files.isSymbolicLink(target) || !Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Imported debug profile storage is not a regular file.");
        }
        try {
            if (Files.size(target) > MAX_BYTES) throw new IOException("Imported debug profile storage exceeds 1 MiB.");
            Map<String, Object> root = MiniJson.asObject(MiniJson.parse(Files.readString(target, StandardCharsets.UTF_8)));
            if (root == null || root.size() != 3 || !root.containsKey("version") || !root.containsKey("workspace") || !root.containsKey("profiles")) {
                throw new IllegalArgumentException("root fields are invalid");
            }
            if (!version(root.get("version"))) throw new IllegalArgumentException("version is unsupported");
            String persistedWorkspace = MiniJson.asString(root.get("workspace"));
            if (persistedWorkspace == null || !workspace.equals(Path.of(persistedWorkspace).toAbsolutePath().normalize())) {
                throw new IllegalArgumentException("workspace does not match its storage target");
            }
            List<Object> entries = MiniJson.asArray(root.get("profiles"));
            if (entries == null || entries.size() > MAX_PROFILES) throw new IllegalArgumentException("profiles are invalid");
            Map<String, DebugAdapterRegistry.Configuration> values = new LinkedHashMap<>();
            for (Object entry : entries) {
                DebugAdapterRegistry.Configuration profile = profile(MiniJson.asObject(entry));
                if (values.put(profile.name(), profile) != null) throw new IllegalArgumentException("profile names are duplicated");
            }
            return Map.copyOf(values);
        } catch (RuntimeException error) {
            throw new IOException("Imported debug profile storage is invalid: " + error.getMessage(), error);
        }
    }

    private void save(Path workspace, Map<String, DebugAdapterRegistry.Configuration> profiles) throws IOException {
        Files.createDirectories(directory);
        List<Object> entries = new ArrayList<>();
        profiles.values().stream().sorted(Comparator.comparing(DebugAdapterRegistry.Configuration::name)).forEach(profile -> entries.add(profileObject(profile)));
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("version", VERSION);
        root.put("workspace", workspace.toString());
        root.put("profiles", entries);
        byte[] encoded = MiniJson.stringify(root).getBytes(StandardCharsets.UTF_8);
        if (encoded.length > MAX_BYTES) throw new IOException("Imported debug profiles exceed 1 MiB.");
        AtomicFileWriter.write(target(workspace), encoded);
    }

    private static Map<String, DebugAdapterRegistry.Configuration> validatedProfiles(Map<String, DebugAdapterRegistry.Configuration> profiles) {
        Map<String, DebugAdapterRegistry.Configuration> supplied = profiles == null ? Map.of() : profiles;
        if (supplied.size() > MAX_PROFILES) throw new IllegalArgumentException("at most 100 profiles may be persisted");
        Map<String, DebugAdapterRegistry.Configuration> values = new LinkedHashMap<>();
        for (Map.Entry<String, DebugAdapterRegistry.Configuration> entry : supplied.entrySet()) {
            String name = entry.getKey();
            DebugAdapterRegistry.Configuration profile = entry.getValue();
            if (!safeName(name) || profile == null || !name.equals(profile.name()) || !"workspace".equals(profile.scope())
                || !profile.fileExtensions().isEmpty() || !profile.inputs().isEmpty()) {
                throw new IllegalArgumentException("profile is invalid");
            }
            if (values.put(name, profile) != null) throw new IllegalArgumentException("profile names are duplicated");
        }
        return Map.copyOf(values);
    }

    private static Map<String, Object> profileObject(DebugAdapterRegistry.Configuration profile) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("name", profile.name());
        value.put("adapter", profile.adapter());
        value.put("request", profile.request().name());
        value.put("program", profile.program());
        value.put("module", profile.module());
        value.put("code", profile.code());
        value.put("cwd", profile.cwd());
        value.put("args", profile.args());
        value.put("prelaunchTask", profile.prelaunchTask());
        value.put("host", profile.host());
        value.put("port", profile.port());
        value.put("environment", profile.environment());
        value.put("adapterOptions", profile.adapterOptions());
        return value;
    }

    private static DebugAdapterRegistry.Configuration profile(Map<String, Object> fields) {
        if (fields == null || fields.size() != 13 || !fields.keySet().containsAll(List.of("name", "adapter", "request", "program", "module", "code",
            "cwd", "args", "prelaunchTask", "host", "port", "environment", "adapterOptions"))) {
            throw new IllegalArgumentException("profile fields are invalid");
        }
        String name = text(fields, "name");
        String adapter = text(fields, "adapter");
        String request = text(fields, "request");
        String program = text(fields, "program");
        String module = text(fields, "module");
        String code = text(fields, "code");
        String cwd = text(fields, "cwd");
        String prelaunchTask = text(fields, "prelaunchTask");
        String host = text(fields, "host");
        List<String> args = strings(fields.get("args"));
        Map<String, String> environment = environment(fields.get("environment"));
        Map<String, Object> adapterOptions = MiniJson.asObject(fields.get("adapterOptions"));
        int port = port(fields.get("port"));
        if (!safeName(name) || adapter == null || program == null || module == null || code == null || cwd == null || prelaunchTask == null || host == null
            || args == null || environment == null || adapterOptions == null || port < 0) throw new IllegalArgumentException("profile values are invalid");
        DebugAdapterRegistry.Request launchRequest;
        try { launchRequest = DebugAdapterRegistry.Request.valueOf(request == null ? "" : request); }
        catch (IllegalArgumentException error) { throw new IllegalArgumentException("profile request is invalid"); }
        if (!DebugAdapterRegistry.safeAdapterOptions(adapterOptions)) throw new IllegalArgumentException("profile adapter options are invalid");
        return new DebugAdapterRegistry.Configuration(name, adapter, launchRequest, "workspace", program, module, code, cwd, args, prelaunchTask, host,
            port, List.of(), environment, adapterOptions);
    }

    private static String text(Map<String, Object> fields, String key) { return MiniJson.asString(fields.get(key)); }

    private static List<String> strings(Object value) {
        List<Object> raw = MiniJson.asArray(value);
        if (raw == null || raw.size() > 256) return null;
        List<String> result = new ArrayList<>();
        for (Object item : raw) {
            String text = MiniJson.asString(item);
            if (text == null) return null;
            result.add(text);
        }
        return List.copyOf(result);
    }

    private static Map<String, String> environment(Object value) {
        Map<String, Object> raw = MiniJson.asObject(value);
        if (raw == null || raw.size() > 100) return null;
        Map<String, String> result = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : raw.entrySet()) {
            String text = MiniJson.asString(entry.getValue());
            if (entry.getKey() == null || text == null) return null;
            result.put(entry.getKey(), text);
        }
        return DebugAdapterRegistry.safeEnvironment(result) ? Map.copyOf(result) : null;
    }

    private static int port(Object value) {
        if (!(value instanceof Number number) || number.doubleValue() != number.intValue()) return -1;
        return number.intValue();
    }

    private static boolean version(Object value) {
        return value instanceof Number number && number.doubleValue() == number.intValue() && number.intValue() == VERSION;
    }

    private Path target(Path workspace) { return directory.resolve("imported-debug-profiles-" + hash(workspace.toString()).substring(0, 16) + ".json"); }

    private static Path root(Path workspace) {
        if (workspace == null) throw new IllegalArgumentException("workspace is required");
        return workspace.toAbsolutePath().normalize();
    }

    private static boolean safeName(String value) {
        return value != null && !value.isBlank() && value.length() <= 160 && value.indexOf('\u0000') < 0 && value.indexOf('\n') < 0 && value.indexOf('\r') < 0;
    }

    private static String hash(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder(digest.length * 2);
            for (byte byteValue : digest) result.append(String.format("%02x", byteValue));
            return result.toString();
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException("SHA-256 is unavailable", error);
        }
    }
}
