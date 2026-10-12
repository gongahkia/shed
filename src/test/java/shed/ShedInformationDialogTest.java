package shed;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

final class ShedInformationDialogTest {
    @Test
    void formatsSectionsAndEscapesExternalReportContent() {
        String html = ShedInformationDialog.formatReport("Status:\nPath: /tmp/<unsafe>\n\n  <script>alert(1)</script>\n");

        assertTrue(html.contains("<h2>Status</h2>"));
        assertTrue(html.contains("<b>Path</b>"));
        assertTrue(html.contains("&lt;unsafe&gt;"));
        assertTrue(html.contains("&lt;script&gt;alert(1)&lt;/script&gt;"));
        assertFalse(html.contains("<script>"));
    }
}
