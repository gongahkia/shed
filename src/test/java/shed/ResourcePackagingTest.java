package shed;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.awt.image.BufferedImage;
import java.io.InputStream;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;

public class ResourcePackagingTest {
    @Test
    void bundledLogoIsOnClasspath() throws Exception {
        try (InputStream stream = Texteditor.class.getClassLoader().getResourceAsStream("assets/logo/shed.png")) {
            assertNotNull(stream);
            BufferedImage image = ImageIO.read(stream);
            assertNotNull(image);
            assertEquals(264, image.getWidth());
            assertEquals(297, image.getHeight());
        }
    }
}
