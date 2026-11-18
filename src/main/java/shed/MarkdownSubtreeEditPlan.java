package shed;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import static shed.MarkdownHeadingEditPlan.Status;

/** A bounded source splice derived only from verified heading-marker changes. */
record MarkdownSubtreeEditPlan(MarkdownSemanticSnapshot.Source source, MarkdownSourceRange subtree,
                               List<Marker> markers, MarkdownSourceRange range, String expected,
                               String replacement, boolean demote, int dot, int mark) {
    static final int MAX_REPLACEMENT_CHARACTERS = 64 * 1024;
    static final int MAX_HEADINGS = 512;
    record Marker(MarkdownSourceRange range, String expected, String replacement) { }
    record Result(Status status, Optional<MarkdownSubtreeEditPlan> edit, String message) {
        Result {
            Objects.requireNonNull(status); Objects.requireNonNull(edit); Objects.requireNonNull(message);
            if ((status == Status.READY) != edit.isPresent()) throw new IllegalArgumentException("Inconsistent subtree plan");
        }
    }

    MarkdownSubtreeEditPlan {
        Objects.requireNonNull(source); Objects.requireNonNull(subtree); Objects.requireNonNull(range);
        Objects.requireNonNull(expected); Objects.requireNonNull(replacement);
        markers = List.copyOf(markers);
        subtree.validate(source.text()); range.validate(source.text());
        new MarkdownSourceRange(dot, dot).validate(source.text());
        new MarkdownSourceRange(mark, mark).validate(source.text());
        if (!source.completeSource() || markers.isEmpty() || markers.size() > MAX_HEADINGS
            || range.length() > MAX_REPLACEMENT_CHARACTERS || replacement.length() > MAX_REPLACEMENT_CHARACTERS
            || range.start() < subtree.start() || range.end() > subtree.end()
            || !range.equals(new MarkdownSourceRange(markers.getFirst().range().start(), markers.getLast().range().end()))
            || !expected.equals(range.text(source.text()))) throw new IllegalArgumentException("Invalid subtree source splice");
        int previous = range.start();
        for (var marker : markers) {
            if (marker.range().start() < previous || marker.range().end() > range.end())
                throw new IllegalArgumentException("Overlapping subtree markers");
            // Reuse the established exact-marker and level validation, without another transaction type.
            new MarkdownHeadingEditPlan(source, marker.range(), marker.expected(), marker.replacement(), demote, dot, mark);
            previous = marker.range().end();
        }
        if (!replacement.equals(splice(expected, range.start(), markers)))
            throw new IllegalArgumentException("Subtree replacement changes non-marker source");
    }

    static Result plan(MarkdownSemanticSnapshot semantic, int dot, int mark, boolean demote) {
        var first = MarkdownHeadingEditPlan.plan(semantic, dot, mark, demote);
        if (first.edit().isEmpty()) return rejected(first.status(), first.message());
        var source = semantic.source(); var text = source.text();
        if (text.positionAt(dot).line() != text.positionAt(mark).line())
            return rejected(Status.UNSUPPORTED, "Markdown subtree edit unavailable: selection crosses heading lines");
        var opening = first.edit().orElseThrow();
        int root = -1;
        for (int i = 0; i < semantic.headings().size(); i++) {
            if (semantic.headings().get(i).marker().equals(Optional.of(opening.range()))) { root = i; break; }
        }
        if (root < 0) return rejected(Status.UNVERIFIED, "Markdown subtree heading unavailable");
        int level = semantic.headings().get(root).level();
        int start = text.lineStartOffset(text.positionAt(opening.range().start()).line()), end = text.length();
        List<Marker> changes = new ArrayList<>();
        List<Integer> headingOffsets = new ArrayList<>();
        try {
            int previous = -1;
            for (int i = root; i < semantic.headings().size(); i++) {
                var heading = semantic.headings().get(i);
                var segments = heading.location().segments();
                if (segments.isEmpty()) return rejected(Status.UNVERIFIED, "Markdown subtree heading location unavailable");
                int offset = segments.getFirst().start();
                if (offset <= previous) return rejected(Status.UNVERIFIED, "Unordered Markdown subtree headings");
                previous = offset; headingOffsets.add(offset);
                if (i > root && heading.level() <= level) {
                    end = text.lineStartOffset(text.positionAt(offset).line()); break;
                }
                if (heading.marker().isEmpty()) return rejected(Status.UNVERIFIED, "Markdown subtree marker unavailable");
                var marker = heading.marker().orElseThrow(); marker.validate(text);
                String expected = marker.text(text);
                if (heading.level() < 1 || heading.level() > 6 || !expected.equals("#".repeat(heading.level())))
                    return rejected(Status.UNSUPPORTED, "Markdown subtree edit unavailable: unsupported heading style");
                // Standalone ATX indentation is at most three spaces; avoid repeated snapshot line scans.
                int lineStart = marker.start();
                while (lineStart > 0 && marker.start() - lineStart <= 3 && text.charAt(lineStart - 1) != '\n') lineStart--;
                if (segments.size() != 1 || marker.start() < segments.getFirst().start() || marker.end() > segments.getFirst().end()
                    || marker.start() - lineStart > 3 || !text.substring(lineStart, marker.start() - lineStart).chars().allMatch(c -> c == ' '))
                    return rejected(Status.UNSUPPORTED, "Markdown subtree edit unavailable: container heading");
                int nextLevel = heading.level() + (demote ? 1 : -1);
                if (nextLevel < 1 || nextLevel > 6) return rejected(Status.BOUNDARY, "Markdown subtree exceeds h1–h6; no headings changed");
                if (changes.size() == MAX_HEADINGS || marker.end() - opening.range().start() > MAX_REPLACEMENT_CHARACTERS)
                    return rejected(Status.UNSUPPORTED, "Markdown subtree edit exceeds operation-size limit");
                changes.add(new Marker(marker, expected, "#".repeat(nextLevel)));
            }
            // Container spans are used only to reject ambiguous hierarchy, never as mutation ranges.
            for (var block : semantic.blocks()) {
                if (block.kind() != MarkdownSemanticSnapshot.BlockKind.LIST_ITEM
                    && block.kind() != MarkdownSemanticSnapshot.BlockKind.BLOCK_QUOTE) continue;
                var segments = block.location().segments();
                if (segments.isEmpty()) return rejected(Status.UNVERIFIED, "Markdown container location unavailable");
                int low = java.util.Collections.binarySearch(headingOffsets, segments.getFirst().start());
                if (low < 0) low = -low - 1;
                if (low < headingOffsets.size() && headingOffsets.get(low) < segments.getLast().end())
                    return rejected(Status.UNSUPPORTED, "Markdown subtree edit unavailable: container heading");
            }
            var range = new MarkdownSourceRange(changes.getFirst().range().start(), changes.getLast().range().end());
            String expected = range.text(text), replacement = splice(expected, range.start(), changes);
            if (replacement.length() > MAX_REPLACEMENT_CHARACTERS)
                return rejected(Status.UNSUPPORTED, "Markdown subtree edit exceeds operation-size limit");
            var plan = new MarkdownSubtreeEditPlan(source, new MarkdownSourceRange(start, end), changes,
                range, expected, replacement, demote, dot, mark);
            return new Result(Status.READY, Optional.of(plan), demote ? "Demoted subtree" : "Promoted subtree");
        } catch (IllegalArgumentException invalid) {
            return rejected(Status.UNVERIFIED, "Markdown subtree marker or boundary unavailable");
        }
    }

    long undoBytes() {
        int prefix = 0, suffix = 0;
        while (prefix < expected.length() && prefix < replacement.length() && expected.charAt(prefix) == replacement.charAt(prefix)) prefix++;
        while (suffix < expected.length() - prefix && suffix < replacement.length() - prefix
            && expected.charAt(expected.length() - suffix - 1) == replacement.charAt(replacement.length() - suffix - 1)) suffix++;
        int removed = expected.length() - prefix - suffix, inserted = replacement.length() - prefix - suffix;
        return (removed == 0 ? 0 : 64L + 2L * removed) + (inserted == 0 ? 0 : 64L + 2L * inserted);
    }

    int mapPosition(int position) {
        int delta = 0;
        for (var marker : markers) {
            if (position <= marker.range().start()) break;
            if (position < marker.range().end()) return marker.range().start() + delta
                + Math.min(position - marker.range().start(), marker.replacement().length());
            delta += marker.replacement().length() - marker.expected().length();
        }
        return position + delta;
    }

    private static String splice(String expected, int start, List<Marker> changes) {
        StringBuilder result = new StringBuilder(expected.length() + changes.size());
        int cursor = 0;
        for (var marker : changes) {
            result.append(expected, cursor, marker.range().start() - start).append(marker.replacement());
            cursor = marker.range().end() - start;
        }
        return result.append(expected, cursor, expected.length()).toString();
    }

    private static Result rejected(Status status, String message) { return new Result(status, Optional.empty(), message); }
}
