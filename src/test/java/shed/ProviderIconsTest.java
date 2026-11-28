package shed;
import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;
class ProviderIconsTest {
    @Test void rasterIsReusedAndTracksDeviceScaleWithBoundedRetention(){
        var source=new java.awt.image.BufferedImage(640,640,java.awt.image.BufferedImage.TYPE_INT_ARGB);
        var g=source.createGraphics();try{g.setColor(java.awt.Color.ORANGE);g.fillOval(32,32,576,576);}finally{g.dispose();}
        var icon=new ProviderIcons.ProviderIcon(source);var small=icon.raster(18,18);
        assertSame(small,icon.raster(18,18));var retina=icon.raster(36,36);assertNotSame(small,retina);assertSame(retina,icon.raster(36,36));
        assertEquals(256,icon.raster(10000,10000).getWidth());assertEquals(1,icon.raster(0,0).getHeight());
        for(int scale:new int[]{1,2}){
            int size=icon.getIconWidth()*scale;
            var actual=new java.awt.image.BufferedImage(size,size,java.awt.image.BufferedImage.TYPE_INT_ARGB);
            var expected=new java.awt.image.BufferedImage(size,size,java.awt.image.BufferedImage.TYPE_INT_ARGB);
            g=actual.createGraphics();try{g.scale(scale,scale);icon.paintIcon(null,g,0,0);}finally{g.dispose();}
            g=expected.createGraphics();try{g.setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION,java.awt.RenderingHints.VALUE_INTERPOLATION_BICUBIC);g.drawImage(source,0,0,size,size,null);}finally{g.dispose();}
            assertArrayEquals(expected.getRGB(0,0,size,size,null,0,size),actual.getRGB(0,0,size,size,null,0,size),"Cached painting must retain the original raster at each device scale");
        }
    }
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
