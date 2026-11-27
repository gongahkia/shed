package shed;
import static org.junit.jupiter.api.Assertions.*;
import java.awt.event.*;
import javax.swing.JTextField;
import java.util.List;
import org.junit.jupiter.api.Test;
class ShortcutEditingTest {
    private KeyEvent key(int code,int mask){return new KeyEvent(new JTextField(),KeyEvent.KEY_PRESSED,0,mask,code,KeyEvent.CHAR_UNDEFINED);}
    @Test void capturePreservesPrimaryModifierAndRejectsTextAltGraphAndModifierOnly(){
        assertEquals("<mod-s-k>",ShortcutEditing.capture(key(KeyEvent.VK_K,ShortcutKey.primaryMask()|InputEvent.SHIFT_DOWN_MASK)));
        assertNull(ShortcutEditing.capture(key(KeyEvent.VK_A,0)));assertNull(ShortcutEditing.capture(key(KeyEvent.VK_SHIFT,InputEvent.SHIFT_DOWN_MASK)));assertNull(ShortcutEditing.capture(key(KeyEvent.VK_Q,InputEvent.CTRL_DOWN_MASK|InputEvent.ALT_DOWN_MASK)));assertEquals("<f5>",ShortcutEditing.capture(key(KeyEvent.VK_F5,0)));
    }
    @Test void collisionChecksComparePhysicalAliasesAndRelevantScopes(){
        var binding=new KeymapOverlay.Binding("keybind.plain.<mod-k>","plain","<mod-k>","action:save","User overlay","Active");
        String physical=ShortcutKey.isMac()?"<meta-k>":"<c-k>";
        assertEquals(List.of(binding),ShortcutEditing.conflicts(List.of(binding),"plain",physical));assertTrue(ShortcutEditing.conflicts(List.of(binding),"emacs",physical).isEmpty());assertTrue(ShortcutEditing.conflicts(List.of(binding),"plain","not-a-key").isEmpty());
    }
}
