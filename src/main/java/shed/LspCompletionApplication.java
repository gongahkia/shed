package shed;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

final class LspCompletionApplication {
    record Placeholder(int start, int end) {
    }

    record Result(String text, int caret, List<Placeholder> placeholders, boolean fallback) {
    }

    static Result apply(String source, int caret, String prefix, LspClient.CompletionItem item) {
        String safeSource = source == null ? "" : source;
        int safeCaret = Math.max(0, Math.min(caret, safeSource.length()));
        int fallbackStart = Math.max(0, safeCaret - (prefix == null ? 0 : prefix.length()));
        String fallbackText = item == null || item.getLabel() == null ? "" : item.getLabel();
        if (item == null) return new Result(replace(safeSource, fallbackStart, safeCaret, fallbackText), fallbackStart + fallbackText.length(), List.of(), true);
        List<LspClient.CompletionTextEdit> edits = item.getTextEdits();
        if (edits.isEmpty()) return applyInsertion(safeSource, fallbackStart, safeCaret, item.getInsertText(), item.isSnippet(), fallbackText);
        List<ResolvedEdit> resolved = new ArrayList<>();
        for (int i = 0; i < edits.size(); i++) {
            LspClient.CompletionTextEdit edit = edits.get(i);
            int start = offset(safeSource, edit.getStartLine(), edit.getStartCharacter());
            int end = offset(safeSource, edit.getEndLine(), edit.getEndCharacter());
            if (start < 0 || end < start) return new Result(replace(safeSource, fallbackStart, safeCaret, fallbackText), fallbackStart + fallbackText.length(), List.of(), true);
            resolved.add(new ResolvedEdit(start, end, edit.getNewText(), i == 0));
        }
        resolved.sort(Comparator.comparingInt(ResolvedEdit::start));
        for (int i = 1; i < resolved.size(); i++) {
            if (resolved.get(i - 1).end() > resolved.get(i).start()) {
                return new Result(replace(safeSource, fallbackStart, safeCaret, fallbackText), fallbackStart + fallbackText.length(), List.of(), true);
            }
        }
        ResolvedEdit primary = resolved.stream().filter(ResolvedEdit::primary).findFirst().orElse(null);
        if (primary == null) return new Result(replace(safeSource, fallbackStart, safeCaret, fallbackText), fallbackStart + fallbackText.length(), List.of(), true);
        Expansion expansion = item.isSnippet() ? Expansion.parse(primary.text()) : Expansion.plain(primary.text());
        if (expansion == null) return new Result(replace(safeSource, fallbackStart, safeCaret, fallbackText), fallbackStart + fallbackText.length(), List.of(), true);
        StringBuilder updated = new StringBuilder(safeSource);
        int primaryStart = primary.start();
        for (ResolvedEdit edit : resolved) {
            if (!edit.primary() && edit.start() < primary.start()) {
                primaryStart += edit.text().length() - (edit.end() - edit.start());
            }
        }
        for (int i = resolved.size() - 1; i >= 0; i--) {
            ResolvedEdit edit = resolved.get(i);
            String value = edit.primary() ? expansion.text() : edit.text();
            updated.replace(edit.start(), edit.end(), value);
        }
        List<Placeholder> placeholders = new ArrayList<>();
        for (IndexedPlaceholder placeholder : expansion.placeholders()) placeholders.add(new Placeholder(primaryStart + placeholder.start(), primaryStart + placeholder.end()));
        int finalCaret = placeholders.isEmpty() ? primaryStart + expansion.text().length() : placeholders.get(0).start();
        return new Result(updated.toString(), finalCaret, placeholders, false);
    }

    private static Result applyInsertion(String source, int start, int end, String text, boolean snippet, String fallbackText) {
        Expansion expansion = snippet ? Expansion.parse(text) : Expansion.plain(text);
        if (expansion == null) return new Result(replace(source, start, end, fallbackText), start + fallbackText.length(), List.of(), true);
        List<Placeholder> placeholders = new ArrayList<>();
        for (IndexedPlaceholder placeholder : expansion.placeholders()) placeholders.add(new Placeholder(start + placeholder.start(), start + placeholder.end()));
        int caret = placeholders.isEmpty() ? start + expansion.text().length() : placeholders.get(0).start();
        return new Result(replace(source, start, end, expansion.text()), caret, placeholders, false);
    }

    private static int offset(String text, int line, int character) {
        if (line < 0 || character < 0) return -1;
        int cursor = 0;
        for (int currentLine = 0; currentLine < line; currentLine++) {
            int newline = text.indexOf('\n', cursor);
            if (newline < 0) return -1;
            cursor = newline + 1;
        }
        int lineEnd = text.indexOf('\n', cursor);
        if (lineEnd < 0) lineEnd = text.length();
        return cursor + character > lineEnd ? -1 : cursor + character;
    }

    private static String replace(String text, int start, int end, String value) {
        return text.substring(0, start) + value + text.substring(end);
    }

    private record ResolvedEdit(int start, int end, String text, boolean primary) {
    }

    private record IndexedPlaceholder(int index, int start, int end) {
    }

    private record Expansion(String text, List<IndexedPlaceholder> placeholders) {
        static Expansion plain(String text) { return new Expansion(text == null ? "" : text, List.of()); }
        static Expansion parse(String source) {
            String input = source == null ? "" : source;
            StringBuilder text = new StringBuilder();
            List<IndexedPlaceholder> placeholders = new ArrayList<>();
            for (int i = 0; i < input.length(); i++) {
                char c = input.charAt(i);
                if (c == '\\' && i + 1 < input.length()) { text.append(input.charAt(++i)); continue; }
                if (c != '$') { text.append(c); continue; }
                if (i + 1 >= input.length()) return null;
                if (Character.isDigit(input.charAt(i + 1))) {
                    int end = ++i;
                    while (end + 1 < input.length() && Character.isDigit(input.charAt(end + 1))) end++;
                    int index = Integer.parseInt(input.substring(i, end + 1));
                    int start = text.length();
                    placeholders.add(new IndexedPlaceholder(index, start, start));
                    i = end;
                    continue;
                }
                if (input.charAt(i + 1) != '{') return null;
                int close = input.indexOf('}', i + 2);
                if (close < 0) return null;
                String body = input.substring(i + 2, close);
                int colon = body.indexOf(':');
                String indexText = colon < 0 ? body : body.substring(0, colon);
                if (indexText.isEmpty() || !indexText.chars().allMatch(Character::isDigit)) return null;
                int index = Integer.parseInt(indexText);
                String defaultText = colon < 0 ? "" : body.substring(colon + 1);
                int start = text.length();
                text.append(defaultText);
                placeholders.add(new IndexedPlaceholder(index, start, text.length()));
                i = close;
            }
            placeholders.sort(Comparator.comparingInt(placeholder -> placeholder.index() == 0 ? Integer.MAX_VALUE : placeholder.index()));
            return new Expansion(text.toString(), placeholders);
        }
    }
}
