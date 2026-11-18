package shed;

/** Conservative fence context, carried by the existing lexical checkpoints; never a parser cache. */
final class MarkdownKeyboardContext {
    static final int MAX_LINE = 4096, MAX_READ = 16384, MAX_LINES = 128;
    record State(char fence, int length, boolean lists) {
        boolean text() { return fence == 0; }
    }
    static final State TEXT = new State((char) 0, 0, false), UNKNOWN = new State('?', 0, false);
    record Fence(int markerEnd, char marker, int length, String info) { }

    static Fence fence(String line) {
        int start = 0;
        while (start < line.length() && line.charAt(start) == ' ') start++;
        if (start > 3 || start == line.length()) return null;
        char marker = line.charAt(start);
        if (marker != '`' && marker != '~') return null;
        int end = start;
        while (end < line.length() && line.charAt(end) == marker) end++;
        if (end - start < 3) return null;
        return new Fence(end, marker, end - start, line.substring(end));
    }

    static State advance(State state, String line) {
        if (state == UNKNOWN || line.length() > MAX_LINE) return UNKNOWN;
        Fence fence = fence(line);
        if (!state.text()) {
            return fence != null && fence.marker() == state.fence() && fence.length() >= state.length()
                && blankTail(fence.info()) ? new State((char) 0, 0, state.lists()) : state;
        }
        String trimmed = line.stripLeading();
        // HTML and container fences need richer block ownership than these checkpoints promise.
        boolean list = list(trimmed);
        if (trimmed.startsWith("<") || (trimmed.startsWith(">") || list)
                && (trimmed.contains("```") || trimmed.contains("~~~"))) return UNKNOWN;
        if (fence != null) {
            if (state.lists() && line.charAt(0) == ' ') return UNKNOWN;
            if (fence.marker() == '`' && fence.info().indexOf('`') >= 0) return state;
            return new State(fence.marker(), fence.length(), state.lists());
        }
        if (state.lists() && (trimmed.startsWith("```") || trimmed.startsWith("~~~"))) return UNKNOWN;
        return list && !state.lists() ? new State((char) 0, 0, true) : state;
    }

    private static boolean blankTail(String tail) {
        for (int i = 0; i < tail.length(); i++)
            if (tail.charAt(i) != ' ' && tail.charAt(i) != '\t' && !(i == tail.length() - 1 && tail.charAt(i) == '\r')) return false;
        return true;
    }

    private static boolean list(String text) {
        if (text.isEmpty()) return false;
        int end = 1;
        if ("-*+".indexOf(text.charAt(0)) < 0) {
            end = 0;
            while (end < text.length() && end < 9 && text.charAt(end) >= '0' && text.charAt(end) <= '9') end++;
            if (end == 0 || end == text.length() || ".)".indexOf(text.charAt(end)) < 0) return false;
            end++;
        }
        return end == text.length() || text.charAt(end) == ' ' || text.charAt(end) == '\t';
    }

    static State before(VersionedTextSnapshot text, int from, State state, int target) {
        if (state == UNKNOWN || target < from || target - from > MAX_READ) return UNKNOWN;
        if (target == from) return state;
        String source = text.substring(from, target - from);
        int start = 0, lines = 0;
        while (start < source.length()) {
            if (++lines > MAX_LINES) return UNKNOWN;
            int end = source.indexOf('\n', start);
            if (end < 0 || end - start > MAX_LINE) return UNKNOWN;
            state = advance(state, source.substring(start, end));
            if (state == UNKNOWN) return state;
            start = end + 1;
        }
        return state;
    }
}
