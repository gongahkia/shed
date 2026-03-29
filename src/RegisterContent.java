public class RegisterContent {
    private final String text;
    private final boolean lineWise;

    private RegisterContent(String text, boolean lineWise) {
        this.text = text == null ? "" : text;
        this.lineWise = lineWise;
    }

    public static RegisterContent characterWise(String text) {
        return new RegisterContent(text, false);
    }

    public static RegisterContent lineWise(String text) {
        return new RegisterContent(text, true);
    }

    public String getText() {
        return text;
    }

    public boolean isLineWise() {
        return lineWise;
    }

    public String displayText() {
        String normalized = text.replace("\n", "\\n");
        if (normalized.length() > 80) {
            return normalized.substring(0, 77) + "...";
        }
        return normalized;
    }
}
