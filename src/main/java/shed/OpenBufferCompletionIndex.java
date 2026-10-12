package shed;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class OpenBufferCompletionIndex {
    private static final int MAX_INCREMENTAL_SPAN = 64 * 1024;
    record Candidate(String word, int score) { }

    record WordStats(int count, int lastOffset) {
        WordStats next(int offset) { return new WordStats(count + 1, offset); }
    }

    private record Snapshot(VersionedTextSnapshot text, Map<String, WordStats> words) { }
    private final Map<Object, Snapshot> snapshots = new HashMap<>();

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
        return words;
    }

    void update(Object buffer, Map<String, WordStats> snapshot) {
        update(buffer, null, snapshot);
    }

    void update(Object buffer, VersionedTextSnapshot text, Map<String, WordStats> snapshot) {
        if (buffer == null || snapshot == null) return;
        // The completed background build hands ownership of this map to the EDT.
        snapshots.put(buffer, new Snapshot(text, snapshot));
    }

    void remove(Object buffer) { snapshots.remove(buffer); }

    boolean hasSnapshot(Object buffer) { return snapshots.containsKey(buffer); }

    boolean hasSnapshot(Object buffer, VersionedTextSnapshot text) {
        Snapshot snapshot = snapshots.get(buffer);
        return snapshot != null && snapshot.text() == text;
    }

    /** Updates only the lines touched by an edit; large replacements use a background rebuild. */
    boolean applyChange(Object buffer, FileBuffer.DocumentTextChange change) {
        Snapshot snapshot = snapshots.get(buffer);
        if (snapshot == null || change == null || !change.incremental() || snapshot.text() != change.before()) return false;
        VersionedTextSnapshot before = change.before();
        VersionedTextSnapshot after = change.after();
        int oldStart = before.lineStartOffset(before.positionAt(change.offset()).line());
        int oldEnd = before.lineEndOffset(before.positionAt(change.offset() + change.removedLength()).line());
        int newStart = after.lineStartOffset(after.positionAt(change.offset()).line());
        int newEnd = after.lineEndOffset(after.positionAt(change.offset() + change.insertedText().length()).line());
        if (oldEnd - oldStart > MAX_INCREMENTAL_SPAN || newEnd - newStart > MAX_INCREMENTAL_SPAN) return false;
        Map<String, WordStats> oldWords = wordsIn(before.substring(oldStart, oldEnd - oldStart), oldStart);
        Map<String, WordStats> newWords = wordsIn(after.substring(newStart, newEnd - newStart), newStart);
        Map<String, WordStats> words = snapshot.words();
        for (Map.Entry<String, WordStats> entry : oldWords.entrySet()) {
            WordStats existing = words.get(entry.getKey());
            if (existing == null) continue;
            int remaining = existing.count() - entry.getValue().count();
            if (remaining <= 0) words.remove(entry.getKey());
            else words.put(entry.getKey(), new WordStats(remaining, existing.lastOffset()));
        }
        for (Map.Entry<String, WordStats> entry : newWords.entrySet()) {
            words.merge(entry.getKey(), entry.getValue(), (old, added) ->
                new WordStats(old.count() + added.count(), Math.max(old.lastOffset(), added.lastOffset())));
        }
        snapshots.put(buffer, new Snapshot(after, words));
        return true;
    }

    private static Map<String, WordStats> wordsIn(String text, int baseOffset) {
        Map<String, WordStats> words = new HashMap<>();
        int start = -1;
        for (int index = 0; index <= text.length(); index++) {
            char character = index < text.length() ? text.charAt(index) : '\0';
            if (isWordCharacter(character)) {
                if (start < 0) start = index;
            } else if (start >= 0) {
                if (isIdentifierStart(text.charAt(start))) {
                    String word = text.substring(start, index);
                    int offset = baseOffset + start;
                    words.merge(word, new WordStats(1, offset), (old, ignored) -> old.next(offset));
                }
                start = -1;
            }
        }
        return words;
    }

    List<Candidate> complete(List<?> openBuffers, Object currentBuffer, String prefix, int caret, int maxResults,
                             FuzzyMatchService fuzzyMatchService) {
        if (prefix == null || prefix.isBlank() || openBuffers == null || fuzzyMatchService == null) return List.of();
        snapshots.keySet().removeIf(buffer -> !openBuffers.contains(buffer));
        Map<String, Integer> scores = new HashMap<>();
        for (Object buffer : openBuffers) {
            Snapshot snapshot = snapshots.get(buffer);
            if (snapshot == null) continue;
            if (buffer instanceof FileBuffer fileBuffer && snapshot.text() != null
                && fileBuffer.textSnapshot() != snapshot.text()) continue;
            Map<String, WordStats> words = snapshot.words();
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
