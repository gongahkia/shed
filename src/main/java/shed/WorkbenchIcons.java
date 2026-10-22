package shed;

import java.awt.*;
import javax.swing.*;

/** Small outline actions, painted at UI scale and inheriting their control's foreground. */
enum WorkbenchIcons implements Icon {
    CLOSE, DETACH;

    public int getIconWidth() { return WorkbenchStyle.px(16); }
    public int getIconHeight() { return WorkbenchStyle.px(16); }
    public void paintIcon(Component c, Graphics graphics, int x, int y) {
        Graphics2D g = (Graphics2D) graphics.create();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.translate(x, y); g.scale(getIconWidth() / 16.0, getIconHeight() / 16.0);
        g.setStroke(new BasicStroke(1.5f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g.setColor(c.isEnabled() ? c.getForeground() : UIManager.getColor("shed.muted"));
        if (this == CLOSE) { g.drawLine(4, 4, 12, 12); g.drawLine(12, 4, 4, 12); }
        else { g.drawLine(8, 8, 13, 3); g.drawLine(9, 3, 13, 3); g.drawLine(13, 3, 13, 7);
            g.drawLine(7, 4, 3, 4); g.drawLine(3, 4, 3, 13); g.drawLine(3, 13, 12, 13); g.drawLine(12, 13, 12, 9); }
        g.dispose();
    }
}
