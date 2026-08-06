package shed;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class OpenBufferCompletionIndex {
    record Candidate(String word, int score) { }

    record WordStats(int count, int lastOffset) {
        WordStats next(int offset) { return new WordStats(count + 1, offset); }
    }

    private final Map<Object, Map<String, WordStats>> snapshots = new HashMap<>();

    Map<String, WordStats> build(String text) {
        Map<String, WordStats> words = new LinkedHashMap<>();
        if (text == null || text.isEmpty()) return words;
        StringBuilder word = new StringBuilder();
        int offset = 0;
        for (int index = 0; index <= text.length(); index++) {
            if ((index & 2047) == 0 && Thread.currentThread().isInterrupted()) return Map.of();
            char character = index < text.length() ? text.charAt(index) : '\0';
            if (isWordCharacter(character)) {
                if (word.isEmpty()) offset = index;
                word.append(character);
            } else if (!word.isEmpty()) {
                if (isIdentifierStart(word.charAt(0))) {
                    String value = word.toString();
                    WordStats previous = words.get(value);
                    words.put(value, previous == null ? new WordStats(1, offset) : previous.next(offset));
                }
                word.setLength(0);
            }
        }
        return Map.copyOf(words);
    }

    void update(Object buffer, Map<String, WordStats> snapshot) {
        if (buffer == null || snapshot == null) return;
        snapshots.put(buffer, snapshot);
    }

    void remove(Object buffer) { snapshots.remove(buffer); }

    boolean hasSnapshot(Object buffer) { return snapshots.containsKey(buffer); }

    List<Candidate> complete(List<?> openBuffers, Object currentBuffer, String prefix, int caret, int maxResults,
                             FuzzyMatchService fuzzyMatchService) {
        if (prefix == null || prefix.isBlank() || openBuffers == null || fuzzyMatchService == null) return List.of();
        snapshots.keySet().removeIf(buffer -> !openBuffers.contains(buffer));
        Map<String, Integer> scores = new HashMap<>();
        for (Object buffer : openBuffers) {
            Map<String, WordStats> words = snapshots.get(buffer);
            if (words == null) continue;
            boolean current = buffer == currentBuffer;
            for (Map.Entry<String, WordStats> entry : words.entrySet()) {
                String word = entry.getKey();
                if (word.length() <= prefix.length()) continue;
                int fuzzyScore = fuzzyMatchService.score(prefix, word);
                if (fuzzyScore <= 0) continue;
                WordStats stats = entry.getValue();
                int locality = current ? Math.max(0, 320 - Math.min(320, Math.abs(caret - stats.lastOffset()) / 8)) : 0;
                int score = fuzzyScore + (current ? 700 : 0) + locality + Math.min(80, stats.count() * 8);
                scores.merge(word, score, Math::max);
            }
        }
        List<Candidate> candidates = new ArrayList<>();
        for (Map.Entry<String, Integer> entry : scores.entrySet()) candidates.add(new Candidate(entry.getKey(), entry.getValue()));
        candidates.sort(Comparator.comparingInt(Candidate::score).reversed().thenComparing(Candidate::word, String.CASE_INSENSITIVE_ORDER));
        return candidates.size() <= maxResults ? List.copyOf(candidates) : List.copyOf(candidates.subList(0, maxResults));
    }

    private static boolean isWordCharacter(char character) {
        return Character.isLetterOrDigit(character) || character == '_';
    }

    private static boolean isIdentifierStart(char character) {
        return Character.isLetter(character) || character == '_';
    }
}
