package shed;

public class MotionService {
    static int charClass(char value) {
        return charClass((int) value);
    }

    static int charClass(int codePoint) {
        if (Character.isLetterOrDigit(codePoint) || codePoint == '_') {
            return 1;
        }
        return Character.isWhitespace(codePoint) ? 0 : 2;
    }

    public int moveWordForward(String text, int position) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        int pos = GraphemeBoundary.isBoundary(text, position) ? position : GraphemeBoundary.ceiling(text, position);
        if (pos >= text.length()) {
            return text.length();
        }
        int category = charClass(text.codePointAt(pos));
        if (category > 0) {
            while (pos < text.length() && charClass(text.codePointAt(pos)) == category) {
                pos = GraphemeBoundary.next(text, pos);
            }
        }
        while (pos < text.length() && Character.isWhitespace(text.codePointAt(pos))) {
            pos = GraphemeBoundary.next(text, pos);
        }
        return pos;
    }

    public int moveWordBackward(String text, int position) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        int pos = GraphemeBoundary.isBoundary(text, position) ? GraphemeBoundary.previous(text, position)
            : GraphemeBoundary.floor(text, position);
        while (pos > 0 && Character.isWhitespace(text.codePointAt(pos))) {
            pos = GraphemeBoundary.previous(text, pos);
        }
        int category = charClass(text.codePointAt(pos));
        while (pos > 0) {
            int previous = GraphemeBoundary.previous(text, pos);
            if (charClass(text.codePointAt(previous)) != category) {
                break;
            }
            pos = previous;
        }
        return pos;
    }

    public int moveWordEnd(String text, int position) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        int pos = GraphemeBoundary.isBoundary(text, position) ? position : GraphemeBoundary.ceiling(text, position);
        if (pos >= text.length()) {
            return GraphemeBoundary.previous(text, pos);
        }
        pos = GraphemeBoundary.next(text, pos);
        while (pos < text.length() && Character.isWhitespace(text.codePointAt(pos))) {
            pos = GraphemeBoundary.next(text, pos);
        }
        if (pos >= text.length()) {
            return GraphemeBoundary.previous(text, pos);
        }
        int category = charClass(text.codePointAt(pos));
        while (GraphemeBoundary.next(text, pos) < text.length()
            && charClass(text.codePointAt(GraphemeBoundary.next(text, pos))) == category) {
            pos = GraphemeBoundary.next(text, pos);
        }
        return pos;
    }
}
