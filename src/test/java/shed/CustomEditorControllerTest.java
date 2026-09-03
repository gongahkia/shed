package shed;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class CustomEditorControllerTest {
    @Test
    void identifiesBinaryControlDataWithoutMisclassifyingNormalWhitespace() {
        assertTrue(CustomEditorController.isBinary(new byte[] {0x00, 0x01}));
        assertTrue(CustomEditorController.isBinary(new byte[] {'a', 0x1f, 'b'}));
        assertFalse(CustomEditorController.isBinary("line one\nline two\t".getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    }
}
