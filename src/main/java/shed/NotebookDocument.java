package shed;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** A lossless-enough in-memory representation of a Jupyter notebook document. */
final class NotebookDocument {
    record Cell(String type, String source, Map<String, Object> fields) {
        Cell {
            type = "markdown".equals(type) ? "markdown" : "code";
            source = source == null ? "" : source;
            fields = fields == null ? new LinkedHashMap<>() : new LinkedHashMap<>(fields);
        }
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
                if (data != null) text = source(data.get("text/plain"));
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
}
