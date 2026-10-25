package shed;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/** An immutable, normalized text snapshot. Offsets count UTF-16 code units; versions are opaque. */
public record WorkspaceDocument(String workspaceId, String documentId, String uri, String version,
                                String content, boolean dirty, boolean readOnly, boolean complete,
                                String encoding, String lineEnding, boolean byteOrderMark, Source source) {
    public enum Source { EDITOR, DISK }

    public WorkspaceDocument {
        for (String field : new String[] {workspaceId, documentId, version, encoding, lineEnding})
            if (field == null || field.isBlank()) throw new IllegalArgumentException("Snapshot identity and format are required");
        content = Objects.requireNonNull(content);
        source = Objects.requireNonNull(source);
    }

    public Map<String, Object> metadata() {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("workspaceId", workspaceId); value.put("documentId", documentId); value.put("uri", uri);
        value.put("version", version); value.put("dirty", dirty); value.put("readOnly", readOnly);
        value.put("complete", complete); value.put("encoding", encoding); value.put("lineEnding", lineEnding);
        value.put("byteOrderMark", byteOrderMark); value.put("source", source.name());
        value.put("length", content.length()); value.put("offsetEncoding", "utf-16");
        return java.util.Collections.unmodifiableMap(value);
    }
}
