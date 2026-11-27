package shed;

import java.awt.event.KeyEvent;
import java.util.List;

/** Physical shortcut matching shares the runtime key parser and international-input exclusions. */
final class ShortcutEditing {
    private ShortcutEditing() { }
    static String capture(KeyEvent event) {
        if(event.getKeyCode()==KeyEvent.VK_SHIFT||event.getKeyCode()==KeyEvent.VK_CONTROL||event.getKeyCode()==KeyEvent.VK_META||event.getKeyCode()==KeyEvent.VK_ALT)return null;
        var tokens=ShortcutKey.tokens(event);if(tokens.isEmpty())return null;
        String value=tokens.getLast();return ShortcutKey.parse(value).safeForTextInput()?value:null;
    }
    static List<KeymapOverlay.Binding> conflicts(List<KeymapOverlay.Binding> bindings,String scope,String key) {
        ShortcutKey wanted;try{wanted=ShortcutKey.parse(key);}catch(IllegalArgumentException invalid){return List.of();}
        return bindings.stream().filter(b->b.scope().equals(scope)||b.scope().equals("global")||scope.equals("global")).filter(b->{try{var actual=ShortcutKey.parse(b.lhs());return actual.code()==wanted.code()&&actual.physicalModifiers()==wanted.physicalModifiers();}catch(IllegalArgumentException invalid){return false;}}).toList();
    }
}
