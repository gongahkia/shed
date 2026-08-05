package shed;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

final class CompletionRanker {
    private record Ranked(LspClient.CompletionItem item, int score, int ordinal) { }

    List<LspClient.CompletionItem> rank(String prefix, List<LspClient.CompletionItem> items, boolean fuzzy, int maxResults) {
        if (items == null || items.isEmpty()) return List.of();
        if (prefix == null || prefix.isBlank()) return limit(items, maxResults);
        FuzzyMatchService matcher = new FuzzyMatchService();
        List<Ranked> ranked = new ArrayList<>();
        for (int index = 0; index < items.size(); index++) {
            LspClient.CompletionItem item = items.get(index);
            String candidate = item.getFilterText().isBlank() ? item.getLabel() : item.getFilterText();
            int score = fuzzy ? matcher.score(prefix, candidate) : prefixMatches(prefix, candidate) ? 1 : 0;
            if (score > 0) ranked.add(new Ranked(item, score, index));
        }
        ranked.sort(Comparator.comparingInt(Ranked::score).reversed()
            .thenComparing(entry -> entry.item().getSortText(), String.CASE_INSENSITIVE_ORDER)
            .thenComparingInt(Ranked::ordinal));
        List<LspClient.CompletionItem> values = new ArrayList<>();
        for (Ranked entry : ranked) values.add(entry.item());
        return limit(values, maxResults);
    }

    private static boolean prefixMatches(String prefix, String candidate) {
        return candidate.regionMatches(true, 0, prefix, 0, prefix.length());
    }

    private static List<LspClient.CompletionItem> limit(List<LspClient.CompletionItem> items, int maxResults) {
        int limit = Math.max(1, maxResults);
        return items.size() <= limit ? List.copyOf(items) : List.copyOf(items.subList(0, limit));
    }
}
