package shed;

public class MotionService {
    public int moveWordForward(String text, int position) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        int pos = clamp(position, text.length());
        while (pos < text.length() && !Character.isWhitespace(text.charAt(pos))) {
            pos++;
        }
        while (pos < text.length() && Character.isWhitespace(text.charAt(pos))) {
            pos++;
        }
        return Math.min(pos, text.length());
    }

    public int moveWordBackward(String text, int position) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        int pos = clamp(position, text.length());
        if (pos > 0) {
            pos--;
            while (pos > 0 && Character.isWhitespace(text.charAt(pos))) {
                pos--;
            }
            while (pos > 0 && !Character.isWhitespace(text.charAt(pos))) {
                pos--;
            }
            if (pos > 0) {
                pos++;
            }
        }
        return Math.max(0, Math.min(pos, text.length()));
    }

    public int moveWordEnd(String text, int position) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        int pos = clamp(position, text.length());
        if (pos < text.length()) {
            pos++;
            while (pos < text.length() && Character.isWhitespace(text.charAt(pos))) {
                pos++;
            }
            while (pos < text.length() && !Character.isWhitespace(text.charAt(pos))) {
                pos++;
            }
            if (pos > 0) {
                pos--;
            }
        }
        return Math.max(0, Math.min(pos, text.length()));
    }

    private int clamp(int position, int max) {
        return Math.max(0, Math.min(position, max));
    }
}
