import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class RegisterContent {
    public enum Kind {
        CHARACTER,
        LINE,
        MACRO
    }

    private final String text;
    private final Kind kind;
    private final List<NormalizedKeyStroke> macroKeys;

    private RegisterContent(String text, Kind kind, List<NormalizedKeyStroke> macroKeys) {
        this.text = text == null ? "" : text;
        this.kind = kind;
        this.macroKeys = macroKeys == null ? Collections.emptyList() : new ArrayList<>(macroKeys);
    }

    public static RegisterContent characterWise(String text) {
        return new RegisterContent(text, Kind.CHARACTER, null);
    }

    public static RegisterContent lineWise(String text) {
        return new RegisterContent(text, Kind.LINE, null);
    }

    public static RegisterContent macro(List<NormalizedKeyStroke> macroKeys) {
        return new RegisterContent("", Kind.MACRO, macroKeys);
    }

    public String getText() {
        return text;
    }

    public boolean isLineWise() {
        return kind == Kind.LINE;
    }

    public boolean isMacro() {
        return kind == Kind.MACRO;
    }

    public List<NormalizedKeyStroke> getMacroKeys() {
        return new ArrayList<>(macroKeys);
    }

    public String displayText() {
        if (isMacro()) {
            return "Macro[" + macroKeys.size() + "]";
        }
        String normalized = text.replace("\n", "\\n");
        if (normalized.length() > 80) {
            return normalized.substring(0, 77) + "...";
        }
        return normalized;
    }
}
