package shed;

final class GraphemeBoundary {
    private GraphemeBoundary() {
    }

    static int previous(String text, int offset) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        int target = clamp(text, offset);
        if (target == 0) {
            return 0;
        }
        int boundary = 0;
        while (boundary < target) {
            int next = nextBoundary(text, boundary);
            if (next >= target) {
                return boundary;
            }
            boundary = next;
        }
        return boundary;
    }

    static int next(String text, int offset) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        int target = clamp(text, offset);
        if (target >= text.length()) {
            return text.length();
        }
        if (!isBoundary(text, target)) {
            return ceiling(text, target);
        }
        return nextBoundary(text, target);
    }

    static int floor(String text, int offset) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        int target = clamp(text, offset);
        int boundary = 0;
        while (boundary < target) {
            int next = nextBoundary(text, boundary);
            if (next > target) {
                return boundary;
            }
            boundary = next;
        }
        return boundary;
    }

    static int ceiling(String text, int offset) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        int target = clamp(text, offset);
        int boundary = 0;
        while (boundary < target) {
            boundary = nextBoundary(text, boundary);
        }
        return boundary;
    }

    static boolean isBoundary(String text, int offset) {
        if (text == null || text.isEmpty()) {
            return offset == 0;
        }
        int target = clamp(text, offset);
        int boundary = 0;
        while (boundary < target) {
            boundary = nextBoundary(text, boundary);
        }
        return boundary == target;
    }

    private static int nextBoundary(String text, int start) {
        if (start >= text.length()) {
            return text.length();
        }
        int cursor = start;
        int previous = codePointAt(text, cursor);
        cursor += width(previous);
        int regionalIndicators = isRegionalIndicator(previous) ? 1 : 0;
        boolean emojiBeforeZwj = isEmoji(previous);
        while (cursor < text.length()) {
            int current = codePointAt(text, cursor);
            if (!continues(previous, current, regionalIndicators, emojiBeforeZwj)) {
                return cursor;
            }
            if (isRegionalIndicator(current)) {
                regionalIndicators++;
            } else if (!isExtend(current)) {
                regionalIndicators = 0;
            }
            if (current == 0x200D) {
                emojiBeforeZwj = emojiBeforeZwj || isEmoji(previous);
            } else if (!isExtend(current)) {
                emojiBeforeZwj = isEmoji(current);
            }
            previous = current;
            cursor += width(current);
        }
        return cursor;
    }

    private static boolean continues(int previous, int current, int regionalIndicators, boolean emojiBeforeZwj) {
        if (isSurrogate(previous) || isSurrogate(current)) {
            return false;
        }
        if (previous == '\r' && current == '\n') {
            return true;
        }
        if (isControl(previous) || isControl(current)) {
            return false;
        }
        if (joinsHangul(previous, current) || isPrepend(previous)) {
            return true;
        }
        if (isExtend(current) || current == 0x200D) {
            return true;
        }
        if (isEmojiModifier(current) && (isEmoji(previous) || isExtend(previous))) {
            return true;
        }
        if (previous == 0x200D && emojiBeforeZwj && isEmoji(current)) {
            return true;
        }
        return isRegionalIndicator(previous) && isRegionalIndicator(current) && regionalIndicators % 2 == 1;
    }

    private static int codePointAt(String text, int offset) {
        return text.codePointAt(offset);
    }

    private static int width(int codePoint) {
        return Character.charCount(codePoint);
    }

    private static int clamp(String text, int offset) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        return Math.max(0, Math.min(offset, text.length()));
    }

    private static boolean isControl(int codePoint) {
        return codePoint == '\r' || codePoint == '\n'
            || Character.getType(codePoint) == Character.CONTROL
            || Character.getType(codePoint) == Character.FORMAT && codePoint != 0x200C && codePoint != 0x200D;
    }

    private static boolean isSurrogate(int codePoint) {
        return codePoint >= Character.MIN_SURROGATE && codePoint <= Character.MAX_SURROGATE;
    }

    private static boolean isExtend(int codePoint) {
        int type = Character.getType(codePoint);
        return type == Character.NON_SPACING_MARK || type == Character.COMBINING_SPACING_MARK
            || type == Character.ENCLOSING_MARK || codePoint == 0x200C || isVariationSelector(codePoint);
    }

    private static boolean isVariationSelector(int codePoint) {
        return codePoint >= 0xFE00 && codePoint <= 0xFE0F
            || codePoint >= 0xE0100 && codePoint <= 0xE01EF;
    }

    private static boolean isEmojiModifier(int codePoint) {
        return codePoint >= 0x1F3FB && codePoint <= 0x1F3FF;
    }

    private static boolean isEmoji(int codePoint) {
        return codePoint >= 0x1F000 && codePoint <= 0x1FAFF
            || codePoint >= 0x2600 && codePoint <= 0x27BF
            || codePoint == 0x00A9 || codePoint == 0x00AE || codePoint == 0x203C
            || codePoint == 0x2049 || codePoint == 0x2122 || codePoint == 0x2139
            || codePoint == 0x3030 || codePoint == 0x303D || codePoint == 0x3297 || codePoint == 0x3299;
    }

    private static boolean isRegionalIndicator(int codePoint) {
        return codePoint >= 0x1F1E6 && codePoint <= 0x1F1FF;
    }

    private static boolean joinsHangul(int previous, int current) {
        return isHangulL(previous) && (isHangulL(current) || isHangulV(current) || isHangulLV(current) || isHangulLVT(current))
            || (isHangulLV(previous) || isHangulV(previous)) && (isHangulV(current) || isHangulT(current))
            || (isHangulLVT(previous) || isHangulT(previous)) && isHangulT(current);
    }

    private static boolean isHangulL(int codePoint) {
        return codePoint >= 0x1100 && codePoint <= 0x115F || codePoint >= 0xA960 && codePoint <= 0xA97C;
    }

    private static boolean isHangulV(int codePoint) {
        return codePoint >= 0x1160 && codePoint <= 0x11A7 || codePoint >= 0xD7B0 && codePoint <= 0xD7C6;
    }

    private static boolean isHangulT(int codePoint) {
        return codePoint >= 0x11A8 && codePoint <= 0x11FF || codePoint >= 0xD7CB && codePoint <= 0xD7FB;
    }

    private static boolean isHangulLV(int codePoint) {
        return isHangulSyllable(codePoint) && (codePoint - 0xAC00) % 28 == 0;
    }

    private static boolean isHangulLVT(int codePoint) {
        return isHangulSyllable(codePoint) && (codePoint - 0xAC00) % 28 != 0;
    }

    private static boolean isHangulSyllable(int codePoint) {
        return codePoint >= 0xAC00 && codePoint <= 0xD7A3;
    }

    private static boolean isPrepend(int codePoint) {
        return codePoint >= 0x0600 && codePoint <= 0x0605 || codePoint == 0x06DD || codePoint == 0x070F
            || codePoint == 0x0890 || codePoint == 0x0891 || codePoint == 0x08E2 || codePoint == 0x0D4E
            || codePoint == 0x110BD || codePoint == 0x110CD || codePoint >= 0x111C2 && codePoint <= 0x111C3
            || codePoint == 0x1193F || codePoint == 0x11941 || codePoint == 0x11A3A
            || codePoint >= 0x11A84 && codePoint <= 0x11A89 || codePoint == 0x11D46;
    }
}
