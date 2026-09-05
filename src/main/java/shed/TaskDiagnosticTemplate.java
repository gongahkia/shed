package shed;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/** A bounded, literal-delimited task diagnostic format that never evaluates workspace-supplied regexes. */
final class TaskDiagnosticTemplate {
    enum Field {
        FILE("file"),
        LINE("line"),
        COLUMN("column"),
        SEVERITY("severity"),
        MESSAGE("message");

        private final String token;

        Field(String token) {
            this.token = token;
        }

        String token() {
            return token;
        }
    }

    private sealed interface Part permits Literal, Capture {
    }

    private record Literal(String value) implements Part {
    }

    private record Capture(Field field) implements Part {
    }

    private static final int MAX_LENGTH = 512;
    private final String source;
    private final List<Part> parts;

    private TaskDiagnosticTemplate(String source, List<Part> parts) {
        this.source = source;
        this.parts = List.copyOf(parts);
    }

    static TaskDiagnosticTemplate parse(String value) {
        if (value == null || value.isBlank() || value.length() > MAX_LENGTH || value.indexOf('\0') >= 0
            || value.indexOf('\n') >= 0 || value.indexOf('\r') >= 0) {
            throw new IllegalArgumentException("problem_pattern must be a non-empty single line of at most " + MAX_LENGTH + " characters");
        }
        List<Part> parts = new ArrayList<>();
        java.util.EnumSet<Field> fields = java.util.EnumSet.noneOf(Field.class);
        int position = 0;
        while (position < value.length()) {
            int start = value.indexOf("{{", position);
            if (start < 0) {
                addLiteral(parts, value.substring(position));
                break;
            }
            if (start > position) addLiteral(parts, value.substring(position, start));
            int end = value.indexOf("}}", start + 2);
            if (end < 0) throw new IllegalArgumentException("problem_pattern has an unterminated placeholder");
            String token = value.substring(start + 2, end);
            Field field = field(token);
            if (field == null) throw new IllegalArgumentException("problem_pattern has an unsupported placeholder: " + token);
            if (!fields.add(field)) throw new IllegalArgumentException("problem_pattern repeats placeholder: " + token);
            parts.add(new Capture(field));
            position = end + 2;
        }
        if (parts.isEmpty() || !(parts.getLast() instanceof Capture capture) || capture.field() != Field.MESSAGE) {
            throw new IllegalArgumentException("problem_pattern must end with {{message}}");
        }
        if (!fields.containsAll(java.util.EnumSet.of(Field.FILE, Field.LINE, Field.MESSAGE))) {
            throw new IllegalArgumentException("problem_pattern requires {{file}}, {{line}}, and {{message}}");
        }
        for (int index = 0; index + 1 < parts.size(); index++) {
            if (parts.get(index) instanceof Capture && (!(parts.get(index + 1) instanceof Literal literal) || literal.value().isEmpty())) {
                throw new IllegalArgumentException("each placeholder before {{message}} requires a literal delimiter");
            }
        }
        return new TaskDiagnosticTemplate(value, parts);
    }

    String source() {
        return source;
    }

    Map<Field, String> match(String line) {
        if (line == null) return Map.of();
        Map<Field, String> result = new EnumMap<>(Field.class);
        int position = 0;
        for (int index = 0; index < parts.size(); index++) {
            Part part = parts.get(index);
            if (part instanceof Literal literal) {
                if (!line.startsWith(literal.value(), position)) return Map.of();
                position += literal.value().length();
                continue;
            }
            Capture capture = (Capture) part;
            if (capture.field() == Field.MESSAGE) {
                result.put(capture.field(), line.substring(position));
                position = line.length();
                continue;
            }
            Literal separator = (Literal) parts.get(index + 1);
            int end = line.indexOf(separator.value(), position);
            if (end < 0) return Map.of();
            result.put(capture.field(), line.substring(position, end));
            position = end;
        }
        return position == line.length() ? Map.copyOf(result) : Map.of();
    }

    private static void addLiteral(List<Part> parts, String value) {
        if (value == null || value.isEmpty()) return;
        if (value.contains("}}")) throw new IllegalArgumentException("problem_pattern has an unexpected }}");
        parts.add(new Literal(value));
    }

    private static Field field(String token) {
        for (Field field : Field.values()) if (field.token().equals(token)) return field;
        return null;
    }
}
