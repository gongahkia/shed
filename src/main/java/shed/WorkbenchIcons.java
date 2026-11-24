package shed;

import java.awt.*;
import javax.swing.*;

/** Small outline actions, painted at UI scale and inheriting their control's foreground. */
enum WorkbenchIcons implements Icon {
    CLOSE, DETACH, SEARCH, COMMANDS, SPLIT, TOOLS, SETTINGS, PLAY, STOP, REFRESH, PREVIOUS, NEXT, ADD, SEND, COPY;

    public int getIconWidth() { return WorkbenchStyle.px(16); }
    public int getIconHeight() { return WorkbenchStyle.px(16); }
    public void paintIcon(Component c, Graphics graphics, int x, int y) {
        Graphics2D g = (Graphics2D) graphics.create();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.translate(x, y); g.scale(getIconWidth() / 16.0, getIconHeight() / 16.0);
        g.setStroke(new BasicStroke(1.5f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g.setColor(c.isEnabled() ? c.getForeground() : UIManager.getColor("shed.muted"));
        if (this == PLAY) {g.drawLine(5,3,12,8);g.drawLine(12,8,5,13);g.drawLine(5,13,5,3);}
        else if(this == STOP){g.drawRect(4,4,8,8);}
        else if(this == REFRESH){g.drawArc(3,3,10,10,40,290);g.drawLine(12,2,12,6);g.drawLine(12,6,8,6);}
        else if(this == PREVIOUS || this == NEXT){int a=this==NEXT?5:11,b=this==NEXT?11:5;g.drawLine(a,3,b,8);g.drawLine(b,8,a,13);}
        else if(this == ADD){g.drawLine(3,8,13,8);g.drawLine(8,3,8,13);}
        else if(this == SEND){g.drawLine(3,3,14,8);g.drawLine(14,8,3,13);g.drawLine(3,13,5,8);g.drawLine(5,8,3,3);g.drawLine(5,8,14,8);}
        else if(this == COPY){g.drawRect(6,5,7,9);g.drawLine(3,11,3,2);g.drawLine(3,2,10,2);}
        else if (this == CLOSE) { g.drawLine(4, 4, 12, 12); g.drawLine(12, 4, 4, 12); }
        else if (this == SEARCH) { g.drawOval(3, 2, 8, 8); g.drawLine(10, 10, 14, 14); }
        else if (this == COMMANDS) { g.drawLine(3, 4, 7, 8); g.drawLine(7, 8, 3, 12); g.drawLine(9, 12, 14, 12); }
        else if (this == SPLIT) { g.drawRect(2, 3, 12, 10); g.drawLine(8, 3, 8, 13); }
        else if (this == TOOLS) { g.drawRect(2, 3, 12, 10); g.drawLine(2, 9, 14, 9); }
        else if (this == SETTINGS) { g.drawLine(2, 4, 14, 4); g.drawLine(2, 8, 14, 8); g.drawLine(2, 12, 14, 12);
            g.drawOval(5, 2, 3, 4); g.drawOval(10, 6, 3, 4); g.drawOval(3, 10, 3, 4); }
        else { g.drawLine(8, 8, 13, 3); g.drawLine(9, 3, 13, 3); g.drawLine(13, 3, 13, 7);
            g.drawLine(7, 4, 3, 4); g.drawLine(3, 4, 3, 13); g.drawLine(3, 13, 12, 13); g.drawLine(12, 13, 12, 9); }
        g.dispose();
    }
}
