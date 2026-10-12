package shed;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

public class OpenBufferCompletionIndexTest {
    @Test
    void ranksCurrentOpenBufferWordsByFuzzyMatchAndLocality() {
        OpenBufferCompletionIndex index = new OpenBufferCompletionIndex();
        Object first = new Object();
        Object second = new Object();
        index.update(first, index.build("connection requestContext connection"));
        index.update(second, index.build("requestConfig requestContext"));

        List<OpenBufferCompletionIndex.Candidate> matches = index.complete(List.of(first, second), second, "req", 20, 12,
            new FuzzyMatchService());

        assertEquals("requestContext", matches.get(0).word());
        assertTrue(matches.stream().anyMatch(candidate -> candidate.word().equals("requestConfig")));
    }

    @Test
    void excludesNumericLiteralsFromIdentifierCompletionIndex() {
        OpenBufferCompletionIndex index = new OpenBufferCompletionIndex();

        var words = index.build("value 12345 _private");

        assertTrue(words.containsKey("value"));
        assertTrue(words.containsKey("_private"));
        assertTrue(!words.containsKey("12345"));
    }

    @Test
    void updatesChangedLineWordsWithoutRebuildingTheBuffer() {
        OpenBufferCompletionIndex index = new OpenBufferCompletionIndex();
        Object buffer = new Object();
        VersionedTextSnapshot before = VersionedTextSnapshot.of("alpha beta\nGamma");
        VersionedTextSnapshot after = before.replace(6, 4, "better");
        index.update(buffer, before, index.build(before.text()));

        assertTrue(index.applyChange(buffer, new FileBuffer.DocumentTextChange(before, after, 6, 4, "better", true)));
        assertTrue(index.hasSnapshot(buffer, after));
        List<String> matches = index.complete(List.of(buffer), buffer, "be", 10, 12, new FuzzyMatchService())
            .stream().map(OpenBufferCompletionIndex.Candidate::word).toList();
        assertTrue(matches.contains("better"));
        assertFalse(matches.contains("beta"));
    }

    @Test
    void updatesWordsWhenDeletingANewlineJoinsTwoLines() {
        OpenBufferCompletionIndex index = new OpenBufferCompletionIndex();
        Object buffer = new Object();
        VersionedTextSnapshot before = VersionedTextSnapshot.of("alpha\nbeta");
        VersionedTextSnapshot after = before.replace(5, 1, "");
        index.update(buffer, before, index.build(before.text()));

        assertTrue(index.applyChange(buffer, new FileBuffer.DocumentTextChange(before, after, 5, 1, "", true)));
        List<String> matches = index.complete(List.of(buffer), buffer, "alph", 9, 12, new FuzzyMatchService())
            .stream().map(OpenBufferCompletionIndex.Candidate::word).toList();
        assertEquals(List.of("alphabeta"), matches);
    }
}
