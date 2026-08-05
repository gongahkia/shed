package shed;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Color;
import java.awt.Font;
import org.junit.jupiter.api.Test;

public class MarkdownPreviewRendererTest {
    @Test
    void rendersCommonMarkdownWithoutAllowingRawHtmlOrUnsafeLinks() {
        String markdown = """
            # Hello *Shed*

            - [x] Done
            - [ ] Pending

            | Name | Value |
            | :--- | ---: |
            | one | two |

            [safe](https://example.com) [unsafe](javascript:alert(1))
            ![diagram](https://example.com/diagram.png)

            ```java
            <script>alert(1)</script>
            ```
            """;

        String html = MarkdownPreviewRenderer.render(markdown, "Preview", new Font(Font.DIALOG, Font.PLAIN, 13), Color.WHITE, Color.BLACK);

        assertTrue(html.contains("<h1 id=\"hello-shed\">Hello <em>Shed</em></h1>"));
        assertTrue(html.contains("<span class=\"task\">☑</span> Done"));
        assertTrue(html.contains("<table>"));
        assertTrue(html.contains("<a href=\"https://example.com\">safe</a>"));
        assertTrue(html.contains("[image: diagram]</a>"));
        assertTrue(html.contains("&lt;script&gt;alert(1)&lt;/script&gt;"));
        assertFalse(html.contains("href=\"javascript:"));
        assertFalse(html.contains("<script>alert(1)</script>"));
    }
}
