package shed;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/** Selects already-installed local Python, Node, and Go executables for one workspace. */
final class ToolchainService {
    enum Runtime {
        PYTHON("python"), NODE("node"), GO("go");

        private final String id;

        Runtime(String id) { this.id = id; }

        String id() { return id; }

        static Runtime parse(String value) {
            if (value == null) return null;
            String normalized = value.trim().toLowerCase(Locale.ROOT);
            for (Runtime runtime : values()) if (runtime.id.equals(normalized)) return runtime;
            return null;
        }
    }

    record Candidate(Runtime runtime, Path executable, String source) {
        Candidate {
            runtime = Objects.requireNonNull(runtime, "runtime");
            executable = Objects.requireNonNull(executable, "executable").toAbsolutePath().normalize();
            source = source == null ? "" : source;
        }
    }

    record Report(Map<Runtime, Path> selections, List<Candidate> candidates, String failure) {
        Report {
            selections = selections == null ? Map.of() : Map.copyOf(selections);
            candidates = candidates == null ? List.of() : List.copyOf(candidates);
            failure = failure == null ? "" : failure;
        }

        boolean usable() { return failure.isEmpty(); }
    }

    private static final int VERSION = 1;
    private static final long MAX_BYTES = 16L * 1024L;
    private final Path directory;
    private final Map<String, String> parentEnvironment;
    private final Map<Path, Map<Runtime, Path>> workspaces = new LinkedHashMap<>();

    ToolchainService(Path directory) {
        this(directory, System.getenv());
    }

    ToolchainService(Path directory, Map<String, String> parentEnvironment) {
        this.directory = Objects.requireNonNull(directory, "directory").toAbsolutePath().normalize();
        this.parentEnvironment = Map.copyOf(parentEnvironment == null ? Map.of() : parentEnvironment);
    }

    synchronized Report report(Path workspace) {
        Path root = root(workspace);
        Map<Runtime, Path> selections = workspaces.get(root);
        if (selections == null) {
            try {
                selections = read(root);
                workspaces.put(root, selections);
            } catch (IOException error) {
                return new Report(Map.of(), candidates(root), error.getMessage());
            }
        }
        return new Report(selections, candidates(root), "");
    }

    synchronized Path select(Path workspace, Runtime runtime, String requestedExecutable) throws IOException {
        Path root = root(workspace);
        if (runtime == null) throw new IllegalArgumentException("toolchain runtime is required");
        Path executable = executable(requestedExecutable);
        Map<Runtime, Path> selections = new EnumMap<>(Runtime.class);
        Report current = report(root);
        if (!current.usable()) throw new IOException(current.failure());
        selections.putAll(current.selections());
        selections.put(runtime, executable);
        Map<Runtime, Path> immutable = Map.copyOf(selections);
        save(root, immutable);
        workspaces.put(root, immutable);
        return executable;
    }

    synchronized boolean clear(Path workspace, Runtime runtime) throws IOException {
        Path root = root(workspace);
        if (runtime == null) throw new IllegalArgumentException("toolchain runtime is required");
        Report current = report(root);
        if (!current.usable()) throw new IOException(current.failure());
        if (!current.selections().containsKey(runtime)) return false;
        Map<Runtime, Path> selections = new EnumMap<>(Runtime.class);
        selections.putAll(current.selections());
        selections.remove(runtime);
        Map<Runtime, Path> immutable = Map.copyOf(selections);
        if (immutable.isEmpty()) {
            Files.deleteIfExists(target(root));
        } else {
            save(root, immutable);
        }
        workspaces.put(root, immutable);
        return true;
    }

    /** Environment variables for local process launches. Task-specific variables may intentionally override these. */
    Map<String, String> environment(Path workspace) {
        Report report = report(workspace);
        if (!report.usable() || report.selections().isEmpty()) return Map.of();
        List<String> directories = new ArrayList<>();
        Path python = report.selections().get(Runtime.PYTHON);
        for (Runtime runtime : Runtime.values()) {
            Path executable = report.selections().get(runtime);
            if (executable == null || executable.getParent() == null) continue;
            String directory = executable.getParent().toString();
            if (!directories.contains(directory)) directories.add(directory);
        }
        Map<String, String> result = new LinkedHashMap<>();
        if (!directories.isEmpty()) {
            String inheritedPath = parentEnvironment.getOrDefault("PATH", "");
            String prefix = String.join(File.pathSeparator, directories);
            result.put("PATH", inheritedPath.isBlank() ? prefix : prefix + File.pathSeparator + inheritedPath);
        }
        if (python != null && python.getParent() != null && python.getParent().getParent() != null
            && Files.isRegularFile(python.getParent().getParent().resolve("pyvenv.cfg"))) {
            result.put("VIRTUAL_ENV", python.getParent().getParent().toString());
        }
        return Map.copyOf(result);
    }

    Map<String, String> environment(Path workspace, Map<String, String> taskEnvironment) {
        Map<String, String> result = new LinkedHashMap<>(environment(workspace));
        if (taskEnvironment != null) result.putAll(taskEnvironment);
        return Map.copyOf(result);
    }

    private List<Candidate> candidates(Path workspace) {
        Map<Path, Candidate> values = new LinkedHashMap<>();
        for (String environment : List.of(".venv", "venv", "env")) {
            addCandidate(values, Runtime.PYTHON, workspace.resolve(environment).resolve("bin").resolve("python"), "workspace " + environment);
            addCandidate(values, Runtime.PYTHON, workspace.resolve(environment).resolve("Scripts").resolve("python.exe"), "workspace " + environment);
        }
        addPathCandidate(values, Runtime.PYTHON, "python");
        addPathCandidate(values, Runtime.PYTHON, "python3");
        addPathCandidate(values, Runtime.NODE, isWindows() ? "node.exe" : "node");
        addPathCandidate(values, Runtime.GO, isWindows() ? "go.exe" : "go");
        return values.values().stream().sorted(Comparator.comparing((Candidate candidate) -> candidate.runtime().ordinal())
            .thenComparing(candidate -> candidate.executable().toString())).toList();
    }

    private void addPathCandidate(Map<Path, Candidate> values, Runtime runtime, String name) {
        Path executable = findOnPath(name);
        if (executable != null) addCandidate(values, runtime, executable, "PATH");
    }

    private void addCandidate(Map<Path, Candidate> values, Runtime runtime, Path candidate, String source) {
        try {
            Path executable = executable(candidate == null ? null : candidate.toString());
            values.putIfAbsent(executable, new Candidate(runtime, executable, source));
        } catch (IllegalArgumentException ignored) {
            // Detection is advisory; unavailable or non-executable candidates are omitted.
        }
    }

    private Path findOnPath(String name) {
        String path = parentEnvironment.get("PATH");
        if (path == null || path.isBlank()) return null;
        for (String entry : path.split(java.util.regex.Pattern.quote(File.pathSeparator))) {
            if (entry.isBlank()) continue;
            Path candidate;
            try { candidate = Path.of(entry).resolve(name); }
            catch (RuntimeException error) { continue; }
            try { return executable(candidate.toString()); }
            catch (IllegalArgumentException ignored) { }
        }
        return null;
    }

    private Map<Runtime, Path> read(Path workspace) throws IOException {
        Path target = target(workspace);
        if (!Files.exists(target, LinkOption.NOFOLLOW_LINKS)) return Map.of();
        if (Files.isSymbolicLink(target) || !Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Toolchain selection storage is not a regular file.");
        }
        try {
            if (Files.size(target) > MAX_BYTES) throw new IOException("Toolchain selection storage exceeds 16 KiB.");
            Map<String, Object> fields = MiniJson.asObject(MiniJson.parse(Files.readString(target, StandardCharsets.UTF_8)));
            if (fields == null || fields.size() != 3 || !fields.containsKey("version") || !fields.containsKey("workspace") || !fields.containsKey("selections")) {
                throw new IllegalArgumentException("root fields are invalid");
            }
            if (!version(fields.get("version"))) throw new IllegalArgumentException("version is unsupported");
            String persistedWorkspace = MiniJson.asString(fields.get("workspace"));
            if (persistedWorkspace == null || !workspace.equals(Path.of(persistedWorkspace).toAbsolutePath().normalize())) {
                throw new IllegalArgumentException("workspace does not match its storage target");
            }
            Map<String, Object> persisted = MiniJson.asObject(fields.get("selections"));
            if (persisted == null || persisted.size() > Runtime.values().length) throw new IllegalArgumentException("selections are invalid");
            Map<Runtime, Path> selections = new EnumMap<>(Runtime.class);
            for (Map.Entry<String, Object> entry : persisted.entrySet()) {
                Runtime runtime = Runtime.parse(entry.getKey());
                String path = MiniJson.asString(entry.getValue());
                if (runtime == null || path == null || selections.put(runtime, executable(path)) != null) {
                    throw new IllegalArgumentException("selection is invalid");
                }
            }
            return Map.copyOf(selections);
        } catch (RuntimeException error) {
            throw new IOException("Toolchain selection storage is invalid: " + error.getMessage(), error);
        }
    }

    private void save(Path workspace, Map<Runtime, Path> selections) throws IOException {
        Files.createDirectories(directory);
        Map<String, Object> persisted = new LinkedHashMap<>();
        for (Runtime runtime : Runtime.values()) {
            Path executable = selections.get(runtime);
            if (executable != null) persisted.put(runtime.id(), executable.toString());
        }
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("version", VERSION);
        root.put("workspace", workspace.toString());
        root.put("selections", persisted);
        byte[] encoded = MiniJson.stringify(root).getBytes(StandardCharsets.UTF_8);
        if (encoded.length > MAX_BYTES) throw new IOException("Toolchain selections exceed 16 KiB.");
        AtomicFileWriter.write(target(workspace), encoded);
    }

    private static Path executable(String requested) {
        if (requested == null || requested.isBlank()) throw new IllegalArgumentException("toolchain executable is required");
        Path candidate;
        try { candidate = Path.of(requested); }
        catch (RuntimeException error) { throw new IllegalArgumentException("toolchain executable path is invalid"); }
        if (!candidate.isAbsolute()) throw new IllegalArgumentException("toolchain executable must be an absolute path");
        try {
            Path resolved = candidate.toRealPath();
            if (!Files.isRegularFile(resolved) || !Files.isExecutable(resolved)) {
                throw new IllegalArgumentException("toolchain executable is not an executable regular file");
            }
            return resolved;
        } catch (IOException error) {
            throw new IllegalArgumentException("toolchain executable is unavailable");
        }
    }

    private static Path root(Path workspace) {
        if (workspace == null) throw new IllegalArgumentException("workspace is required");
        return workspace.toAbsolutePath().normalize();
    }

    private Path target(Path workspace) { return directory.resolve("toolchains-" + hash(workspace.toString()).substring(0, 16) + ".json"); }

    private static boolean version(Object value) {
        return value instanceof Number number && number.doubleValue() == number.intValue() && number.intValue() == VERSION;
    }

    private static boolean isWindows() { return File.separatorChar == '\\'; }

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
