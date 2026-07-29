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
        List<MultiSelection> ordered = new ArrayList<>(selections);
        ordered.sort(Comparator.comparingInt(MultiSelection::start).reversed().thenComparing(Comparator.comparingInt(MultiSelection::end).reversed()));
        List<MultiSelection> updated = new ArrayList<>();
        for (MultiSelection selection : ordered) {
            String text = area.getText();
            GraphemeEditRange.Range range = resolve(text, selection, backward, forward);
            int delta = replacement.length() - (range.end() - range.start());
            area.replaceRange(replacement, range.start(), range.end());
            for (int index = 0; index < updated.size(); index++) {
                MultiSelection later = updated.get(index);
                updated.set(index, MultiSelection.caret(later.start() + delta));
            }
            updated.add(MultiSelection.caret(range.start() + replacement.length()));
        }
        updated.sort(Comparator.comparingInt(MultiSelection::start));
        return updated;
    }

    private static GraphemeEditRange.Range resolve(String text, MultiSelection selection, boolean backward, boolean forward) {
        if (!selection.collapsed()) {
            return GraphemeEditRange.selection(text, selection.start(), selection.end());
        }
        if (backward) {
            return GraphemeEditRange.previous(text, selection.start());
        }
        if (forward) {
            return GraphemeEditRange.next(text, selection.start());
        }
        int caret = GraphemeBoundary.floor(text, selection.start());
        return new GraphemeEditRange.Range(caret, caret);
    }
}
