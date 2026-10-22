package shed;

import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** A single editor shortcut, with platform-independent Mod and explicit physical modifiers. */
record ShortcutKey(int code, int modifiers, boolean primary) {
    private static final Map<String, Integer> SPECIAL = Map.ofEntries(
        Map.entry("esc", KeyEvent.VK_ESCAPE), Map.entry("enter", KeyEvent.VK_ENTER),
        Map.entry("tab", KeyEvent.VK_TAB), Map.entry("space", KeyEvent.VK_SPACE),
        Map.entry("bs", KeyEvent.VK_BACK_SPACE), Map.entry("del", KeyEvent.VK_DELETE),
        Map.entry("up", KeyEvent.VK_UP), Map.entry("down", KeyEvent.VK_DOWN),
        Map.entry("left", KeyEvent.VK_LEFT), Map.entry("right", KeyEvent.VK_RIGHT),
        Map.entry("home", KeyEvent.VK_HOME), Map.entry("end", KeyEvent.VK_END),
        Map.entry("pageup", KeyEvent.VK_PAGE_UP), Map.entry("pagedown", KeyEvent.VK_PAGE_DOWN),
        Map.entry("minus", KeyEvent.VK_MINUS), Map.entry("plus", KeyEvent.VK_EQUALS),
        Map.entry("comma", KeyEvent.VK_COMMA));
    static final int MASK = InputEvent.CTRL_DOWN_MASK | InputEvent.META_DOWN_MASK
        | InputEvent.ALT_DOWN_MASK | InputEvent.SHIFT_DOWN_MASK;

    static ShortcutKey parse(String value) {
        if (value == null || !value.startsWith("<") || !value.endsWith(">"))
            throw new IllegalArgumentException("shortcut must use <mod-key>, <c-key>, <a-key>, <s-key>, <meta-key> or <f1> notation");
        String key = value.substring(1, value.length() - 1).toLowerCase(Locale.ROOT);
        int modifiers = 0;
        boolean primary = false;
        while (key.indexOf('-') > 0) {
            int separator = key.indexOf('-');
            String modifier = key.substring(0, separator);
            switch (modifier) {
                case "mod" -> primary = true;
                case "c", "ctrl" -> modifiers |= InputEvent.CTRL_DOWN_MASK;
                case "meta", "cmd", "d" -> modifiers |= InputEvent.META_DOWN_MASK;
                case "a", "alt" -> modifiers |= InputEvent.ALT_DOWN_MASK;
                case "s", "shift" -> modifiers |= InputEvent.SHIFT_DOWN_MASK;
                default -> throw new IllegalArgumentException("unknown shortcut modifier: " + modifier);
            }
            key = key.substring(separator + 1);
        }
        key = switch (key) { case "cr" -> "enter"; case "backspace" -> "bs"; case "delete" -> "del"; default -> key; };
        Integer code = SPECIAL.get(key);
        if (code == null && key.matches("f([1-9]|1[0-2])")) code = KeyEvent.VK_F1 + Integer.parseInt(key.substring(1)) - 1;
        if (code == null && key.length() == 1 && Character.isLetterOrDigit(key.charAt(0)))
            code = KeyEvent.getExtendedKeyCodeForChar(Character.toUpperCase(key.charAt(0)));
        if (code == null || code == KeyEvent.VK_UNDEFINED) throw new IllegalArgumentException("unsupported shortcut key: " + key);
        if (primary && (modifiers & (InputEvent.CTRL_DOWN_MASK | InputEvent.META_DOWN_MASK)) != 0)
            throw new IllegalArgumentException("Mod cannot be combined with Ctrl or Meta");
        if ((modifiers & (InputEvent.CTRL_DOWN_MASK | InputEvent.ALT_DOWN_MASK))
            == (InputEvent.CTRL_DOWN_MASK | InputEvent.ALT_DOWN_MASK))
            throw new IllegalArgumentException("Ctrl+Alt is reserved for international keyboard text input");
        return new ShortcutKey(code, modifiers, primary);
    }

    int physicalModifiers() { return modifiers | (primary ? primaryMask() : 0); }
    boolean safeForTextInput() {
        return primary || (modifiers & (InputEvent.CTRL_DOWN_MASK | InputEvent.META_DOWN_MASK)) != 0
            || (code >= KeyEvent.VK_F1 && code <= KeyEvent.VK_F12)
            || SPECIAL.containsValue(code) && code != KeyEvent.VK_SPACE && code != KeyEvent.VK_MINUS
                && code != KeyEvent.VK_EQUALS && code != KeyEvent.VK_COMMA;
    }
    String token() {
        String key = SPECIAL.entrySet().stream().filter(entry -> entry.getValue() == code).map(Map.Entry::getKey).findFirst()
            .orElseGet(() -> code >= KeyEvent.VK_F1 && code <= KeyEvent.VK_F12 ? "f" + (code - KeyEvent.VK_F1 + 1)
                : String.valueOf((char) code).toLowerCase(Locale.ROOT));
        return "<" + (primary ? "mod-" : "") + ((modifiers & InputEvent.CTRL_DOWN_MASK) != 0 ? "c-" : "")
            + ((modifiers & InputEvent.META_DOWN_MASK) != 0 ? "meta-" : "")
            + ((modifiers & InputEvent.ALT_DOWN_MASK) != 0 ? "a-" : "")
            + ((modifiers & InputEvent.SHIFT_DOWN_MASK) != 0 ? "s-" : "") + key + ">";
    }
    static List<String> tokens(KeyEvent event) {
        if (event == null || event.isAltGraphDown() || event.isControlDown() && event.isAltDown()) return List.of();
        int modifiers = event.getModifiersEx() & MASK;
        ShortcutKey physical = new ShortcutKey(event.getKeyCode(), modifiers, false);
        List<String> result = new ArrayList<>();
        result.add(physical.token());
        if ((modifiers & primaryMask()) != 0)
            result.add(new ShortcutKey(event.getKeyCode(), modifiers & ~primaryMask(), true).token());
        return List.copyOf(result);
    }
    static int primaryMask() { return isMac() ? InputEvent.META_DOWN_MASK : InputEvent.CTRL_DOWN_MASK; }
    static boolean isMac() { return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("mac"); }
}
