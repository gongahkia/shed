package shed;

import java.awt.*;
import java.awt.image.BufferedImage;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import javax.imageio.ImageIO;
import javax.swing.*;

/** Bundled PNG brand marks; never fetches an icon at runtime. Unknown models keep their text label. */
final class ProviderIcons {
    private static final Map<String,Icon> CACHE=new ConcurrentHashMap<>();
    static String brand(String id){
        String value=Objects.toString(id,"").toLowerCase(Locale.ROOT);
        if(value.contains("ollama"))return "ollama";
        if(value.contains("claude")||value.contains("anthropic"))return "claude";
        if(value.contains("gemini")||value.startsWith("google/"))return "gemini";
        if(value.contains("deepseek"))return "deepseek";if(value.contains("qwen"))return "qwen";
        if(value.contains("llama")||value.equals("meta"))return "meta";if(value.contains("mistral")||value.contains("codestral"))return "mistral";
        if(value.contains("gpt")||value.contains("codex")||value.equals("openai")||value.startsWith("openai/"))return "openai";
        if(value.equals("opencode"))return "opencode";if(value.equals("openrouter"))return "openrouter";
        if(value.contains("huggingface"))return "huggingface";return null;
    }
    static Icon forValue(Object value,Color background){
        String name=value instanceof AgentModelOptions.Choice choice?choice.value()+" "+choice.name():value instanceof String text?text:"";
        String brand=brand(name);if(brand==null)return null;
        boolean dark=background!=null&&(background.getRed()+background.getGreen()+background.getBlue())<384;
        String key=(dark?"dark/":"light/")+brand;
        return CACHE.computeIfAbsent(key,ProviderIcons::load);
    }
    private static Icon load(String key){
        try(var stream=ProviderIcons.class.getResourceAsStream("/assets/providers/"+key+".png")){
            if(stream==null)return WorkbenchIcons.COMMANDS;BufferedImage decoded=ImageIO.read(stream);BufferedImage image;
            if(key.endsWith("/claude")){
                image=new BufferedImage(decoded.getWidth(),decoded.getHeight(),BufferedImage.TYPE_INT_ARGB);
                var tint=image.createGraphics();try{tint.drawImage(decoded,0,0,null);tint.setComposite(AlphaComposite.SrcIn);tint.setColor(new Color(0xD97757));tint.fillRect(0,0,image.getWidth(),image.getHeight());}finally{tint.dispose();decoded.flush();}
            }else image=decoded;
            return new ProviderIcon(image);
        }catch(java.io.IOException failure){return WorkbenchIcons.COMMANDS;}
    }
    static final class ProviderIcon implements Icon {
        private final BufferedImage source;
        private BufferedImage scaled;
        ProviderIcon(BufferedImage source){this.source=source;}
        public int getIconWidth(){return WorkbenchStyle.px(18);}
        public int getIconHeight(){return WorkbenchStyle.px(18);}
        synchronized BufferedImage raster(int width,int height){
            width=Math.clamp(width,1,256);height=Math.clamp(height,1,256);
            if(scaled==null||scaled.getWidth()!=width||scaled.getHeight()!=height){
                var next=new BufferedImage(width,height,BufferedImage.TYPE_INT_ARGB);var g=next.createGraphics();
                try{g.setRenderingHint(RenderingHints.KEY_INTERPOLATION,RenderingHints.VALUE_INTERPOLATION_BICUBIC);g.drawImage(source,0,0,width,height,null);}finally{g.dispose();}
                // retain one device-sized raster per brand/theme, including after zoom or monitor changes
                scaled=next;
            }
            return scaled;
        }
        public void paintIcon(Component c,Graphics graphics,int x,int y){
            var g=(Graphics2D)graphics.create();
            try{
                var transform=g.getTransform();
                int width=(int)Math.ceil(getIconWidth()*Math.hypot(transform.getScaleX(),transform.getShearY()));
                int height=(int)Math.ceil(getIconHeight()*Math.hypot(transform.getShearX(),transform.getScaleY()));
                g.setRenderingHint(RenderingHints.KEY_INTERPOLATION,RenderingHints.VALUE_INTERPOLATION_BICUBIC);
                g.drawImage(raster(width,height),x,y,getIconWidth(),getIconHeight(),null);
            }finally{g.dispose();}
        }
    }
}
