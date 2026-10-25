package shed;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class SearchPatternTest {
    @Test void sharesCaseAndUnicodeWordRulesWithoutTrimmingWhitespace() {
        var pattern = new SearchPattern("café", false, false, true);
        assertEquals(List.of(new SearchPattern.Range(0, 4), new SearchPattern.Range(19, 23)),
            pattern.scan("CAFÉ caféine _café café!", 20, () -> false).ranges());
        assertEquals(1, SearchPattern.literal(" ").scan("a b", 20, () -> false).ranges().size());
    }
    @Test void expandsGroupsAndHandlesZeroWidthAndVariableLengthMatches() {
        var pattern = new SearchPattern("(?<word>[a-z]+)=(\\d+)", true, true, false);
        String input = "prefix a=1 bb=22";
        var scan = pattern.scan(input, 20, () -> false);
        assertEquals(2, scan.ranges().size());
        assertEquals("22:bb", pattern.replacementAt(input, scan.ranges().get(1), "$2:${word}"));
        assertEquals("prefix 1:a 22:bb", pattern.replaceAll(input, "$2:${word}", () -> false).text());
        assertEquals("$1$1", SearchPattern.literal("a").replaceAll("aa", "$1", () -> false).text());
        assertEquals(3, new SearchPattern("(?=a)|$", true, true, false).scan("aa", 10, () -> false).ranges().size());
        assertFalse(SearchPattern.literal("a").scan("aa", 2, () -> false).truncated());
        assertTrue(SearchPattern.literal("a").scan("aaa", 2, () -> false).truncated());
    }
    @Test void invalidExpressionsAndBacktrackingRemainCancellable() {
        assertThrows(IllegalArgumentException.class, () -> new SearchPattern("[", true, true, false).compile());
        AtomicInteger checks = new AtomicInteger();
        assertThrows(CancellationException.class, () -> new SearchPattern("(a+)+$", true, true, false)
            .scan("a".repeat(20000) + "!", 20, () -> checks.incrementAndGet() > 5));
    }
    @Test void filtersRootAndNestedFilesWithBracesAndExclusions() {
        var filters = new SearchFileFilter("**/*.{java,kt}", "build/**,**/generated/**");
        assertTrue(filters.accepts(Path.of("Main.java")));
        assertTrue(filters.accepts(Path.of("src/Main.kt")));
        assertFalse(filters.accepts(Path.of("build/Main.java")));
        assertFalse(filters.accepts(Path.of("src/generated/Main.java")));
        assertFalse(filters.accepts(Path.of("notes.txt")));
        assertThrows(IllegalArgumentException.class, () -> new SearchFileFilter("[", ""));
    }

    @Test void cancellableGlobsRetainNativeSyntaxAcrossGroupsRangesAndLiteralMetacharacters() {
        var filesystem = java.nio.file.FileSystems.getDefault();
        for (String glob : List.of("**/*.java", "*.{java,kt}", "a?*.txt", "src/[a-z]*.java", "src/[!A-Z]*.java",
            "[[]literal].txt", "foo}", "{src,lib}/**", "a+b.*", "**/generated/**", "[^x].txt", "[a-c&].txt", "[!a-c].txt", "[-ab].txt", "[a-].txt", "{x,y,}.*")) {
            var nativePattern = filesystem.getPathMatcher("glob:" + glob);
            var rootPattern = glob.startsWith("**/") ? filesystem.getPathMatcher("glob:" + glob.substring(3)) : null;
            var filter = new SearchFileFilter(glob, "");
            for (String name : List.of("Main.java", "main.kt", "src/Main.java", "src/main.java", "src/a/b.java", "ab.txt", "abc.txt", "lib/a.txt",
                "[literal].txt", "foo}", "a+b.txt", "generated/a.java", "src/generated/a.java", "^.txt", "&.txt", "-.txt", "x.txt", ".txt", "é.txt")) {
                Path path = Path.of(name);
                boolean expected = nativePattern.matches(path) || !glob.contains("/") && nativePattern.matches(path.getFileName())
                    || rootPattern != null && rootPattern.matches(path);
                assertEquals(expected, filter.accepts(path), glob + " against " + name);
            }
        }
    }

    @Test void pathologicalPathGlobsCanBeCancelledDuringBacktracking() {
        AtomicInteger checks = new AtomicInteger();
        var filter = new SearchFileFilter("*a".repeat(64) + "b", "");
        assertThrows(CancellationException.class,
            () -> filter.accepts(Path.of("a".repeat(20_000) + "c"), () -> checks.incrementAndGet() > 5));
    }
}
