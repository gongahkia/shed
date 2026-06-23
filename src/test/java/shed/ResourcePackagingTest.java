package shed;

import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.io.InputStream;
import org.junit.jupiter.api.Test;

public class ResourcePackagingTest {
    @Test
    void bundledHackFontIsOnClasspath() throws Exception {
        try (InputStream stream = Texteditor.class.getClassLoader().getResourceAsStream("assets/hackregfont.ttf")) {
            assertNotNull(stream);
        }
    }
}
