package shed;

import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** A lossless-enough in-memory representation of a Jupyter notebook document. */
final class NotebookDocument {
    private static final int MAX_IMAGES_PER_CELL = 16;
    private static final int MAX_IMAGE_BYTES = 4 * 1024 * 1024;
    private static final int MAX_ENCODED_IMAGE_CHARACTERS = (MAX_IMAGE_BYTES * 4 / 3) + 8;
    private static final int MAX_MARKDOWN_OUTPUTS_PER_CELL = 16;
    private static final int MAX_MARKDOWN_OUTPUT_CHARACTERS = 128 * 1024;
    private static final int MAX_JSON_OUTPUT_CHARACTERS = 128 * 1024;

    record Cell(String type, String source, Map<String, Object> fields) {
        Cell {
            type = "markdown".equals(type) ? "markdown" : "code";
            source = source == null ? "" : source;
            fields = fields == null ? new LinkedHashMap<>() : new LinkedHashMap<>(fields);
        }
    }

    record ImageOutput(String mimeType, byte[] bytes) {
        ImageOutput {
            mimeType = mimeType == null ? "" : mimeType;
            bytes = bytes == null ? new byte[0] : bytes.clone();
        }

        @Override public byte[] bytes() { return bytes.clone(); }
    }

    record MarkdownOutput(String markdown) {
        MarkdownOutput { markdown = markdown == null ? "" : markdown; }
    }

    private final Map<String, Object> root;
    private final List<Cell> cells;

    private NotebookDocument(Map<String, Object> root, List<Cell> cells) {
        this.root = new LinkedHashMap<>(root);
        this.cells = new ArrayList<>(cells);
    }

    static NotebookDocument parse(String text) {
        Map<String, Object> parsed = MiniJson.asObject(MiniJson.parse(text));
        if (parsed == null) throw new IllegalArgumentException("notebook root must be a JSON object");
        List<Object> rawCells = MiniJson.asArray(parsed.get("cells"));
        if (rawCells == null) throw new IllegalArgumentException("notebook cells must be a JSON array");
        List<Cell> cells = new ArrayList<>();
        for (Object raw : rawCells) {
            Map<String, Object> fields = MiniJson.asObject(raw);
            if (fields == null) throw new IllegalArgumentException("notebook cell must be a JSON object");
            String type = MiniJson.asString(fields.get("cell_type"));
            if (!"code".equals(type) && !"markdown".equals(type)) {
                throw new IllegalArgumentException("unsupported notebook cell type: " + (type == null ? "missing" : type));
            }
            cells.add(new Cell(type, source(fields.get("source")), fields));
        }
        return new NotebookDocument(parsed, cells);
    }

    static NotebookDocument empty() {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("nbformat", 4L);
        root.put("nbformat_minor", 5L);
        root.put("metadata", new LinkedHashMap<>());
        return new NotebookDocument(root, List.of());
    }

    List<Cell> cells() {
        return List.copyOf(cells);
    }

    NotebookDocument withCells(List<Cell> replacement) {
        return new NotebookDocument(root, replacement == null ? List.of() : replacement);
    }

    /** Returns the standard Jupyter kernelspec name when it is safe to pass as one direct argv value. */
    String kernelName() {
        Map<String, Object> metadata = MiniJson.asObject(root.get("metadata"));
        Map<String, Object> kernelspec = MiniJson.asObject(metadata == null ? null : metadata.get("kernelspec"));
        String name = MiniJson.asString(kernelspec == null ? null : kernelspec.get("name"));
        return validKernelName(name) ? name.trim() : "";
    }

    /** Persists a selected installed kernelspec without discarding other notebook metadata. */
    NotebookDocument withKernelSpec(String name, String displayName, String language) {
        String selected = normalizedKernelName(name);
        Map<String, Object> updatedRoot = new LinkedHashMap<>(root);
        Map<String, Object> metadata = copyMap(MiniJson.asObject(updatedRoot.get("metadata")));
        Map<String, Object> kernelspec = copyMap(MiniJson.asObject(metadata.get("kernelspec")));
        kernelspec.put("name", selected);
        if (displayName != null && !displayName.isBlank()) kernelspec.put("display_name", displayName.trim());
        if (language != null && !language.isBlank()) kernelspec.put("language", language.trim());
        metadata.put("kernelspec", kernelspec);
        updatedRoot.put("metadata", metadata);
        return new NotebookDocument(updatedRoot, cells);
    }

    /** Returns a standalone notebook containing cells through the requested one-based index. */
    NotebookDocument through(int cellCount) {
        if (cellCount < 1 || cellCount > cells.size()) throw new IllegalArgumentException("notebook cell index is unavailable");
        return new NotebookDocument(root, cells.subList(0, cellCount));
    }

    /** Replaces an executed one-based cell prefix while retaining the unexecuted cells. */
    NotebookDocument withExecutedPrefix(NotebookDocument executed, int cellCount) {
        if (executed == null || cellCount < 1 || cellCount > cells.size() || executed.cells.size() != cellCount) {
            throw new IllegalArgumentException("executed notebook prefix does not match this notebook");
        }
        List<Cell> replacement = new ArrayList<>(cells);
        for (int index = 0; index < cellCount; index++) replacement.set(index, executed.cells.get(index));
        return new NotebookDocument(root, replacement);
    }

    String serialize() {
        Map<String, Object> result = new LinkedHashMap<>(root);
        List<Object> serialized = new ArrayList<>();
        for (Cell cell : cells) {
            Map<String, Object> fields = new LinkedHashMap<>(cell.fields());
            fields.put("cell_type", cell.type());
            fields.put("source", cell.source());
            if ("code".equals(cell.type())) {
                fields.putIfAbsent("execution_count", null);
                fields.putIfAbsent("outputs", List.of());
            } else {
                fields.remove("execution_count");
                fields.remove("outputs");
            }
            serialized.add(fields);
        }
        result.put("cells", serialized);
        result.putIfAbsent("nbformat", 4L);
        result.putIfAbsent("nbformat_minor", 5L);
        result.putIfAbsent("metadata", new LinkedHashMap<>());
        return MiniJson.stringify(result) + "\n";
    }

    static String outputText(Cell cell) {
        if (cell == null || !"code".equals(cell.type())) return "";
        List<Object> outputs = MiniJson.asArray(cell.fields().get("outputs"));
        if (outputs == null || outputs.isEmpty()) return "";
        StringBuilder result = new StringBuilder();
        for (Object raw : outputs) {
            Map<String, Object> output = MiniJson.asObject(raw);
            if (output == null) continue;
            String text = source(output.get("text"));
            if (text.isBlank()) {
                Map<String, Object> data = MiniJson.asObject(output.get("data"));
                if (data != null) {
                    text = source(data.get("text/plain"));
                    if (text.isBlank() && data.containsKey("application/json")) text = jsonText(data.get("application/json"));
                }
            }
            if (text.isBlank()) {
                String name = MiniJson.asString(output.get("ename"));
                String value = MiniJson.asString(output.get("evalue"));
                if (name != null || value != null) text = (name == null ? "" : name) + (value == null ? "" : ": " + value);
            }
            if (!text.isBlank()) {
                if (!result.isEmpty()) result.append('\n');
                result.append(text);
            }
        }
        return result.toString();
    }

    private static String jsonText(Object value) {
        try {
            String serialized = MiniJson.stringify(value);
            return serialized.length() > MAX_JSON_OUTPUT_CHARACTERS ? "" : serialized;
        } catch (RuntimeException ignored) {
            return "";
        }
    }

    /** Returns bounded PNG/JPEG display data only; HTML, SVG, and scriptable MIME outputs stay inert. */
    static List<ImageOutput> imageOutputs(Cell cell) {
        if (cell == null || !"code".equals(cell.type())) return List.of();
        List<Object> outputs = MiniJson.asArray(cell.fields().get("outputs"));
        if (outputs == null || outputs.isEmpty()) return List.of();
        List<ImageOutput> result = new ArrayList<>();
        for (Object raw : outputs) {
            if (result.size() >= MAX_IMAGES_PER_CELL) break;
            Map<String, Object> output = MiniJson.asObject(raw);
            if (output == null) continue;
            Map<String, Object> data = MiniJson.asObject(output.get("data"));
            if (data == null) continue;
            addImage(result, "image/png", source(data.get("image/png")));
            if (result.size() < MAX_IMAGES_PER_CELL) addImage(result, "image/jpeg", source(data.get("image/jpeg")));
        }
        return List.copyOf(result);
    }

    /** Returns bounded text/markdown display data; raw HTML remains sanitised by the view renderer. */
    static List<MarkdownOutput> markdownOutputs(Cell cell) {
        if (cell == null || !"code".equals(cell.type())) return List.of();
        List<Object> outputs = MiniJson.asArray(cell.fields().get("outputs"));
        if (outputs == null || outputs.isEmpty()) return List.of();
        List<MarkdownOutput> result = new ArrayList<>();
        for (Object raw : outputs) {
            if (result.size() >= MAX_MARKDOWN_OUTPUTS_PER_CELL) break;
            Map<String, Object> output = MiniJson.asObject(raw);
            Map<String, Object> data = MiniJson.asObject(output == null ? null : output.get("data"));
            String markdown = source(data == null ? null : data.get("text/markdown"));
            if (markdown.isBlank() || markdown.length() > MAX_MARKDOWN_OUTPUT_CHARACTERS) continue;
            result.add(new MarkdownOutput(markdown));
        }
        return List.copyOf(result);
    }

    private static void addImage(List<ImageOutput> results, String mimeType, String encoded) {
        if (encoded == null || encoded.isBlank() || encoded.length() > MAX_ENCODED_IMAGE_CHARACTERS) return;
        String compact = encoded.replaceAll("\\s+", "");
        if (compact.length() > MAX_ENCODED_IMAGE_CHARACTERS) return;
        try {
            byte[] bytes = Base64.getDecoder().decode(compact);
            if (bytes.length > 0 && bytes.length <= MAX_IMAGE_BYTES) results.add(new ImageOutput(mimeType, bytes));
        } catch (IllegalArgumentException ignored) {
            // A malformed display payload remains an inert notebook value.
        }
    }

    private static String source(Object value) {
        String single = MiniJson.asString(value);
        if (single != null) return single;
        List<Object> parts = MiniJson.asArray(value);
        if (parts == null) return "";
        StringBuilder result = new StringBuilder();
        for (Object part : parts) {
            String text = MiniJson.asString(part);
            if (text != null) result.append(text);
        }
        return result.toString();
    }

    private static boolean validKernelName(String name) {
        return name != null && name.trim().matches("[A-Za-z0-9._-]+");
    }

    private static String normalizedKernelName(String name) {
        if (!validKernelName(name)) throw new IllegalArgumentException("kernel name is invalid");
        return name.trim();
    }

    private static Map<String, Object> copyMap(Map<String, Object> value) {
        return value == null ? new LinkedHashMap<>() : new LinkedHashMap<>(value);
    }
}
