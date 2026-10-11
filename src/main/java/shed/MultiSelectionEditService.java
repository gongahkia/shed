package shed;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import javax.swing.JTextArea;

final class MultiSelectionEditService {
    private MultiSelectionEditService() {
    }

    static List<MultiSelection> insert(JTextArea area, List<MultiSelection> selections, String replacement) {
        return replace(area, selections, replacement, false, false);
    }

    static List<MultiSelection> backspace(JTextArea area, List<MultiSelection> selections) {
        return replace(area, selections, "", true, false);
    }

    static List<MultiSelection> delete(JTextArea area, List<MultiSelection> selections) {
        return replace(area, selections, "", false, true);
    }

    private static List<MultiSelection> replace(JTextArea area, List<MultiSelection> selections, String replacement,
                                                boolean backward, boolean forward) {
        String text = area.getText();
        validate(text, selections);
        record Resolved(GraphemeEditRange.Range range) { }
        List<Resolved> ordered = new ArrayList<>(selections.size());
        for (MultiSelection selection : selections) ordered.add(new Resolved(resolve(text, selection, backward, forward)));
        ordered.sort(Comparator.comparingInt((Resolved value) -> value.range().start()));
        for (int index = 1; index < ordered.size(); index++) {
            if (ordered.get(index - 1).range().end() > ordered.get(index).range().start()) {
                throw new IllegalArgumentException("multi-selection edits overlap");
            }
        }
        List<MultiSelection> updated = new ArrayList<>(ordered.size());
        int adjustment = 0;
        for (Resolved edit : ordered) {
            GraphemeEditRange.Range range = edit.range();
            int delta = replacement.length() - (range.end() - range.start());
            updated.add(MultiSelection.caret(range.start() + replacement.length() + adjustment));
            adjustment += delta;
        }
        for (int index = ordered.size() - 1; index >= 0; index--) {
            GraphemeEditRange.Range range = ordered.get(index).range();
            int delta = replacement.length() - (range.end() - range.start());
            if (delta != 0 || !range.empty()) area.replaceRange(replacement, range.start(), range.end());
        }
        return updated;
    }

    private static void validate(String text, List<MultiSelection> selections) {
        List<MultiSelection> ordered = new ArrayList<>(selections);
        ordered.sort(Comparator.comparingInt(MultiSelection::start).thenComparingInt(MultiSelection::end));
        MultiSelection previous = null;
        for (MultiSelection selection : ordered) {
            if (selection.end() > text.length() || !GraphemeBoundary.isBoundary(text, selection.start())
                || !GraphemeBoundary.isBoundary(text, selection.end())) {
                throw new IllegalArgumentException("multi-selection range is outside text or not at a grapheme boundary");
            }
            if (previous != null && (selection.start() < previous.end()
                || selection.start() == previous.start() && (selection.collapsed() || previous.collapsed()))) {
                throw new IllegalArgumentException("multi-selection ranges overlap");
            }
            previous = selection;
        }
    }

    private static GraphemeEditRange.Range resolve(String text, MultiSelection selection, boolean backward, boolean forward) {
        if (!selection.collapsed()) {
            return new GraphemeEditRange.Range(selection.start(), selection.end());
        }
        if (backward) {
            return GraphemeEditRange.previous(text, selection.start());
        }
        if (forward) {
            return GraphemeEditRange.next(text, selection.start());
        }
        return new GraphemeEditRange.Range(selection.start(), selection.start());
    }
}
