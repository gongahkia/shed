package shed;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/** Parses the portable TextMate subset used by VS Code and LSP snippets. */
final class SnippetExpansion {
    record Placeholder(int index, int start, int end) {}
    record Result(String text, List<Placeholder> placeholders) {}

    private SnippetExpansion() {}

    static Result parse(String source, Map<String, String> variables) {
        String input = source == null ? "" : source;
        Map<String, String> values = variables == null ? Map.of() : variables;
        StringBuilder text = new StringBuilder();
        List<Placeholder> placeholders = new ArrayList<>();
        for (int index = 0; index < input.length(); index++) {
            char current = input.charAt(index);
            if (current == '\\' && index + 1 < input.length()) {
                text.append(input.charAt(++index));
                continue;
            }
            if (current != '$') {
                text.append(current);
                continue;
            }
            if (index + 1 >= input.length()) return null;
            char next = input.charAt(index + 1);
            if (Character.isDigit(next)) {
                int end = index + 1;
                while (end + 1 < input.length() && Character.isDigit(input.charAt(end + 1))) end++;
                addPlaceholder(placeholders, input.substring(index + 1, end + 1), "", text);
                index = end;
                continue;
            }
            if (next == '{') {
                int close = closingBrace(input, index + 2);
                if (close < 0) return null;
                if (!appendBraced(input.substring(index + 2, close), values, text, placeholders)) return null;
                index = close;
                continue;
            }
            if (!isVariableStart(next)) return null;
            int end = index + 1;
            while (end + 1 < input.length() && isVariablePart(input.charAt(end + 1))) end++;
            String variable = input.substring(index + 1, end + 1);
            text.append(values.getOrDefault(variable, ""));
            index = end;
        }
        placeholders.sort(Comparator.comparingInt(placeholder -> placeholder.index() == 0 ? Integer.MAX_VALUE : placeholder.index()));
        return new Result(text.toString(), List.copyOf(placeholders));
    }

    private static boolean appendBraced(String body, Map<String, String> variables, StringBuilder text, List<Placeholder> placeholders) {
        int colon = body.indexOf(':');
        int choice = body.indexOf('|');
        int separator = colon >= 0 ? colon : choice;
        String identifier = separator < 0 ? body : body.substring(0, separator);
        if (identifier.isEmpty()) return false;
        if (identifier.chars().allMatch(Character::isDigit)) {
            String defaultText = "";
            if (colon >= 0) defaultText = body.substring(colon + 1);
            else if (choice >= 0 && body.endsWith("|")) {
                String choices = body.substring(choice + 1, body.length() - 1);
                int comma = choices.indexOf(',');
                defaultText = comma < 0 ? choices : choices.substring(0, comma);
            } else if (choice >= 0) return false;
            addPlaceholder(placeholders, identifier, defaultText, text);
            return true;
        }
        if (!identifier.chars().allMatch(SnippetExpansion::isVariablePart)) return false;
        String fallback = colon >= 0 ? body.substring(colon + 1) : "";
        text.append(variables.getOrDefault(identifier, fallback));
        return true;
    }

    private static void addPlaceholder(List<Placeholder> placeholders, String indexText, String defaultText, StringBuilder text) {
        int index = Integer.parseInt(indexText);
        int start = text.length();
        text.append(defaultText);
        placeholders.add(new Placeholder(index, start, text.length()));
    }

    private static int closingBrace(String input, int start) {
        int depth = 0;
        for (int index = start; index < input.length(); index++) {
            char current = input.charAt(index);
            if (current == '\\') {
                index++;
                continue;
            }
            if (current == '{') depth++;
            else if (current == '}') {
                if (depth == 0) return index;
                depth--;
            }
        }
        return -1;
    }

    private static boolean isVariableStart(char current) {
        return Character.isLetter(current) || current == '_';
    }

    private static boolean isVariablePart(char current) {
        return Character.isLetterOrDigit(current) || current == '_';
    }
}
