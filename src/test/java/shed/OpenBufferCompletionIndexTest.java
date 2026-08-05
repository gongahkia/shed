package shed;

import static org.junit.jupiter.api.Assertions.assertEquals;
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

        List<OpenBufferCompletionIndex.Candidate> matches = index.complete(List.of(first, second), second, "req", 13, 12,
            new FuzzyMatchService());

        assertEquals("requestContext", matches.get(0).word());
        assertTrue(matches.stream().anyMatch(candidate -> candidate.word().equals("requestConfig")));
    }
}
