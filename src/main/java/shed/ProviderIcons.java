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
            if(stream==null)return WorkbenchIcons.COMMANDS;BufferedImage image=ImageIO.read(stream);
            return new Icon(){public int getIconWidth(){return WorkbenchStyle.px(18);}public int getIconHeight(){return WorkbenchStyle.px(18);}
                public void paintIcon(Component c,Graphics graphics,int x,int y){var g=(Graphics2D)graphics.create();try{g.setRenderingHint(RenderingHints.KEY_INTERPOLATION,RenderingHints.VALUE_INTERPOLATION_BICUBIC);g.drawImage(image,x,y,getIconWidth(),getIconHeight(),null);}finally{g.dispose();}}};
        }catch(java.io.IOException failure){return WorkbenchIcons.COMMANDS;}
    }
}
