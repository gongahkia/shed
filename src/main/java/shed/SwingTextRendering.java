package shed;

import java.awt.RenderingHints;
import java.awt.Toolkit;
import java.util.Map;
import javax.swing.UIDefaults;
import javax.swing.UIManager;

/** Supplies smoothing when a Linux desktop does not publish Swing font hints. */
final class SwingTextRendering {
    static void install(){
        Object hints=Toolkit.getDefaultToolkit().getDesktopProperty("awt.font.desktophints");
        install(UIManager.getDefaults(),hints instanceof Map<?,?> map?map:Map.of());
    }
    static void install(UIDefaults defaults,Map<?,?> hints){
        var key=RenderingHints.KEY_TEXT_ANTIALIASING;Object value=hints.get(key);
        defaults.put(key,value!=null&&key.isCompatibleValue(value)&&value!=RenderingHints.VALUE_TEXT_ANTIALIAS_DEFAULT?value:RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        var contrast=RenderingHints.KEY_TEXT_LCD_CONTRAST;Object lcd=hints.get(contrast);
        if(lcd!=null&&contrast.isCompatibleValue(lcd))defaults.put(contrast,lcd);
    }
}
