package shed;

import java.awt.Color;
import java.util.ArrayList;
import java.util.List;

public class BracketColorService {

    private static final Color[] BRACKET_COLORS = {
        new Color(255, 215, 0),   // gold
        new Color(218, 112, 214), // orchid
        new Color(0, 191, 255),   // deep sky blue
        new Color(50, 205, 50),   // lime green
        new Color(255, 127, 80),  // coral
        new Color(147, 112, 219), // medium purple
    };

    private static final char[] OPEN_BRACKETS = {'(', '[', '{'};
    private static final char[] CLOSE_BRACKETS = {')', ']', '}'};

    public static class BracketPair {
        public final int openOffset;
        public final int closeOffset;
        public final int depth;
        public final char bracketType;

        public BracketPair(int openOffset, int closeOffset, int depth, char bracketType) {
            this.openOffset = openOffset;
            this.closeOffset = closeOffset;
            this.depth = depth;
            this.bracketType = bracketType;
        }

        public Color color() {
            return BRACKET_COLORS[depth % BRACKET_COLORS.length];
        }
    }

    public static class ColoredBracket {
        public final int offset;
        public final int depth;
        public final char bracket;

        public ColoredBracket(int offset, int depth, char bracket) {
            this.offset = offset;
            this.depth = depth;
            this.bracket = bracket;
        }

        public Color color() {
            return BRACKET_COLORS[Math.abs(depth) % BRACKET_COLORS.length];
        }
    }

    public List<ColoredBracket> computeBracketColors(String text) {
        if (text == null || text.isEmpty()) return new ArrayList<>();
        List<ColoredBracket> result = new ArrayList<>();
        int[] depthByType = new int[3]; // ( [ {

        boolean inString = false;
        boolean inLineComment = false;
        boolean inBlockComment = false;
        char stringChar = 0;

        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            char next = i + 1 < text.length() ? text.charAt(i + 1) : 0;

            if (inLineComment) {
                if (c == '\n') inLineComment = false;
                continue;
            }
            if (inBlockComment) {
                if (c == '*' && next == '/') {
                    inBlockComment = false;
                    i++;
                }
                continue;
            }
            if (inString) {
                if (c == '\\') {
                    i++;
                    continue;
                }
                if (c == stringChar) inString = false;
                continue;
            }

            if (c == '/' && next == '/') {
                inLineComment = true;
                i++;
                continue;
            }
            if (c == '/' && next == '*') {
                inBlockComment = true;
                i++;
                continue;
            }
            if (c == '"' || c == '\'' || c == '`') {
                inString = true;
                stringChar = c;
                continue;
            }

            int openIdx = bracketIndex(c, OPEN_BRACKETS);
            if (openIdx >= 0) {
                result.add(new ColoredBracket(i, depthByType[openIdx], c));
                depthByType[openIdx]++;
                continue;
            }
            int closeIdx = bracketIndex(c, CLOSE_BRACKETS);
            if (closeIdx >= 0) {
                depthByType[closeIdx]--;
                result.add(new ColoredBracket(i, Math.max(0, depthByType[closeIdx]), c));
            }
        }
        return result;
    }

    public List<BracketPair> findMatchingPairs(String text) {
        if (text == null || text.isEmpty()) return new ArrayList<>();
        List<BracketPair> pairs = new ArrayList<>();
        List<int[]>[] stacks = new List[3];
        for (int i = 0; i < 3; i++) stacks[i] = new ArrayList<>();

        boolean inString = false;
        boolean inLineComment = false;
        boolean inBlockComment = false;
        char stringChar = 0;

        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            char next = i + 1 < text.length() ? text.charAt(i + 1) : 0;

            if (inLineComment) {
                if (c == '\n') inLineComment = false;
                continue;
            }
            if (inBlockComment) {
                if (c == '*' && next == '/') { inBlockComment = false; i++; }
                continue;
            }
            if (inString) {
                if (c == '\\') { i++; continue; }
                if (c == stringChar) inString = false;
                continue;
            }
            if (c == '/' && next == '/') { inLineComment = true; i++; continue; }
            if (c == '/' && next == '*') { inBlockComment = true; i++; continue; }
            if (c == '"' || c == '\'' || c == '`') { inString = true; stringChar = c; continue; }

            int openIdx = bracketIndex(c, OPEN_BRACKETS);
            if (openIdx >= 0) {
                stacks[openIdx].add(new int[]{i, stacks[openIdx].size()});
                continue;
            }
            int closeIdx = bracketIndex(c, CLOSE_BRACKETS);
            if (closeIdx >= 0 && !stacks[closeIdx].isEmpty()) {
                int[] open = stacks[closeIdx].remove(stacks[closeIdx].size() - 1);
                pairs.add(new BracketPair(open[0], i, open[1], OPEN_BRACKETS[closeIdx]));
            }
        }
        return pairs;
    }

    private static int bracketIndex(char c, char[] brackets) {
        for (int i = 0; i < brackets.length; i++) {
            if (c == brackets[i]) return i;
        }
        return -1;
    }

    public static Color[] getBracketColors() {
        return BRACKET_COLORS.clone();
    }
}
