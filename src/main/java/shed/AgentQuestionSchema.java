package shed;

import java.math.BigDecimal;
import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Restricted elicitation forms; no arbitrary JSON Schema evaluation or remote references. */
final class AgentQuestionSchema {
    record Choice(String value, String title) { }
    record Field(String name, String type, String title, String description, boolean required,
                 List<Choice> choices, Object defaultValue, int minimumLength, int maximumLength,
                 BigDecimal minimum, BigDecimal maximum, String format, String pattern) { }
    private final List<Field> fields;
    private AgentQuestionSchema(List<Field> fields) { this.fields = List.copyOf(fields); }
    List<Field> fields() { return fields; }

    static AgentQuestionSchema parse(Object value) {
        var schema = object(value);
        keys(schema, Set.of("type", "properties", "required", "title", "description", "_meta"));
        if (!"object".equals(schema.getOrDefault("type", "object"))) throw invalid("Expected a flat object form");
        var properties = object(schema.getOrDefault("properties", Map.of()));
        if (properties.size() > 64) throw invalid("A form may have at most 64 fields");
        var required = strings(schema.getOrDefault("required", List.of()), 64);
        if (!properties.keySet().containsAll(required) || Set.copyOf(required).size() != required.size()) throw invalid("Invalid required fields");
        List<Field> fields = new ArrayList<>();
        for (var property : properties.entrySet()) {
            String name = bounded(property.getKey(), 256); var source = object(property.getValue());
            String type = bounded(source.get("type"), 16), title = optional(source, "title", name, 1024), description = optional(source, "description", "", 4096);
            var allowed = new java.util.HashSet<>(Set.of("type", "title", "description", "default", "_meta"));
            List<Choice> choices = List.of(); int min = 0, max = 65536; BigDecimal minimum = null, maximum = null; String format = "", pattern = "";
            switch (type) {
                case "string" -> {
                    allowed.addAll(Set.of("enum", "enumNames", "oneOf", "minLength", "maxLength", "format", "pattern"));
                    choices = choices(source, "oneOf"); min = bound(source, "minLength", 0, 65536); max = bound(source, "maxLength", 65536, 65536);
                    format = optional(source, "format", "", 32);
                    if (!Set.of("", "email", "uri", "date", "date-time").contains(format)) throw invalid("Unsupported form format; credentials require an external interaction");
                    pattern = optional(source, "pattern", "", 1024);
                    if (!pattern.isEmpty()) try { java.util.regex.Pattern.compile(pattern); }
                    catch (RuntimeException | StackOverflowError malformed) { throw invalid("Invalid form pattern"); }
                }
                case "number", "integer" -> {
                    allowed.addAll(Set.of("minimum", "maximum")); minimum = decimal(source.get("minimum")); maximum = decimal(source.get("maximum"));
                    if (minimum != null && maximum != null && minimum.compareTo(maximum) > 0) throw invalid("Invalid number range");
                }
                case "boolean" -> { }
                case "array" -> {
                    allowed.addAll(Set.of("items", "minItems", "maxItems")); var items = object(source.get("items"));
                    keys(items, Set.of("type", "enum", "enumNames", "anyOf", "_meta"));
                    if (items.get("type") != null && !"string".equals(items.get("type"))) throw invalid("Only string choices are supported in lists");
                    choices = choices(items, "anyOf"); if (choices.isEmpty()) throw invalid("List fields must declare choices");
                    min = bound(source, "minItems", 0, 128); max = bound(source, "maxItems", 128, 128);
                }
                default -> throw invalid("Unsupported form field type");
            }
            keys(source, allowed); if (min > max) throw invalid("Invalid field length range");
            Object initial = source.get("default");
            if (initial instanceof List<?> list) initial = List.copyOf(list);
            var field = new Field(name, type, title, description, required.contains(name), choices, initial, min, max, minimum, maximum, format, pattern);
            if (initial != null) validate(field, initial);
            fields.add(field);
        }
        return new AgentQuestionSchema(fields);
    }

    Map<String, Object> validate(Map<String, Object> content) {
        if (content == null || content.size() > fields.size()) throw invalid("Form response exceeds its bounds");
        var names = fields.stream().map(Field::name).collect(java.util.stream.Collectors.toSet());
        if (!names.containsAll(content.keySet())) throw invalid("Form response has unknown fields");
        var result = new LinkedHashMap<String, Object>();
        for (var field : fields) {
            if (!content.containsKey(field.name())) { if (field.required()) throw invalid(field.title() + " is required"); continue; }
            Object value = content.get(field.name()); validate(field, value);
            result.put(field.name(), value instanceof List<?> list ? List.copyOf(list) : value);
        }
        if (MiniJson.stringify(result).length() > 65536) throw invalid("Form response exceeds its bounds");
        return java.util.Collections.unmodifiableMap(result);
    }

    private static void validate(Field field, Object value) {
        String failure = "Check the value for " + field.title();
        switch (field.type()) {
            case "string" -> {
                if (!(value instanceof String text) || text.length() > 65536 || text.indexOf('\0') >= 0) throw invalid(failure);
                int length = text.codePointCount(0, text.length());
                if (length < field.minimumLength() || length > field.maximumLength()) throw invalid(failure);
                if (!field.choices().isEmpty() && field.choices().stream().noneMatch(choice -> choice.value().equals(text))) throw invalid(failure);
                try {
                    switch (field.format()) {
                        case "email" -> { if (!text.matches("[^\\s@]+@[^\\s@]+\\.[^\\s@]+")) throw invalid(failure); }
                        case "uri" -> { if (!URI.create(text).isAbsolute()) throw invalid(failure); }
                        case "date" -> java.time.LocalDate.parse(text);
                        case "date-time" -> java.time.OffsetDateTime.parse(text);
                        default -> { }
                    }
                    if (!field.pattern().isEmpty() && !SearchPattern.checkedMatcher(java.util.regex.Pattern.compile(field.pattern()), text, () -> false).find()) throw invalid(failure);
                } catch (RuntimeException | StackOverflowError invalid) { throw invalid(failure); }
            }
            case "number", "integer" -> {
                BigDecimal number = decimal(value);
                if (number == null || field.type().equals("integer") && number.stripTrailingZeros().scale() > 0
                    || field.minimum() != null && number.compareTo(field.minimum()) < 0 || field.maximum() != null && number.compareTo(field.maximum()) > 0) throw invalid(failure);
            }
            case "boolean" -> { if (!(value instanceof Boolean)) throw invalid(failure); }
            case "array" -> {
                var list = strings(value, 128);
                if (list.size() < field.minimumLength() || list.size() > field.maximumLength() || Set.copyOf(list).size() != list.size()
                    || list.stream().anyMatch(item -> field.choices().stream().noneMatch(choice -> choice.value().equals(item)))) throw invalid(failure);
            }
            default -> throw invalid(failure);
        }
    }
    private static List<Choice> choices(Map<String, Object> source, String tagged) {
        if (source.get("enum") != null && source.get(tagged) != null) throw invalid("Ambiguous choice schema");
        List<Choice> result = new ArrayList<>();
        if (source.get("enum") != null) {
            var values = strings(source.get("enum"), 128);
            var titles = source.get("enumNames") == null ? values : strings(source.get("enumNames"), 128);
            if (values.isEmpty() || values.size() != titles.size()) throw invalid("Invalid choice labels");
            for (int i = 0; i < values.size(); i++) result.add(new Choice(values.get(i), titles.get(i)));
        } else if (source.get(tagged) != null) {
            var values = MiniJson.asArray(source.get(tagged));
            if (values == null || values.isEmpty() || values.size() > 128) throw invalid("Invalid choices");
            for (Object item : values) {
                var choice = object(item); keys(choice, Set.of("const", "title", "_meta"));
                String value = bounded(choice.get("const"), 1024);
                result.add(new Choice(value, optional(choice, "title", value, 1024)));
            }
        }
        if (result.stream().map(Choice::value).distinct().count() != result.size()) throw invalid("Duplicate choices");
        return List.copyOf(result);
    }
    private static int bound(Map<String, Object> source, String name, int fallback, int maximum) {
        if (source.get(name) == null) return fallback;
        try { int value = decimal(source.get(name)).intValueExact(); if (value < 0 || value > maximum) throw invalid("Field constraint exceeds its bound"); return value; }
        catch (ArithmeticException invalid) { throw invalid("Invalid field length"); }
    }
    private static BigDecimal decimal(Object value) {
        if (value == null) return null;
        if (!(value instanceof Number) || value.toString().length() > 256) throw invalid("Expected a bounded number");
        try { return new BigDecimal(value.toString()); } catch (NumberFormatException invalid) { throw invalid("Expected a finite number"); }
    }
    private static List<String> strings(Object value, int limit) {
        var values = MiniJson.asArray(value);
        if (values == null || values.size() > limit) throw invalid("Invalid bounded list");
        return values.stream().map(item -> bounded(item, 1024)).toList();
    }
    private static void keys(Map<String, Object> value, Set<String> keys) { if (!keys.containsAll(value.keySet())) throw invalid("Unsupported form schema constraint"); }
    static String bounded(Object value, int limit) {
        if (!(value instanceof String text) || text.length() > limit || text.indexOf('\0') >= 0) throw invalid("Invalid bounded text"); return text;
    }
    private static String optional(Map<String, Object> source, String key, String fallback, int max) { return source.get(key) == null ? fallback : bounded(source.get(key), max); }
    static Map<String, Object> object(Object value) { var result = MiniJson.asObject(value); if (result == null) throw invalid("Expected an object"); return result; }
    static AcpException invalid(String message) { return new AcpException(-32602, message); }
}
