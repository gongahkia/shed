package shed;

import java.awt.event.KeyEvent;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import javax.swing.JTextArea;
import javax.swing.text.BadLocationException;

final class MultiSelectionMotionService {
    private MultiSelectionMotionService() { }

    record Result(MultiSelection primary, List<MultiSelection> extras) { }

    static Result move(JTextArea area, MultiSelection primary, List<MultiSelection> extras, int keyCode) {
        String text = area.getText();
        MultiSelection movedPrimary = moveOne(area, text, primary, keyCode);
        List<MultiSelection> movedExtras = new ArrayList<>();
        Set<MultiSelection> seen = new HashSet<>();
        seen.add(movedPrimary);
        for (MultiSelection selection : extras) {
            MultiSelection moved = moveOne(area, text, selection, keyCode);
            if (seen.add(moved)) movedExtras.add(moved);
        }
        movedExtras.sort(Comparator.comparingInt(MultiSelection::start));
        return new Result(movedPrimary, movedExtras);
    }

    private static MultiSelection moveOne(JTextArea area, String text, MultiSelection selection, int keyCode) {
        boolean backward = keyCode == KeyEvent.VK_LEFT || keyCode == KeyEvent.VK_UP;
        int position = backward ? selection.start() : selection.end();
        if (keyCode == KeyEvent.VK_LEFT || keyCode == KeyEvent.VK_RIGHT) {
            if (!selection.collapsed()) return MultiSelection.caret(position);
            return MultiSelection.caret(backward ? GraphemeBoundary.previous(text, position)
                : GraphemeBoundary.next(text, position));
        }
        try {
            int line = area.getLineOfOffset(position);
            int targetLine = keyCode == KeyEvent.VK_UP ? Math.max(0, line - 1)
                : Math.min(area.getLineCount() - 1, line + 1);
            int column = position - area.getLineStartOffset(line);
            int targetStart = area.getLineStartOffset(targetLine);
            int targetEnd = targetLine == area.getLineCount() - 1 ? text.length()
                : area.getLineEndOffset(targetLine) - 1;
            int target = Math.min(targetStart + column, Math.max(targetStart, targetEnd));
            return MultiSelection.caret(GraphemeBoundary.isBoundary(text, target) ? target
                : GraphemeBoundary.floor(text, target));
        } catch (BadLocationException error) {
            return MultiSelection.caret(position);
        }
    }
}
