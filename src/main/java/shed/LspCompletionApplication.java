package shed;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.Map;
import java.util.List;

final class LspCompletionApplication {
    record Result(String text, int caret, List<SnippetExpansion.Placeholder> placeholders, boolean fallback) {
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
        SnippetExpansion.Result expansion = item.isSnippet() ? SnippetExpansion.parse(primary.text(), Map.of()) : plain(primary.text());
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
        List<SnippetExpansion.Placeholder> placeholders = offsetPlaceholders(primaryStart, expansion.placeholders());
        int finalCaret = placeholders.isEmpty() ? primaryStart + expansion.text().length() : placeholders.get(0).start();
        return new Result(updated.toString(), finalCaret, placeholders, false);
    }

    private static Result applyInsertion(String source, int start, int end, String text, boolean snippet, String fallbackText) {
        SnippetExpansion.Result expansion = snippet ? SnippetExpansion.parse(text, Map.of()) : plain(text);
        if (expansion == null) return new Result(replace(source, start, end, fallbackText), start + fallbackText.length(), List.of(), true);
        List<SnippetExpansion.Placeholder> placeholders = offsetPlaceholders(start, expansion.placeholders());
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

    private static SnippetExpansion.Result plain(String text) {
        return new SnippetExpansion.Result(text == null ? "" : text, List.of());
    }

    private static List<SnippetExpansion.Placeholder> offsetPlaceholders(int baseOffset, List<SnippetExpansion.Placeholder> source) {
        List<SnippetExpansion.Placeholder> placeholders = new ArrayList<>();
        for (SnippetExpansion.Placeholder placeholder : source) {
            placeholders.add(new SnippetExpansion.Placeholder(placeholder.index(), baseOffset + placeholder.start(), baseOffset + placeholder.end()));
        }
        return List.copyOf(placeholders);
    }
}
