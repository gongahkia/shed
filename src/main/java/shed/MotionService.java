package shed;

public class MotionService {
    static int charClass(char c) {
        if (Character.isLetterOrDigit(c) || c == '_') return 1;
        if (Character.isWhitespace(c)) return 0;
        return 2;
    }
    public int moveWordForward(String text, int position) {
        if (text == null || text.isEmpty()) return 0;
        int pos = clamp(position, text.length());
        int len = text.length();
        if (pos >= len) return len;
        int cls = charClass(text.charAt(pos));
        if (cls > 0) {
            while (pos < len && charClass(text.charAt(pos)) == cls) pos++;
        }
        while (pos < len && Character.isWhitespace(text.charAt(pos))) pos++;
        return Math.min(pos, len);
    }
    public int moveWordBackward(String text, int position) {
        if (text == null || text.isEmpty()) return 0;
        int pos = clamp(position, text.length());
        if (pos <= 0) return 0;
        pos--;
        while (pos > 0 && Character.isWhitespace(text.charAt(pos))) pos--;
        if (pos >= 0) {
            int cls = charClass(text.charAt(pos));
            while (pos > 0 && charClass(text.charAt(pos - 1)) == cls) pos--;
        }
        return Math.max(0, pos);
    }
    public int moveWordEnd(String text, int position) {
        if (text == null || text.isEmpty()) return 0;
        int pos = clamp(position, text.length());
        int len = text.length();
        if (pos >= len - 1) return Math.max(0, len - 1);
        pos++;
        while (pos < len && Character.isWhitespace(text.charAt(pos))) pos++;
        if (pos < len) {
            int cls = charClass(text.charAt(pos));
            while (pos + 1 < len && charClass(text.charAt(pos + 1)) == cls) pos++;
        }
        return Math.max(0, Math.min(pos, len));
    }
    private int clamp(int position, int max) {
        return Math.max(0, Math.min(position, max));
    }
}
