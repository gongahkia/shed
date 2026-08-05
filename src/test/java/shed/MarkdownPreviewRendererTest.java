package shed;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Color;
import java.awt.Font;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

public class MarkdownPreviewRendererTest {
    @TempDir
    Path tempDir;

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

        String html;
        try (MarkdownPreviewAssets assets = new MarkdownPreviewAssets()) {
            html = MarkdownPreviewRenderer.render(markdown, "Preview", new Font(Font.DIALOG, Font.PLAIN, 13), Color.WHITE, Color.BLACK, assets, null);
        }

        assertTrue(html.contains("<h1 id=\"hello-shed\">Hello <em>Shed</em></h1>"));
        assertTrue(html.contains("type=\"checkbox\""));
        assertTrue(html.contains("checked=\"\""));
        assertTrue(html.contains("<table>"));
        assertTrue(html.contains("href=\"https://example.com\">safe</a>"));
        assertTrue(html.contains("[image unavailable: diagram]"));
        assertTrue(html.contains("&lt;script&gt;alert(1)&lt;/script&gt;"));
        assertFalse(html.contains("href=\"javascript:"));
        assertFalse(html.contains("<script>alert(1)</script>"));
    }

    @Test
    void rendersLocalImagesMathMermaidAndGfmExtensionsOffline() throws Exception {
        Path image = tempDir.resolve("diagram.png");
        ImageIO.write(new BufferedImage(4, 4, BufferedImage.TYPE_INT_ARGB), "png", image.toFile());
        Path markdown = tempDir.resolve("preview.md");
        Files.writeString(markdown, "preview");
        String source = """
            ![local](diagram.png)

            inline $x^2$ and display:
            $$
            \\frac{1}{2}
            $$

            ```mermaid
            flowchart LR
              A --> B
            ```

            https://example.com[^note]

            [^note]: note text

            > [!NOTE]
            > alert text
            """;

        String html;
        try (MarkdownPreviewAssets assets = new MarkdownPreviewAssets()) {
            html = MarkdownPreviewRenderer.render(source, "Preview", new Font(Font.DIALOG, Font.PLAIN, 13), Color.WHITE, Color.BLACK, assets, markdown.toFile());
        }

        assertTrue(html.contains(image.toUri().toASCIIString()));
        assertTrue(html.contains("alt=\"math\""));
        assertTrue(html.contains("alt=\"Mermaid diagram\""));
        assertTrue(html.contains("https://example.com"));
        assertTrue(html.contains("footnotes"));
        assertTrue(html.contains("markdown-alert"));
    }

    @Test
    void rendersTexDelimitersAndLeavesCodeFencesUntouched() {
        String markdown = """
            ~~removed~~ and `$literal$`

            \\(\\sqrt{2}\\)

            \\[
            a + b
            \\]

            ```text
            $not math$
            ```
            """;

        String html;
        try (MarkdownPreviewAssets assets = new MarkdownPreviewAssets()) {
            html = MarkdownPreviewRenderer.render(markdown, "Preview", new Font(Font.DIALOG, Font.PLAIN, 13), Color.WHITE, Color.BLACK, assets, null);
        }

        assertTrue(html.contains("<del>removed</del>"));
        assertTrue(html.contains("<code>$literal$</code>"));
        assertTrue(html.contains("<pre><code class=\"language-text\">$not math$"));
        assertTrue(html.indexOf("alt=\"math\"") != html.lastIndexOf("alt=\"math\""));
    }
}
