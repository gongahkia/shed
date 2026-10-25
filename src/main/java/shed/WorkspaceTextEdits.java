package shed;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;

/** Bounded line diff, selected-hunk application, and conservative three-way rebasing. */
final class WorkspaceTextEdits {
    private static final long MAX_MATRIX_CELLS = 2_000_000;
    record Hunk(int start, int end, String before, String replacement) {
        Hunk {
            if (start < 0 || end < start || before == null || replacement == null || end - start != before.length())
                throw new IllegalArgumentException("Invalid text hunk");
        }
    }
    private record Line(int start, int end, String text) { }
    private WorkspaceTextEdits() { }

    static List<Hunk> diff(String before, String after) {
        if (before.equals(after)) return List.of();
        List<Line> left = lines(before), right = lines(after);
        int prefix = 0;
        while (prefix < left.size() && prefix < right.size() && left.get(prefix).text().equals(right.get(prefix).text())) prefix++;
        int leftEnd = left.size(), rightEnd = right.size();
        while (leftEnd > prefix && rightEnd > prefix && left.get(leftEnd - 1).text().equals(right.get(rightEnd - 1).text())) { leftEnd--; rightEnd--; }
        int n = leftEnd - prefix, m = rightEnd - prefix;
        List<Hunk> result = new ArrayList<>();
        if ((long) (n + 1) * (m + 1) > MAX_MATRIX_CELLS) {
            add(result, before, after, offset(left, prefix, before.length()), offset(left, leftEnd, before.length()),
                offset(right, prefix, after.length()), offset(right, rightEnd, after.length()));
            return List.copyOf(result);
        }
        int width = m + 1;
        int[] lengths = new int[(n + 1) * width];
        for (int i = n - 1; i >= 0; i--)
            for (int j = m - 1; j >= 0; j--)
                lengths[i * width + j] = left.get(prefix + i).text().equals(right.get(prefix + j).text())
                    ? 1 + lengths[(i + 1) * width + j + 1]
                    : Math.max(lengths[(i + 1) * width + j], lengths[i * width + j + 1]);
        int i = 0, j = 0, startI = -1, startJ = -1;
        while (i < n || j < m) {
            if (i < n && j < m && left.get(prefix + i).text().equals(right.get(prefix + j).text())) {
                if (startI >= 0) {
                    add(result, before, after, offset(left, prefix + startI, before.length()), offset(left, prefix + i, before.length()),
                        offset(right, prefix + startJ, after.length()), offset(right, prefix + j, after.length()));
                    startI = startJ = -1;
                }
                i++; j++;
            } else {
                if (startI < 0) { startI = i; startJ = j; }
                if (j >= m || i < n && lengths[(i + 1) * width + j] >= lengths[i * width + j + 1]) i++;
                else j++;
            }
        }
        if (startI >= 0)
            add(result, before, after, offset(left, prefix + startI, before.length()), offset(left, prefix + i, before.length()),
                offset(right, prefix + startJ, after.length()), offset(right, prefix + j, after.length()));
        return List.copyOf(result);
    }

    static String apply(String base, List<Hunk> changes) {
        List<Hunk> ordered = changes.stream().sorted(Comparator.comparingInt(Hunk::start).thenComparingInt(Hunk::end)).toList();
        int end = -1;
        for (Hunk hunk : ordered) {
            if (hunk.start() < end || hunk.end() > base.length() || !base.regionMatches(hunk.start(), hunk.before(), 0, hunk.before().length())
                || !GraphemeBoundary.isBoundary(base, hunk.start()) || !GraphemeBoundary.isBoundary(base, hunk.end()))
                throw new WorkspaceApiException(WorkspaceApiException.Code.CONFLICT, "Text hunks overlap or their original text changed");
            end = hunk.end();
        }
        StringBuilder text = new StringBuilder(base);
        for (int i = ordered.size() - 1; i >= 0; i--) {
            Hunk hunk = ordered.get(i); text.replace(hunk.start(), hunk.end(), hunk.replacement());
        }
        return text.toString();
    }

    static String selected(String before, String after, Set<Integer> selected) {
        List<Hunk> hunks = diff(before, after);
        for (int index : selected) if (index < 0 || index >= hunks.size())
            throw new WorkspaceApiException(WorkspaceApiException.Code.INVALID_REQUEST, "Unknown hunk selection");
        List<Hunk> changes = new ArrayList<>();
        for (int i = 0; i < hunks.size(); i++) if (selected.contains(i)) changes.add(hunks.get(i));
        return apply(before, changes);
    }

    /** Reapply base→desired onto current; overlapping human changes require explicit review. */
    static String rebase(String base, String desired, String current) {
        if (base.equals(current)) return desired;
        List<Hunk> intended = diff(base, desired), intervening = diff(base, current), rebased = new ArrayList<>();
        for (Hunk change : intended) {
            int shift = 0;
            for (Hunk human : intervening) {
                boolean insertion = change.start() == change.end() || human.start() == human.end();
                if (insertion ? change.start() <= human.end() && human.start() <= change.end()
                    : change.start() < human.end() && human.start() < change.end())
                    throw new WorkspaceApiException(WorkspaceApiException.Code.CONFLICT, "Changes overlap; review is required");
                if (human.end() <= change.start()) shift += human.replacement().length() - human.before().length();
            }
            rebased.add(new Hunk(change.start() + shift, change.end() + shift, change.before(), change.replacement()));
        }
        return apply(current, rebased);
    }

    private static void add(List<Hunk> result, String left, String right, int a, int b, int c, int d) {
        while (a < b && c < d && left.charAt(a) == right.charAt(c)) { a++; c++; }
        while ((!GraphemeBoundary.isBoundary(left, a) || !GraphemeBoundary.isBoundary(right, c)) && a > 0 && c > 0) { a--; c--; }
        while (b > a && d > c && left.charAt(b - 1) == right.charAt(d - 1)) { b--; d--; }
        while ((!GraphemeBoundary.isBoundary(left, b) || !GraphemeBoundary.isBoundary(right, d)) && b < left.length() && d < right.length()) { b++; d++; }
        if (a != b || c != d) result.add(new Hunk(a, b, left.substring(a, b), right.substring(c, d)));
    }

    private static int offset(List<Line> lines, int index, int length) { return index < lines.size() ? lines.get(index).start() : length; }
    private static List<Line> lines(String text) {
        List<Line> result = new ArrayList<>();
        int start = 0;
        for (int i = 0; i < text.length(); i++) if (text.charAt(i) == '\n') {
            result.add(new Line(start, i + 1, text.substring(start, i + 1))); start = i + 1;
            if (result.size() == 100_000) break;
        }
        if (start < text.length()) result.add(new Line(start, text.length(), text.substring(start)));
        return result;
    }
}
