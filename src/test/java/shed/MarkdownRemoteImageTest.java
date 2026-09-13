package shed;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.lang.reflect.Field;
import java.util.Map;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;

class MarkdownRemoteImageTest {
    @Test
    void acceptsPublicHttpsAttachmentAndBadgeUrlsButRejectsLocalOrPlainHttpUrls() {
        assertTrue(MarkdownPreviewAssets.isAllowedRemoteImageUri("https://github.com/user-attachments/assets/abc/image.png"));
        assertTrue(MarkdownPreviewAssets.isAllowedRemoteImageUri("https://img.shields.io/badge/shed-2.0-blue.svg"));
        assertFalse(MarkdownPreviewAssets.isAllowedRemoteImageUri("http://img.shields.io/badge/shed-2.0-blue.svg"));
        assertFalse(MarkdownPreviewAssets.isAllowedRemoteImageUri("https://localhost/image.png"));
        assertFalse(MarkdownPreviewAssets.isAllowedRemoteImageUri("https://127.0.0.1/image.png"));
        assertFalse(MarkdownPreviewAssets.isAllowedRemoteImageUri("file:///tmp/image.png"));
    }

    @Test
    void normalizesRasterAttachmentsAndSvgBadgesToBoundedImages() throws Exception {
        BufferedImage source = new BufferedImage(7, 5, BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = source.createGraphics();
        try {
            graphics.setColor(Color.RED);
            graphics.fillRect(0, 0, 7, 5);
        } finally {
            graphics.dispose();
        }
        ByteArrayOutputStream png = new ByteArrayOutputStream();
        ImageIO.write(source, "png", png);
        BufferedImage decodedPng = MarkdownPreviewAssets.decodeRemoteImageBytes(png.toByteArray());
        assertEquals(7, decodedPng.getWidth());
        assertEquals(5, decodedPng.getHeight());

        String badge = "<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"72\" height=\"20\"><rect width=\"72\" height=\"20\" fill=\"#4285f4\"/></svg>";
        BufferedImage decodedSvg = MarkdownPreviewAssets.decodeRemoteImageBytes(badge.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        assertEquals(72, decodedSvg.getWidth());
        assertEquals(20, decodedSvg.getHeight());
    }

    @Test
    void rejectsActiveSvgContentBeforeRasterization() {
        String active = "<svg xmlns=\"http://www.w3.org/2000/svg\"><script>alert(1)</script></svg>";
        assertThrows(java.io.IOException.class,
            () -> MarkdownPreviewAssets.decodeRemoteImageBytes(active.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    }

    @Test
    @SuppressWarnings("unchecked")
    void rendersAResolvedRemoteImageFromThePreviewSessionCache() throws Exception {
        String source = "https://img.shields.io/badge/shed-2.0-blue.svg";
        String cached = "file:///tmp/shed-preview-badge.png";
        try (MarkdownPreviewAssets assets = new MarkdownPreviewAssets(() -> true, () -> { })) {
            Field field = MarkdownPreviewAssets.class.getDeclaredField("remoteImages");
            field.setAccessible(true);
            ((Map<String, String>) field.get(assets)).put(source, cached);

            String html = MarkdownPreviewRenderer.render("![Shed badge](" + source + ")", "preview",
                new Font(Font.MONOSPACED, Font.PLAIN, 13), Color.WHITE, Color.BLACK, assets, new File("README.md"));

            assertTrue(html.contains("<img src=\"" + cached + "\" alt=\"Shed badge\""));
        }
    }
}
