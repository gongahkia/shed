package shed;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Shared matching and replacement semantics for editor and workspace searches. */
record SearchPattern(String query, boolean caseSensitive, boolean regex, boolean wholeWord) {
    record Range(int start, int end) { }
    record Scan(List<Range> ranges, boolean truncated) { }
    record Replacement(String text, int count, int firstOffset) { }

    SearchPattern {
        if (query == null || query.isEmpty() || query.indexOf('\0') >= 0 || query.indexOf('\n') >= 0 || query.indexOf('\r') >= 0)
            throw new IllegalArgumentException("Enter a non-empty, single-line search expression");
    }
    static SearchPattern literal(String query) { return new SearchPattern(query, true, false, false); }
    Pattern compile() {
        String expression = regex ? query : Pattern.quote(query);
        if (wholeWord) expression = "(?<![\\p{L}\\p{M}\\p{N}_])(?:" + expression + ")(?![\\p{L}\\p{M}\\p{N}_])";
        if (regex && query.length() > 4096) throw new IllegalArgumentException("Regular expressions are limited to 4096 characters");
        try { return Pattern.compile(expression, Pattern.MULTILINE | (caseSensitive ? 0 : Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE)); }
        catch (StackOverflowError error) { throw new IllegalArgumentException("Expression is too complex; simplify the regular expression"); }
    }
    Matcher matcher(String text, BooleanSupplier cancelled) {
        return matcher(compile(), text, cancelled);
    }
    Matcher matcher(Pattern compiled, String text, BooleanSupplier cancelled) {
        return checkedMatcher(compiled, text, cancelled);
    }
    static Matcher checkedMatcher(Pattern compiled, String text, BooleanSupplier cancelled) {
        check(cancelled);
        return compiled.matcher(new CheckedText(text, cancelled, System.nanoTime() + 1_000_000_000L));
    }
    Scan scan(String text, int limit, BooleanSupplier cancelled) {
        List<Range> ranges = new ArrayList<>();
        Matcher matcher = matcher(text, cancelled);
        try {
            while (matcher.find()) {
                check(cancelled);
                if (ranges.size() == limit) return new Scan(List.copyOf(ranges), true);
                ranges.add(new Range(matcher.start(), matcher.end()));
            }
        } catch (StackOverflowError error) { throw new IllegalArgumentException("Expression is too complex; simplify the regular expression"); }
        return new Scan(List.copyOf(ranges), false);
    }
    String replacementAt(String text, Range range, String value) {
        Matcher matcher = matcher(text, () -> false);
        if (!matcher.find(range.start()) || matcher.start() != range.start() || matcher.end() != range.end())
            throw new IllegalArgumentException("Match changed; search again before replacing");
        StringBuilder prefix = new StringBuilder();
        matcher.appendReplacement(prefix, regex ? value : Matcher.quoteReplacement(value));
        return prefix.substring(range.start());
    }
    Replacement replaceAll(String text, String value, BooleanSupplier cancelled) {
        Matcher matcher = matcher(text, cancelled);
        StringBuilder output = new StringBuilder(text.length());
        int count = 0;
        int first = -1;
        try {
            while (matcher.find()) {
                check(cancelled);
                if (first < 0) first = matcher.start();
                count++;
                matcher.appendReplacement(output, regex ? value : Matcher.quoteReplacement(value));
            }
        } catch (StackOverflowError error) { throw new IllegalArgumentException("Expression is too complex; simplify the regular expression"); }
        matcher.appendTail(output);
        return new Replacement(output.toString(), count, first);
    }
    private static void check(BooleanSupplier cancelled) {
        if (Thread.currentThread().isInterrupted() || cancelled != null && cancelled.getAsBoolean()) throw new CancellationException("Search cancelled");
    }
    private static final class CheckedText implements CharSequence {
        private final String text;
        private final BooleanSupplier cancelled;
        private final long deadline;
        private int reads;
        CheckedText(String text, BooleanSupplier cancelled, long deadline) { this.text = text; this.cancelled = cancelled; this.deadline = deadline; }
        public int length() { return text.length(); }
        public char charAt(int index) {
            if ((reads++ & 1023) == 0) {
                check(cancelled);
                if (System.nanoTime() > deadline) throw new IllegalArgumentException("Search exceeded its time budget; simplify the expression or narrow the scope");
            }
            return text.charAt(index);
        }
        public CharSequence subSequence(int start, int end) { return text.subSequence(start, end); }
        public String toString() { return text; }
    }
}
