package shed;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

record WorkspaceState(int version, List<String> roots, List<BufferState> buffers, List<PaneState> panes,
                      ActiveSelection activeSelection, List<ToolState> tools) {
    static final int VERSION = 2;
    static final int LEGACY_VERSION = 1;

    WorkspaceState(List<String> roots, List<BufferState> buffers, List<PaneState> panes,
                   ActiveSelection activeSelection, List<ToolState> tools) {
        this(VERSION, roots, buffers, panes, activeSelection, tools);
    }

    WorkspaceState {
        roots = List.copyOf(Objects.requireNonNull(roots, "roots"));
        buffers = List.copyOf(Objects.requireNonNull(buffers, "buffers"));
        panes = List.copyOf(Objects.requireNonNull(panes, "panes"));
        tools = List.copyOf(Objects.requireNonNull(tools, "tools"));
        validate(version, roots, buffers, panes, activeSelection, tools);
    }

    String serialize() {
        return MiniJson.stringify(toMap());
    }

    Map<String, Object> toMap() {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("version", version);
        root.put("roots", roots);
        List<Object> encodedBuffers = new ArrayList<>();
        for (BufferState buffer : buffers) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("id", buffer.id());
            entry.put("type", buffer.kind().wireValue());
            entry.put("path", buffer.path());
            entry.put("name", buffer.name());
            entry.put("modified", buffer.modified());
            entry.put("content", buffer.content());
            if (version == VERSION) {
                entry.put("fileSnapshot", buffer.fileSnapshot() == null ? null : buffer.fileSnapshot().toMap());
            }
            encodedBuffers.add(entry);
        }
        root.put("buffers", encodedBuffers);
        List<Object> encodedPanes = new ArrayList<>();
        for (PaneState pane : panes) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("id", pane.id());
            entry.put("bufferId", pane.bufferId());
            entry.put("caret", pane.caret());
            encodedPanes.add(entry);
        }
        root.put("panes", encodedPanes);
        if (activeSelection == null) {
            root.put("activeSelection", null);
        } else {
            Map<String, Object> active = new LinkedHashMap<>();
            active.put("paneId", activeSelection.paneId());
            active.put("bufferId", activeSelection.bufferId());
            active.put("caret", activeSelection.caret());
            root.put("activeSelection", active);
        }
        List<Object> encodedTools = new ArrayList<>();
        for (ToolState tool : tools) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("id", tool.id());
            entry.put("values", tool.values());
            encodedTools.add(entry);
        }
        root.put("tools", encodedTools);
        return root;
    }

    static WorkspaceState parse(String json) {
        try {
            return fromMap(requireObject(MiniJson.parse(json), "workspace"));
        } catch (RuntimeException error) {
            throw new IllegalArgumentException("workspace format invalid: " + error.getMessage(), error);
        }
    }

    static WorkspaceState fromMap(Map<String, Object> root) {
        requireKeys(root, Set.of("version", "roots", "buffers", "panes", "activeSelection", "tools"), "workspace");
        int version = requireInteger(root.get("version"), "workspace.version");
        if (version != LEGACY_VERSION && version != VERSION) {
            throw new IllegalArgumentException("workspace.version is unsupported");
        }
        List<String> roots = stringList(root.get("roots"), "workspace.roots");
        List<BufferState> buffers = buffers(root.get("buffers"), version);
        List<PaneState> panes = panes(root.get("panes"));
        ActiveSelection active = activeSelection(root.get("activeSelection"));
        List<ToolState> tools = tools(root.get("tools"));
        return new WorkspaceState(version, roots, buffers, panes, active, tools);
    }

    private static List<BufferState> buffers(Object value, int version) {
        List<BufferState> buffers = new ArrayList<>();
        List<Object> entries = requireArray(value, "workspace.buffers");
        for (int index = 0; index < entries.size(); index++) {
            Map<String, Object> entry = requireObject(entries.get(index), "workspace.buffers[" + index + "]");
            Set<String> fields = version == VERSION
                ? Set.of("id", "type", "path", "name", "modified", "content", "fileSnapshot")
                : Set.of("id", "type", "path", "name", "modified", "content");
            requireKeys(entry, fields, "workspace.buffers[" + index + "]");
            buffers.add(new BufferState(
                requireString(entry.get("id"), "workspace.buffers[" + index + "].id"),
                BufferKind.fromWireValue(requireString(entry.get("type"), "workspace.buffers[" + index + "].type")),
                nullableString(entry.get("path"), "workspace.buffers[" + index + "].path"),
                nullableString(entry.get("name"), "workspace.buffers[" + index + "].name"),
                requireBoolean(entry.get("modified"), "workspace.buffers[" + index + "].modified"),
                nullableString(entry.get("content"), "workspace.buffers[" + index + "].content"),
                version == VERSION ? fileSnapshot(entry.get("fileSnapshot"), "workspace.buffers[" + index + "].fileSnapshot") : null
            ));
        }
        return buffers;
    }

    private static List<PaneState> panes(Object value) {
        List<PaneState> panes = new ArrayList<>();
        List<Object> entries = requireArray(value, "workspace.panes");
        for (int index = 0; index < entries.size(); index++) {
            Map<String, Object> entry = requireObject(entries.get(index), "workspace.panes[" + index + "]");
            requireKeys(entry, Set.of("id", "bufferId", "caret"), "workspace.panes[" + index + "]");
            panes.add(new PaneState(
                requireString(entry.get("id"), "workspace.panes[" + index + "].id"),
                requireString(entry.get("bufferId"), "workspace.panes[" + index + "].bufferId"),
                requireNonNegativeInteger(entry.get("caret"), "workspace.panes[" + index + "].caret")
            ));
        }
        return panes;
    }

    private static ActiveSelection activeSelection(Object value) {
        if (value == null) {
            return null;
        }
        Map<String, Object> entry = requireObject(value, "workspace.activeSelection");
        requireKeys(entry, Set.of("paneId", "bufferId", "caret"), "workspace.activeSelection");
        return new ActiveSelection(
            requireString(entry.get("paneId"), "workspace.activeSelection.paneId"),
            requireString(entry.get("bufferId"), "workspace.activeSelection.bufferId"),
            requireNonNegativeInteger(entry.get("caret"), "workspace.activeSelection.caret")
        );
    }

    private static List<ToolState> tools(Object value) {
        List<ToolState> tools = new ArrayList<>();
        List<Object> entries = requireArray(value, "workspace.tools");
        for (int index = 0; index < entries.size(); index++) {
            Map<String, Object> entry = requireObject(entries.get(index), "workspace.tools[" + index + "]");
            requireKeys(entry, Set.of("id", "values"), "workspace.tools[" + index + "]");
            Map<String, Object> values = requireObject(entry.get("values"), "workspace.tools[" + index + "].values");
            Map<String, String> typedValues = new LinkedHashMap<>();
            for (Map.Entry<String, Object> valueEntry : values.entrySet()) {
                typedValues.put(requireIdentifier(valueEntry.getKey(), "workspace.tools[" + index + "].values key"),
                    requireString(valueEntry.getValue(), "workspace.tools[" + index + "].values." + valueEntry.getKey()));
            }
            tools.add(new ToolState(requireIdentifier(requireString(entry.get("id"), "workspace.tools[" + index + "].id"),
                "workspace.tools[" + index + "].id"), typedValues));
        }
        return tools;
    }

    private static FileSnapshot fileSnapshot(Object value, String field) {
        if (value == null) {
            return null;
        }
        Map<String, Object> fields = requireObject(value, field);
        requireKeys(fields, Set.of("fileKey", "createdAtMillis", "modifiedAtMillis", "size", "sha256"), field);
        return new FileSnapshot(
            nullableString(fields.get("fileKey"), field + ".fileKey"),
            requireNonNegativeLong(fields.get("createdAtMillis"), field + ".createdAtMillis"),
            requireNonNegativeLong(fields.get("modifiedAtMillis"), field + ".modifiedAtMillis"),
            requireNonNegativeLong(fields.get("size"), field + ".size"),
            requireString(fields.get("sha256"), field + ".sha256")
        );
    }

    private static void validate(int version, List<String> roots, List<BufferState> buffers, List<PaneState> panes,
                                 ActiveSelection active, List<ToolState> tools) {
        if (version != LEGACY_VERSION && version != VERSION) {
            throw new IllegalArgumentException("workspace.version is unsupported");
        }
        Set<String> rootPaths = new HashSet<>();
        for (String root : roots) {
            requireAbsolutePath(root, "workspace root");
            if (!rootPaths.add(root)) {
                throw new IllegalArgumentException("workspace roots must be unique");
            }
        }
        Set<String> bufferIds = new HashSet<>();
        for (BufferState buffer : buffers) {
            requireIdentifier(buffer.id(), "buffer id");
            if (!bufferIds.add(buffer.id())) {
                throw new IllegalArgumentException("buffer ids must be unique");
            }
            if (buffer.kind() == BufferKind.FILE) {
                requireAbsolutePath(buffer.path(), "file buffer path");
                if (buffer.name() != null || (buffer.modified() && buffer.content() == null) || (!buffer.modified() && buffer.content() != null)) {
                    throw new IllegalArgumentException("file buffer content and name do not match its modified state");
                }
                if (version == VERSION && buffer.fileSnapshot() == null) {
                    throw new IllegalArgumentException("version 2 file buffers require a file snapshot");
                }
                if (version == LEGACY_VERSION && buffer.fileSnapshot() != null) {
                    throw new IllegalArgumentException("version 1 file buffers cannot contain a file snapshot");
                }
            } else {
                if (buffer.path() != null || buffer.name() == null || buffer.name().isBlank() || buffer.content() == null || buffer.fileSnapshot() != null) {
                    throw new IllegalArgumentException("scratch buffers require name and content only");
                }
            }
        }
        Set<String> paneIds = new HashSet<>();
        for (PaneState pane : panes) {
            requireIdentifier(pane.id(), "pane id");
            if (!paneIds.add(pane.id()) || !bufferIds.contains(pane.bufferId()) || pane.caret() < 0) {
                throw new IllegalArgumentException("panes require unique ids, existing buffers, and non-negative carets");
            }
        }
        if (buffers.isEmpty() != panes.isEmpty()) {
            throw new IllegalArgumentException("empty workspaces cannot contain panes");
        }
        if (buffers.isEmpty() != (active == null)) {
            throw new IllegalArgumentException("active selection must match workspace emptiness");
        }
        if (active != null) {
            PaneState activePane = panes.stream().filter(pane -> pane.id().equals(active.paneId())).findFirst().orElse(null);
            if (activePane == null || !activePane.bufferId().equals(active.bufferId()) || active.caret() != activePane.caret()) {
                throw new IllegalArgumentException("active selection must reference its pane buffer and non-negative caret");
            }
        }
        Set<String> toolIds = new HashSet<>();
        for (ToolState tool : tools) {
            requireIdentifier(tool.id(), "tool id");
            if (!toolIds.add(tool.id())) {
                throw new IllegalArgumentException("tool ids must be unique");
            }
            for (Map.Entry<String, String> entry : tool.values().entrySet()) {
                requireIdentifier(entry.getKey(), "tool state key");
                Objects.requireNonNull(entry.getValue(), "tool state value");
            }
        }
    }

    private static Map<String, Object> requireObject(Object value, String field) {
        Map<String, Object> object = MiniJson.asObject(value);
        if (object == null) {
            throw new IllegalArgumentException(field + " must be an object");
        }
        return object;
    }

    private static List<Object> requireArray(Object value, String field) {
        List<Object> array = MiniJson.asArray(value);
        if (array == null) {
            throw new IllegalArgumentException(field + " must be an array");
        }
        return array;
    }

    private static List<String> stringList(Object value, String field) {
        List<String> values = new ArrayList<>();
        for (Object item : requireArray(value, field)) {
            values.add(requireString(item, field + " entry"));
        }
        return values;
    }

    private static void requireKeys(Map<String, Object> values, Set<String> expected, String field) {
        if (!values.keySet().equals(expected)) {
            throw new IllegalArgumentException(field + " has unsupported or missing fields");
        }
    }

    private static String requireString(Object value, String field) {
        if (!(value instanceof String)) {
            throw new IllegalArgumentException(field + " must be a string");
        }
        return (String) value;
    }

    private static String nullableString(Object value, String field) {
        return value == null ? null : requireString(value, field);
    }

    private static boolean requireBoolean(Object value, String field) {
        if (!(value instanceof Boolean)) {
            throw new IllegalArgumentException(field + " must be boolean");
        }
        return (Boolean) value;
    }

    private static int requireInteger(Object value, String field) {
        if (value instanceof Integer) {
            return (Integer) value;
        }
        if (!(value instanceof Long) || (Long) value > Integer.MAX_VALUE || (Long) value < Integer.MIN_VALUE) {
            throw new IllegalArgumentException(field + " must be an integer");
        }
        return ((Long) value).intValue();
    }

    private static int requireNonNegativeInteger(Object value, String field) {
        int number = requireInteger(value, field);
        if (number < 0) {
            throw new IllegalArgumentException(field + " must be non-negative");
        }
        return number;
    }

    private static long requireNonNegativeLong(Object value, String field) {
        long number;
        if (value instanceof Long) {
            number = (Long) value;
        } else if (value instanceof Integer) {
            number = (Integer) value;
        } else {
            throw new IllegalArgumentException(field + " must be an integer");
        }
        if (number < 0) {
            throw new IllegalArgumentException(field + " must be non-negative");
        }
        return number;
    }

    private static String requireIdentifier(String value, String field) {
        if (value == null || !value.matches("[A-Za-z0-9._-]+")) {
            throw new IllegalArgumentException(field + " must use letters, digits, dot, underscore, or hyphen");
        }
        return value;
    }

    private static void requireAbsolutePath(String value, String field) {
        if (value == null || value.isBlank() || !Path.of(value).isAbsolute()) {
            throw new IllegalArgumentException(field + " must be an absolute path");
        }
    }

    enum BufferKind {
        FILE("file"),
        SCRATCH("scratch");

        private final String wireValue;

        BufferKind(String wireValue) {
            this.wireValue = wireValue;
        }

        String wireValue() {
            return wireValue;
        }

        static BufferKind fromWireValue(String value) {
            for (BufferKind kind : values()) {
                if (kind.wireValue.equals(value)) {
                    return kind;
                }
            }
            throw new IllegalArgumentException("buffer type is unsupported");
        }
    }

    record BufferState(String id, BufferKind kind, String path, String name, boolean modified, String content, FileSnapshot fileSnapshot) {
        BufferState(String id, BufferKind kind, String path, String name, boolean modified, String content) {
            this(id, kind, path, name, modified, content, null);
        }
    }

    record FileSnapshot(String fileKey, long createdAtMillis, long modifiedAtMillis, long size, String sha256) {
        FileSnapshot {
            if (fileKey != null && fileKey.isBlank()) {
                throw new IllegalArgumentException("file snapshot key must not be blank");
            }
            if (createdAtMillis < 0 || modifiedAtMillis < 0 || size < 0 || sha256 == null || !sha256.matches("[0-9a-f]{64}")) {
                throw new IllegalArgumentException("file snapshot is invalid");
            }
        }

        static FileSnapshot capture(Path path) throws IOException {
            Path resolved = Objects.requireNonNull(path, "path").toAbsolutePath().normalize();
            BasicFileAttributes before = readRegularAttributes(resolved);
            String sha256 = sha256(resolved);
            BasicFileAttributes after = readRegularAttributes(resolved);
            if (!sameMetadata(before, after)) {
                throw new IOException("file changed while capturing workspace state: " + resolved);
            }
            return fromAttributes(after, sha256);
        }

        Map<String, Object> toMap() {
            Map<String, Object> fields = new LinkedHashMap<>();
            fields.put("fileKey", fileKey);
            fields.put("createdAtMillis", createdAtMillis);
            fields.put("modifiedAtMillis", modifiedAtMillis);
            fields.put("size", size);
            fields.put("sha256", sha256);
            return fields;
        }

        private static FileSnapshot fromAttributes(BasicFileAttributes attributes, String sha256) {
            Object key = attributes.fileKey();
            return new FileSnapshot(key == null ? null : key.toString(), attributes.creationTime().toMillis(),
                attributes.lastModifiedTime().toMillis(), attributes.size(), sha256);
        }

        private static BasicFileAttributes readRegularAttributes(Path path) throws IOException {
            BasicFileAttributes attributes = Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            if (!attributes.isRegularFile() || attributes.isSymbolicLink()) {
                throw new IOException("workspace file is not a regular file: " + path);
            }
            return attributes;
        }

        private static boolean sameMetadata(BasicFileAttributes first, BasicFileAttributes second) {
            return Objects.equals(first.fileKey(), second.fileKey())
                && first.creationTime().equals(second.creationTime())
                && first.lastModifiedTime().equals(second.lastModifiedTime())
                && first.size() == second.size();
        }

        private static String sha256(Path path) throws IOException {
            try (InputStream input = Files.newInputStream(path)) {
                MessageDigest digest = MessageDigest.getInstance("SHA-256");
                byte[] buffer = new byte[8192];
                int read;
                while ((read = input.read(buffer)) != -1) {
                    digest.update(buffer, 0, read);
                }
                return java.util.HexFormat.of().formatHex(digest.digest());
            } catch (NoSuchAlgorithmException error) {
                throw new IOException("SHA-256 is unavailable", error);
            }
        }
    }

    record PaneState(String id, String bufferId, int caret) {
    }

    record ActiveSelection(String paneId, String bufferId, int caret) {
    }

    record ToolState(String id, Map<String, String> values) {
        ToolState {
            values = Collections.unmodifiableMap(new LinkedHashMap<>(Objects.requireNonNull(values, "values")));
        }
    }
}
