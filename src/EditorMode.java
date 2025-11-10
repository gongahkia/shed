// Editor Mode Enum
// Defines the valid modes for Shed and their properties

import java.awt.Color;

public enum EditorMode {
    NORMAL("normal mode", "#BC0E4C", false),
    INSERT("insert mode", "#354F60", true),
    VISUAL("visual mode", "#2E8B57", false),
    REPLACE("replace mode", "#8B4513", true),
    COMMAND("command mode", "#FFC501", false);

    private final String displayName;
    private final String backgroundColor;
    private final boolean editable;

    EditorMode(String displayName, String backgroundColor, boolean editable) {
        this.displayName = displayName;
        this.backgroundColor = backgroundColor;
        this.editable = editable;
    }

    public String getDisplayName() {
        return displayName;
    }

    public Color getBackgroundColor() {
        return Color.decode(backgroundColor);
    }

    public boolean isEditable() {
        return editable;
    }

    // Convert from old integer-based mode system
    public static EditorMode fromInt(int mode) {
        switch(mode) {
            case 0: return NORMAL;
            case 1: return INSERT;
            case 2: return COMMAND;
            default: return NORMAL;
        }
    }
}
