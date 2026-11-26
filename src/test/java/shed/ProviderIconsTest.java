package shed;
import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;
class ProviderIconsTest {
    @Test void claudeMarksAreOrangeAcrossThemesAndAgentAliases(){
        for(var background:new java.awt.Color[]{java.awt.Color.WHITE,java.awt.Color.BLACK})for(String name:new String[]{"Claude Code","anthropic/claude","Claude"}){
            var icon=ProviderIcons.forValue(name,background);var image=new java.awt.image.BufferedImage(icon.getIconWidth(),icon.getIconHeight(),java.awt.image.BufferedImage.TYPE_INT_ARGB);var g=image.createGraphics();try{icon.paintIcon(new javax.swing.JLabel(),g,0,0);}finally{g.dispose();}
            int visible=0;for(int y=0;y<image.getHeight();y++)for(int x=0;x<image.getWidth();x++){var color=new java.awt.Color(image.getRGB(x,y),true);if(color.getAlpha()>128){visible++;assertTrue(color.getRed()>color.getGreen()+40&&color.getGreen()>color.getBlue()+15,"Expected orange Claude mark");}}
            assertTrue(visible>10);image.flush();
        }
    }
    @Test void bundledPngsExistForLightAndDarkWithoutRuntimeNetwork() throws Exception {
        for(String theme:new String[]{"light","dark"})for(String brand:new String[]{"openai","claude","gemini","ollama","deepseek","qwen","meta","mistral","openrouter","opencode","huggingface"}){
            try(var stream=ProviderIcons.class.getResourceAsStream("/assets/providers/"+theme+"/"+brand+".png")){assertNotNull(stream);assertNotNull(javax.imageio.ImageIO.read(stream));}
        }
        assertEquals("openai",ProviderIcons.brand("openai/gpt-5"));assertEquals("claude",ProviderIcons.brand("anthropic/claude"));assertEquals("ollama",ProviderIcons.brand("ollama/qwen"));
        assertNull(ProviderIcons.forValue("unknown-private-model",java.awt.Color.WHITE));assertNotNull(ProviderIcons.forValue("Codex",java.awt.Color.BLACK));
    }
}
