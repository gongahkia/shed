package shed;

import java.awt.Component;
import java.awt.FontMetrics;
import java.awt.Insets;
import java.awt.Window;
import javax.swing.JComponent;
import javax.swing.SwingUtilities;

/** Spacing for the supported chrome surfaces; independent of font zoom and source layout. */
enum UiDensity {
    COMPACT("compact", 34, 16, 6, 10, 20, 6, 32, 14, 3, 2, 188),
    DEFAULT("default", 40, 16, 8, 14, 22, 8, 40, 12, 5, 4, 220),
    COMFORTABLE("comfortable", 48, 24, 10, 18, 28, 14, 48, 20, 7, 6, 252);

    static final String PICKER_LIST = "shed.density.picker.list";
    static final String PICKER_FILTER = "shed.density.picker.filter";
    final String id;
    final int controlMinimum, controlPadding, tabVertical, tabHorizontal, treeMinimum, treePadding;
    final int pickerMinimum, pickerPadding, statusPadding, commandPadding, editorTabWidth;

    UiDensity(String id, int controlMinimum, int controlPadding, int tabVertical, int tabHorizontal,
              int treeMinimum, int treePadding, int pickerMinimum, int pickerPadding,
              int statusPadding, int commandPadding, int editorTabWidth) {
        this.id = id; this.controlMinimum = controlMinimum; this.controlPadding = controlPadding;
        this.tabVertical = tabVertical; this.tabHorizontal = tabHorizontal;
        this.treeMinimum = treeMinimum; this.treePadding = treePadding;
        this.pickerMinimum = pickerMinimum; this.pickerPadding = pickerPadding;
        this.statusPadding = statusPadding; this.commandPadding = commandPadding; this.editorTabWidth = editorTabWidth;
    }

    static UiDensity parse(String value) {
        if (value == null) return null;
        return switch (value) {
            case "compact" -> COMPACT;
            case "default" -> DEFAULT;
            case "comfortable" -> COMFORTABLE;
            default -> null;
        };
    }

    int controlHeight(FontMetrics font, double zoom) {
        return Math.max(UiZoom.scale(controlMinimum, zoom), font.getHeight() + UiZoom.scale(controlPadding, zoom));
    }

    int treeHeight(FontMetrics font, double zoom) {
        int height = Math.max(UiZoom.scale(treeMinimum, zoom), font.getFont().getSize() + UiZoom.scale(treePadding, zoom));
        return this == DEFAULT ? height : Math.max(height, font.getHeight() + 2);
    }

    int pickerHeight(JComponent component, double zoom) {
        int padding = UiZoom.scale(pickerPadding, zoom);
        if (this != DEFAULT) {
            Insets insets = component.getInsets();
            // Existing picker renderers/filter borders contain some unscaled padding.
            padding = Math.max(padding, Math.max(14, insets.top + insets.bottom));
        }
        return Math.max(UiZoom.scale(pickerMinimum, zoom), component.getFontMetrics(component.getFont()).getHeight() + padding);
    }

    static Texteditor owner(Component component) {
        if (component == null) return null;
        Window window = component instanceof Window w ? w : SwingUtilities.getWindowAncestor(component);
        while (window != null) {
            if (window instanceof Texteditor editor) return editor;
            window = window.getOwner();
        }
        return null;
    }

    static UiDensity forComponent(Component component) {
        Texteditor editor = owner(component);
        return editor == null ? DEFAULT : editor.configManager.getUiDensity();
    }
}
