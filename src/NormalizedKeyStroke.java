import java.awt.Component;
import java.awt.event.KeyEvent;
import java.util.Objects;

public class NormalizedKeyStroke {
    private final char keyChar;
    private final int keyCode;
    private final int modifiers;

    public NormalizedKeyStroke(char keyChar, int keyCode, int modifiers) {
        this.keyChar = keyChar;
        this.keyCode = keyCode;
        this.modifiers = modifiers;
    }

    public static NormalizedKeyStroke fromKeyEvent(KeyEvent event) {
        return new NormalizedKeyStroke(event.getKeyChar(), event.getKeyCode(), event.getModifiersEx());
    }

    public KeyEvent toKeyEvent(Component source) {
        return new KeyEvent(source, KeyEvent.KEY_PRESSED, System.currentTimeMillis(), modifiers, keyCode, keyChar);
    }

    @Override
    public boolean equals(Object other) {
        if (!(other instanceof NormalizedKeyStroke)) {
            return false;
        }
        NormalizedKeyStroke that = (NormalizedKeyStroke) other;
        return keyChar == that.keyChar && keyCode == that.keyCode && modifiers == that.modifiers;
    }

    @Override
    public int hashCode() {
        return Objects.hash(keyChar, keyCode, modifiers);
    }
}
