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
    void rendersCommonMarkdownAndSanitizesUnsafeHtmlOrLinks() {
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
    void basicRendererAvoidsGeneratedAssetsAndKeepsImagePolicy() {
        String html = MarkdownPreviewRenderer.renderBasic("# Cell\n\n![remote](https://example.com/image.png)", "Cell",
            new Font(Font.DIALOG, Font.PLAIN, 13), Color.WHITE, Color.BLACK, null);

        assertTrue(html.contains("<h1 id=\"cell\">Cell</h1>"));
        assertTrue(html.contains("[image unavailable: remote]"));
        assertFalse(html.contains("file:"));
    }

    @Test
    void rendersSupportedRawHtmlWithLocalImagesAndRemovesUnsafeMarkup() throws Exception {
        Path image = tempDir.resolve("logo.png");
        ImageIO.write(new BufferedImage(4, 4, BufferedImage.TYPE_INT_ARGB), "png", image.toFile());
        Path markdown = tempDir.resolve("preview.md");
        Files.writeString(markdown, "preview");
        String source = """
            <div align="center" onclick="alert(1)">
              <h2>Logo</h2>
              <a href="https://example.com"><img src="logo.png" alt="logo" width="20%"></a>
            </div>

            <ul><li>one</li><li>two</li></ul>
            <table><thead><tr><th>name</th></tr></thead><tbody><tr><td>value</td></tr></tbody></table>
            <a href="javascript:alert(1)">unsafe</a><img src="https://example.com/remote.png" alt="remote">
            <script>alert(1)</script><style>body { color: red; }</style><video src="movie.mp4"></video>
            """;

        String html;
        try (MarkdownPreviewAssets assets = new MarkdownPreviewAssets()) {
            html = MarkdownPreviewRenderer.render(source, "Preview", new Font(Font.DIALOG, Font.PLAIN, 13), Color.WHITE, Color.BLACK, assets, markdown.toFile());
        }

        assertTrue(html.contains("<div align=\"center\">"));
        assertTrue(html.contains("<h2>Logo</h2>"));
        assertTrue(html.contains("href=\"https://example.com\""));
        assertTrue(html.contains("src=\"" + image.toUri().toASCIIString() + "\" alt=\"logo\" width=\"20%\""));
        assertTrue(html.contains("<ul><li>one</li><li>two</li></ul>"));
        assertTrue(html.contains("<table>"));
        assertTrue(html.contains("[image unavailable: remote]"));
        assertFalse(html.contains("onclick="));
        assertFalse(html.contains("href=\"javascript:"));
        assertFalse(html.contains("<script"));
        assertFalse(html.contains("color: red;"));
        assertFalse(html.contains("<video"));
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
