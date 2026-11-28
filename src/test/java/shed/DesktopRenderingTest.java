package shed;

import static org.junit.jupiter.api.Assertions.*;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.util.*;
import javax.swing.*;
import org.junit.jupiter.api.Test;

/** Offscreen component paints only; no desktop window or native shell is opened. */
class DesktopRenderingTest {
    @Test void smoothingUsesDesktopHintsWithASafeFallback(){
        var defaults=new UIDefaults();var aa=RenderingHints.KEY_TEXT_ANTIALIASING;
        SwingTextRendering.install(defaults,Map.of());assertEquals(RenderingHints.VALUE_TEXT_ANTIALIAS_ON,defaults.get(aa));
        SwingTextRendering.install(defaults,Map.of(aa,RenderingHints.VALUE_TEXT_ANTIALIAS_LCD_HRGB,RenderingHints.KEY_TEXT_LCD_CONTRAST,160));
        assertEquals(RenderingHints.VALUE_TEXT_ANTIALIAS_LCD_HRGB,defaults.get(aa));assertEquals(160,defaults.get(RenderingHints.KEY_TEXT_LCD_CONTRAST));
        SwingTextRendering.install(defaults,Map.of(aa,"invalid"));assertEquals(RenderingHints.VALUE_TEXT_ANTIALIAS_ON,defaults.get(aa));
        SwingTextRendering.install(defaults,Map.of(aa,RenderingHints.VALUE_TEXT_ANTIALIAS_OFF));assertEquals(RenderingHints.VALUE_TEXT_ANTIALIAS_OFF,defaults.get(aa));
    }
    @Test void smoothingFallbackReachesSwingLabels()throws Exception{
        SwingUtilities.invokeAndWait(()->{
            var key=RenderingHints.KEY_TEXT_ANTIALIASING;Object before=UIManager.get(key);
            try{
                SwingTextRendering.install(UIManager.getDefaults(),Map.of());var label=new JLabel("Settings navigation");
                label.setUI(new javax.swing.plaf.basic.BasicLabelUI());label.setFont(new Font(Font.DIALOG,0,20));label.setOpaque(true);label.setBackground(Color.WHITE);label.setForeground(Color.BLACK);label.setSize(260,40);
                assertEquals(RenderingHints.VALUE_TEXT_ANTIALIAS_ON,label.getClientProperty(key));
                var image=new BufferedImage(260,40,BufferedImage.TYPE_INT_RGB);var g=image.createGraphics();try{label.paint(g);}finally{g.dispose();}
                int softened=0;for(int y=0;y<40;y++)for(int x=0;x<260;x++){int rgb=image.getRGB(x,y)&0xffffff;if(rgb!=0&&rgb!=0xffffff)softened++;}
                assertTrue(softened>20,"Fallback must render antialiased text, not binary jagged glyphs");
            }finally{UIManager.put(key,before);}
        });
    }
    @Test void bundledCodiconsPaintWithThemeColorWithinBoundsAtBothDisplayScales()throws Exception{
        SwingUtilities.invokeAndWait(()->{
            Object before=UIManager.get("shed.zoom");
            try{for(double zoom:new double[]{1,1.25,2}){UIManager.put("shed.zoom",zoom);for(var icon:WorkbenchIcons.values())for(int scale:new int[]{1,2}){
                int size=icon.getIconWidth();var image=new BufferedImage((size+4)*scale,(size+4)*scale,BufferedImage.TYPE_INT_ARGB);var g=image.createGraphics();var label=new JLabel();label.setForeground(new Color(0x27815C));
                try{g.scale(scale,scale);icon.paintIcon(label,g,2,2);}finally{g.dispose();}
                int pixels=0;for(int y=0;y<image.getHeight();y++)for(int x=0;x<image.getWidth();x++){var c=new Color(image.getRGB(x,y),true);if(c.getAlpha()>0){assertTrue(x>=2*scale&&x<(size+2)*scale&&y>=2*scale&&y<(size+2)*scale,icon+" escaped its bounds");pixels++;}if(c.getAlpha()>50)assertTrue(c.getGreen()>c.getRed()&&c.getGreen()>c.getBlue(),icon+" ignored foreground");}
                assertTrue(pixels>5,"Empty bundled icon: "+icon);
            }}}finally{UIManager.put("shed.zoom",before);}
        });
    }
    @Test void workbenchButtonTextUsesTheSameSmoothingAsLabels()throws Exception{
        SwingUtilities.invokeAndWait(()->{
            var key=RenderingHints.KEY_TEXT_ANTIALIASING;Object before=UIManager.get(key);
            try{
                SwingTextRendering.install(UIManager.getDefaults(),Map.of());
                for(String text:new String[]{"shed", "Open Diff", "Language Services"}){
                    var button=new JButton(text);button.setUI(new WorkbenchStyle.ButtonUI());
                    button.setFont(new Font(Font.DIALOG,Font.PLAIN,20));button.setForeground(Color.BLACK);
                    button.setBorderPainted(false);button.setContentAreaFilled(false);button.setFocusPainted(false);button.setSize(280,40);
                    var image=new BufferedImage(280,40,BufferedImage.TYPE_INT_RGB);var g=image.createGraphics();
                    try{g.setColor(Color.WHITE);g.fillRect(0,0,280,40);button.paint(g);}finally{g.dispose();}
                    int softened=0;for(int y=0;y<40;y++)for(int x=0;x<280;x++){
                        int rgb=image.getRGB(x,y)&0xffffff;if(rgb!=0&&rgb!=0xffffff)softened++;
                    }
                    assertTrue(softened>20,"Button text bypassed Swing's smoothing: "+text);
                }
            }finally{UIManager.put(key,before);}
        });
    }
    @Test void treeSelectionSpansTheRowAndFileIconsDoNotDependOnNerdFonts()throws Exception{
        SwingUtilities.invokeAndWait(()->{
            Object before=UIManager.get("Tree.selectionBackground");Color selection=new Color(0xDDE7FF);UIManager.put("Tree.selectionBackground",selection);
            try{
                var tree=new JTree(new String[]{"Demo.java","README.md"});tree.setUI(new WorkbenchStyle.ExplorerTreeUI());tree.setSize(300,100);tree.setRowHeight(24);tree.setSelectionRow(1);
                var image=new BufferedImage(300,100,BufferedImage.TYPE_INT_RGB);var g=image.createGraphics();try{tree.paint(g);}finally{g.dispose();}
                assertEquals(selection.getRGB(),image.getRGB(290,36));
                assertSame(WorkbenchIcons.CODE,WorkbenchIcons.file("Demo.java"));assertSame(WorkbenchIcons.MARKDOWN,WorkbenchIcons.file("README.md"));
                assertSame(WorkbenchIcons.FILE,WorkbenchIcons.file("unknown"));assertSame(WorkbenchIcons.IMAGE,WorkbenchIcons.file("image.PNG"));
            }finally{UIManager.put("Tree.selectionBackground",before);}
        });
    }
}
