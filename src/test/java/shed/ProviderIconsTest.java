package shed;
import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;
class ProviderIconsTest {
    @Test void bundledPngsExistForLightAndDarkWithoutRuntimeNetwork() throws Exception {
        for(String theme:new String[]{"light","dark"})for(String brand:new String[]{"openai","claude","gemini","ollama","deepseek","qwen","meta","mistral","openrouter","opencode","huggingface"}){
            try(var stream=ProviderIcons.class.getResourceAsStream("/assets/providers/"+theme+"/"+brand+".png")){assertNotNull(stream);assertNotNull(javax.imageio.ImageIO.read(stream));}
        }
        assertEquals("openai",ProviderIcons.brand("openai/gpt-5"));assertEquals("claude",ProviderIcons.brand("anthropic/claude"));assertEquals("ollama",ProviderIcons.brand("ollama/qwen"));
        assertNull(ProviderIcons.forValue("unknown-private-model",java.awt.Color.WHITE));assertNotNull(ProviderIcons.forValue("Codex",java.awt.Color.BLACK));
    }
}
