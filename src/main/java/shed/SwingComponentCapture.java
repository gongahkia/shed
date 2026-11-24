package shed;

import java.awt.*;
import java.awt.image.BufferedImage;
import javax.swing.*;

/** EDT-only, bounded capture of a supplied component. Never reads the desktop or other windows. */
final class SwingComponentCapture {
    private SwingComponentCapture() { }
    static BufferedImage capture(JComponent component,int x,int y,int width,int height,int maximumPixels) {
        if(!SwingUtilities.isEventDispatchThread())throw new IllegalStateException("Capture belongs to the EDT");
        if(x<0 || y<0 || width<1 || height<1 || (long)x+width>component.getWidth() || (long)y+height>component.getHeight())
            throw new IllegalArgumentException("Capture outside component bounds");
        var configuration=component.getGraphicsConfiguration();
        var transform=configuration==null?new java.awt.geom.AffineTransform():configuration.getDefaultTransform();
        double sx=transform.getScaleX(),sy=transform.getScaleY();
        if(!Double.isFinite(sx)||!Double.isFinite(sy)||sx<1||sy<1||sx>4||sy>4)throw new IllegalArgumentException("Unsupported display scale");
        int pixelsWide=(int)Math.ceil(width*sx),pixelsHigh=(int)Math.ceil(height*sy);
        if((long)pixelsWide*pixelsHigh>maximumPixels)throw new IllegalArgumentException("Capture exceeds pixel budget");
        var image=new BufferedImage(pixelsWide,pixelsHigh,BufferedImage.TYPE_INT_RGB);
        var graphics=image.createGraphics();
        try {
            graphics.setColor(component.getBackground());graphics.fillRect(0,0,pixelsWide,pixelsHigh);
            // Match the display's font render context; a 1x capture of a 2x component shifts text overlays.
            graphics.scale(sx,sy);graphics.translate(-x,-y);component.paint(graphics);
        } finally { graphics.dispose(); }
        return image;
    }
}
