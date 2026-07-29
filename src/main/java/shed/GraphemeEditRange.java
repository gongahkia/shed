package shed;

final class GraphemeEditRange {
    private GraphemeEditRange() {
    }

    static Range selection(String text, int start, int end) {
        int first = Math.min(start, end);
        int last = Math.max(start, end);
        return new Range(GraphemeBoundary.floor(text, first), GraphemeBoundary.ceiling(text, last));
    }

    static Range previous(String text, int caret) {
        int end = GraphemeBoundary.floor(text, caret);
        return new Range(GraphemeBoundary.previous(text, end), end);
    }

    static Range next(String text, int caret) {
        int start = GraphemeBoundary.ceiling(text, caret);
        return new Range(start, GraphemeBoundary.next(text, start));
    }

    record Range(int start, int end) {
        boolean empty() {
            return start >= end;
        }
    }
}
