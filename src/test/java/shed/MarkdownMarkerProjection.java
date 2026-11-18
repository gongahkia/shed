package shed;

import java.util.List;
import javax.swing.text.Position;

/** Coordinate-only concealment experiment. It never edits source or installs a view. */
final class MarkdownMarkerProjection {
    private final int sourceLength;
    private final List<MarkdownSourceRange> hidden;
    MarkdownMarkerProjection(VersionedTextSnapshot source, List<MarkdownSourceRange> hidden) {
        if (hidden.size() > 64) throw new IllegalArgumentException("Marker experiment budget");
        int previous = -1;
        for (var range : hidden) {
            range.validate(source);
            if (range.length() == 0 || range.start() < previous) throw new IllegalArgumentException("Invalid concealed intervals");
            previous = range.end();
        }
        sourceLength = source.length(); this.hidden = List.copyOf(hidden);
    }
    int visibleLength() { return sourceLength - hidden.stream().mapToInt(MarkdownSourceRange::length).sum(); }
    int visible(int sourceOffset) {
        if (sourceOffset < 0 || sourceOffset > sourceLength) throw new IllegalArgumentException("Source offset outside projection");
        int removed = 0;
        for (var range : hidden) removed += Math.max(0, Math.min(sourceOffset, range.end()) - range.start());
        return sourceOffset - removed;
    }
    int source(int visualOffset, Position.Bias affinity) {
        if (visualOffset < 0 || visualOffset > visibleLength()) throw new IllegalArgumentException("Visual offset outside projection");
        int removed = 0;
        for (int i = 0; i < hidden.size(); i++) {
            var range = hidden.get(i); int collapsed = range.start() - removed;
            if (visualOffset < collapsed) break;
            if (visualOffset == collapsed && affinity == Position.Bias.Backward) return range.start();
            removed += range.length();
        }
        return visualOffset + removed;
    }
}
