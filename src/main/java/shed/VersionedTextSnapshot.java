package shed;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Immutable piece-tree snapshot used by background editor work. It keeps Swing
 * documents on the EDT while allowing workers to read a stable text revision.
 */
final class VersionedTextSnapshot {
    private static final int CHUNK_SIZE = 8 * 1024;
    private static final AtomicLong PRIORITY_SEED = new AtomicLong(0x6A09E667F3BCC909L);

    record Position(int line, int character) { }

    private final Node root;
    private final long version;

    private VersionedTextSnapshot(Node root, long version) {
        this.root = root;
        this.version = version;
    }

    static VersionedTextSnapshot empty() {
        return new VersionedTextSnapshot(null, 0L);
    }

    static VersionedTextSnapshot of(String text) {
        String value = text == null ? "" : text;
        Node built = null;
        for (int offset = 0; offset < value.length(); offset += CHUNK_SIZE) {
            built = merge(built, leaf(value.substring(offset, Math.min(value.length(), offset + CHUNK_SIZE))));
        }
        return new VersionedTextSnapshot(built, 0L);
    }

    long version() {
        return version;
    }

    int length() {
        return chars(root);
    }

    int lineCount() {
        return root == null ? 1 : root.newlines + 1;
    }

    VersionedTextSnapshot replace(int offset, int removedLength, String insertedText) {
        int start = Math.max(0, Math.min(offset, length()));
        int remove = Math.max(0, Math.min(removedLength, length() - start));
        Parts first = split(root, start);
        Parts second = split(first.right, remove);
        return new VersionedTextSnapshot(merge(merge(first.left, nodes(insertedText)), second.right), version + 1L);
    }

    Position positionAt(int offset) {
        Stats stats = prefix(root, Math.max(0, Math.min(offset, length())));
        return new Position(stats.newlines, stats.trailingCharacters);
    }

    int offsetAt(int line, int character) {
        if (line <= 0) return Math.max(0, Math.min(character, lineEndOffset(0)));
        if (line >= lineCount()) return length();
        int start = lineStartOffset(line);
        int available = Math.max(0, lineEndOffset(line) - start);
        return start + Math.min(Math.max(0, character), available);
    }

    int lineStartOffset(int line) {
        if (line <= 0) return 0;
        if (line >= lineCount()) return length();
        int newline = offsetOfNewline(root, line - 1, 0);
        return newline < 0 ? length() : newline + 1;
    }

    int lineEndOffset(int line) {
        if (line < 0) return 0;
        if (line >= lineCount() - 1) return length();
        int newline = offsetOfNewline(root, line, 0);
        return newline < 0 ? length() : newline;
    }

    String line(int line) {
        int start = lineStartOffset(line);
        int end = lineEndOffset(line);
        return substring(start, Math.max(0, end - start));
    }

    char charAt(int offset) {
        if (offset < 0 || offset >= length()) throw new IndexOutOfBoundsException("offset=" + offset);
        return charAt(root, offset);
    }

    String substring(int offset, int requestedLength) {
        int start = Math.max(0, Math.min(offset, length()));
        int end = Math.max(start, Math.min(length(), start + Math.max(0, requestedLength)));
        StringBuilder text = new StringBuilder(end - start);
        append(root, start, end, 0, text);
        return text.toString();
    }

    String text() {
        return substring(0, length());
    }

    private static Node nodes(String value) {
        if (value == null || value.isEmpty()) return null;
        Node result = null;
        for (int offset = 0; offset < value.length(); offset += CHUNK_SIZE) {
            result = merge(result, leaf(value.substring(offset, Math.min(value.length(), offset + CHUNK_SIZE))));
        }
        return result;
    }

    private static Node leaf(String text) {
        return text == null || text.isEmpty() ? null : new Node(null, null, text, priority());
    }

    private static long priority() {
        long value = PRIORITY_SEED.updateAndGet(previous -> previous * 6364136223846793005L + 1442695040888963407L);
        return value ^ (value >>> 33);
    }

    private static Node merge(Node left, Node right) {
        if (left == null) return right;
        if (right == null) return left;
        if (left.priority >= right.priority) return new Node(left.left, merge(left.right, right), left.text, left.priority);
        return new Node(merge(left, right.left), right.right, right.text, right.priority);
    }

    private static Parts split(Node node, int offset) {
        if (node == null) return new Parts(null, null);
        int leftChars = chars(node.left);
        if (offset < leftChars) {
            Parts split = split(node.left, offset);
            return new Parts(split.left, new Node(split.right, node.right, node.text, node.priority));
        }
        int textEnd = leftChars + node.text.length();
        if (offset > textEnd) {
            Parts split = split(node.right, offset - textEnd);
            return new Parts(new Node(node.left, split.left, node.text, node.priority), split.right);
        }
        int within = offset - leftChars;
        if (within == 0) return new Parts(node.left, new Node(null, node.right, node.text, node.priority));
        if (within == node.text.length()) return new Parts(new Node(node.left, null, node.text, node.priority), node.right);
        Node left = merge(node.left, leaf(node.text.substring(0, within)));
        Node right = merge(leaf(node.text.substring(within)), node.right);
        return new Parts(left, right);
    }

    private static int offsetOfNewline(Node node, int target, int base) {
        if (node == null || target < 0 || target >= newlines(node)) return -1;
        int leftNewlines = newlines(node.left);
        int leftChars = chars(node.left);
        if (target < leftNewlines) return offsetOfNewline(node.left, target, base);
        int localTarget = target - leftNewlines;
        if (localTarget < countNewlines(node.text)) {
            int seen = 0;
            for (int index = 0; index < node.text.length(); index++) {
                if (node.text.charAt(index) == '\n' && seen++ == localTarget) return base + leftChars + index;
            }
        }
        return offsetOfNewline(node.right, localTarget - countNewlines(node.text), base + leftChars + node.text.length());
    }

    private static char charAt(Node node, int offset) {
        int leftChars = chars(node.left);
        if (offset < leftChars) return charAt(node.left, offset);
        int textOffset = offset - leftChars;
        if (textOffset < node.text.length()) return node.text.charAt(textOffset);
        return charAt(node.right, textOffset - node.text.length());
    }

    private static void append(Node node, int from, int to, int base, StringBuilder target) {
        if (node == null || from >= base + chars(node) || to <= base) return;
        int leftChars = chars(node.left);
        append(node.left, from, to, base, target);
        int textBase = base + leftChars;
        int start = Math.max(0, from - textBase);
        int end = Math.min(node.text.length(), to - textBase);
        if (end > start) target.append(node.text, start, end);
        append(node.right, from, to, textBase + node.text.length(), target);
    }

    private static Stats prefix(Node node, int requested) {
        if (node == null || requested <= 0) return Stats.EMPTY;
        int leftChars = chars(node.left);
        if (requested <= leftChars) return prefix(node.left, requested);
        Stats result = stats(node.left);
        int textChars = Math.min(node.text.length(), requested - leftChars);
        result = combine(result, stats(node.text, textChars));
        if (requested <= leftChars + node.text.length()) return result;
        return combine(result, prefix(node.right, requested - leftChars - node.text.length()));
    }

    private static Stats stats(Node node) {
        return node == null ? Stats.EMPTY : new Stats(node.newlines, node.trailingCharacters);
    }

    private static Stats stats(String value, int limit) {
        int newlines = 0;
        int trailing = 0;
        int end = Math.min(value.length(), Math.max(0, limit));
        for (int index = 0; index < end; index++) {
            if (value.charAt(index) == '\n') {
                newlines++;
                trailing = 0;
            } else {
                trailing++;
            }
        }
        return new Stats(newlines, trailing);
    }

    private static Stats combine(Stats left, Stats right) {
        return new Stats(left.newlines + right.newlines, right.newlines == 0 ? left.trailingCharacters + right.trailingCharacters : right.trailingCharacters);
    }

    private static int chars(Node node) {
        return node == null ? 0 : node.chars;
    }

    private static int newlines(Node node) {
        return node == null ? 0 : node.newlines;
    }

    private static int countNewlines(String value) {
        int count = 0;
        for (int index = 0; index < value.length(); index++) if (value.charAt(index) == '\n') count++;
        return count;
    }

    private record Parts(Node left, Node right) { }
    private record Stats(int newlines, int trailingCharacters) {
        private static final Stats EMPTY = new Stats(0, 0);
    }

    private static final class Node {
        final Node left;
        final Node right;
        final String text;
        final long priority;
        final int chars;
        final int newlines;
        final int trailingCharacters;

        Node(Node left, Node right, String text, long priority) {
            this.left = left;
            this.right = right;
            this.text = text;
            this.priority = priority;
            this.chars = chars(left) + text.length() + chars(right);
            Stats result = combine(combine(stats(left), stats(text, text.length())), stats(right));
            this.newlines = result.newlines;
            this.trailingCharacters = result.trailingCharacters;
        }
    }
}
