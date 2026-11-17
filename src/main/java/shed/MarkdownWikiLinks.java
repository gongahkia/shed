package shed;

import java.util.*;
import org.commonmark.node.*;

/** Bounded recognition in CommonMark-admitted text, using original verified spans. */
final class MarkdownWikiLinks {
    static final int MAX_TOKEN = 4096;
    static final int MAX_LINKS = 4096;
    private final VersionedTextSnapshot source;
    private final Map<Integer, MarkdownSemanticSnapshot.WikiLink> found = new TreeMap<>();
    private final Set<Integer> unavailable = new HashSet<>();
    private final Map<Node, Boolean> opaque = new IdentityHashMap<>();
    private int omitted;
    MarkdownWikiLinks(VersionedTextSnapshot source) { this.source = source; }
    static boolean potential(Node node) { return node instanceof Text t && t.getLiteral().contains("[[") || node instanceof Link; }

    void collect(Node node, MarkdownSemanticSnapshot.Location location) {
        for (Node parent = node.getParent(); parent != null; parent = parent.getParent()) {
            if (parent instanceof Link || parent instanceof Image || parent instanceof HtmlBlock) return;
            if (opaque.computeIfAbsent(parent, p -> {
                for (Node child = p.getFirstChild(); child != null; child = child.getNext()) if (child instanceof HtmlInline || child instanceof Text t && t.getLiteral().indexOf('$') >= 0) return true;
                return false;
            })) {
                if (node instanceof Text t && t.getLiteral().contains("[[")) missing(-1 - unavailable.size());
                return;
            }
        }
        if (location.segments().isEmpty()) {
            if (node instanceof Text t && t.getLiteral().contains("[[")) missing(-1 - unavailable.size());
            return;
        }
        if (node instanceof Link && location.segments().size() == 1) {
            var inner = location.segments().getFirst();
            if (inner.start() > 0 && inner.end() < source.length() && source.charAt(inner.start() - 1) == '[' && source.charAt(inner.end()) == ']') {
                int start = inner.start() - 1, end = inner.end() + 1;
                if (end - start <= MAX_TOKEN && !escaped(start)) accept(start, source.substring(start, end - start));
            }
            return;
        }
        for (var segment : location.segments()) {
            String raw = segment.text(source);
            int opening = -1;
            for (int at = 0; at + 1 < raw.length(); at++) {
                if (opening < 0 && raw.charAt(at) == '[' && raw.charAt(at + 1) == '[' && !escaped(segment.start() + at)) {
                    opening = at++; continue;
                }
                if (opening >= 0 && raw.charAt(at) == ']' && raw.charAt(at + 1) == ']') {
                    if (at + 2 - opening <= MAX_TOKEN) accept(segment.start() + opening, raw.substring(opening, at + 2));
                    else missing(segment.start() + opening);
                    opening = -1; at++;
                }
            }
            if (opening >= 0) {
                int start = segment.start() + opening;
                String look = source.substring(start, Math.min(MAX_TOKEN, source.length() - start)); int end = look.indexOf("]]");
                if (end >= 0 && valid(look.substring(0, end + 2)) != null) missing(start);
            }
        }
    }
    private boolean accept(int start, String token) {
        String body = valid(token);
        if (body == null) return false;
        if (!found.containsKey(start) && found.size() >= MAX_LINKS) { missing(start); return false; }
        int pipe = body.indexOf('|'); String destination = (pipe < 0 ? body : body.substring(0, pipe)).strip();
        Optional<String> alias = pipe < 0 ? Optional.empty() : Optional.of(body.substring(pipe + 1));
        int hash = destination.indexOf('#'); String path = hash < 0 ? destination : destination.substring(0, hash);
        Optional<String> fragment = hash < 0 ? Optional.empty() : Optional.of(destination.substring(hash + 1));
        var range = new MarkdownSourceRange(start, start + token.length()).validate(source);
        var content = new MarkdownSourceRange(start + 2, range.end() - 2).validate(source);
        found.putIfAbsent(start, new MarkdownSemanticSnapshot.WikiLink(destination, path, fragment, alias, range, content));
        unavailable.remove(start); return true;
    }
    private static String valid(String token) {
        if (!token.startsWith("[[") || !token.endsWith("]]")) return null;
        String body = token.substring(2, token.length() - 2);
        if (body.isBlank() || body.indexOf('[') >= 0 || body.indexOf(']') >= 0 || body.indexOf('\\') >= 0
            || body.indexOf('\n') >= 0 || body.indexOf('\r') >= 0 || body.indexOf('\0') >= 0
            || body.indexOf('|') != body.lastIndexOf('|')) return null;
        int pipe = body.indexOf('|'); String destination = (pipe < 0 ? body : body.substring(0, pipe)).strip();
        if (destination.isEmpty() || destination.equals("#") || pipe >= 0 && body.substring(pipe + 1).isBlank()) return null;
        return body;
    }
    private boolean escaped(int start) {
        int count = 0; while (start > 0 && source.charAt(--start) == '\\') count++; return count % 2 != 0;
    }
    List<MarkdownSemanticSnapshot.WikiLink> links() { return List.copyOf(found.values()); }
    private void missing(int start) { if (unavailable.size() < MAX_LINKS) unavailable.add(start); else omitted++; }
    int unavailable() { return unavailable.size() + omitted; }
}
