package shed;

import com.aresstack.Mermaid;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Map;
import javax.imageio.ImageIO;
import org.scilab.forge.jlatexmath.TeXConstants;
import org.scilab.forge.jlatexmath.TeXFormula;
import org.scilab.forge.jlatexmath.TeXIcon;

final class MarkdownPreviewAssets implements AutoCloseable {
    private static final int MAX_MERMAID_SOURCE_CHARS = 65_536;
    private static final int MAX_IMAGE_DIMENSION = 4_096;
    private static final Object MERMAID_LOCK = new Object();
    private final Path directory;
    private final Map<String, String> renderedAssets = new HashMap<>();

    MarkdownPreviewAssets() {
        try {
            directory = Files.createTempDirectory("shed-markdown-preview-");
        } catch (IOException error) {
            throw new IllegalStateException("cannot create Markdown preview cache", error);
        }
    }

    String renderMath(String formula, boolean display, Color foreground) throws IOException {
        String key = "math|" + display + "|" + foreground.getRGB() + "|" + formula;
        String cached = renderedAssets.get(key);
        if (cached != null) return cached;
        TeXFormula tex = new TeXFormula(formula);
        TeXIcon icon = tex.createTeXIcon(display ? TeXConstants.STYLE_DISPLAY : TeXConstants.STYLE_TEXT, display ? 20f : 16f);
        icon.setForeground(foreground);
        int width = icon.getIconWidth() + 8;
        int height = icon.getIconHeight() + 8;
        if (width <= 0 || height <= 0 || width > MAX_IMAGE_DIMENSION || height > MAX_IMAGE_DIMENSION) {
            throw new IOException("math result is too large");
        }
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = image.createGraphics();
        try {
            graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            graphics.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            icon.paintIcon(null, graphics, 4, 4);
        } finally {
            graphics.dispose();
        }
        return writePng(key, image);
    }

    String renderMermaid(String source) throws IOException {
        if (source.length() > MAX_MERMAID_SOURCE_CHARS) throw new IOException("Mermaid source exceeds 64 KiB");
        String key = "mermaid|" + source;
        String cached = renderedAssets.get(key);
        if (cached != null) return cached;
        BufferedImage image;
        try {
            synchronized (MERMAID_LOCK) {
                image = Mermaid.renderToImage(source);
            }
        } catch (RuntimeException error) {
            throw new IOException("Mermaid render failed: " + safeMessage(error), error);
        }
        if (image == null) throw new IOException("Mermaid renderer produced no image");
        return writePng(key, scaleWithinBounds(image));
    }

    @Override
    public void close() {
        try (var paths = Files.walk(directory)) {
            paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException ignored) {
                }
            });
        } catch (IOException ignored) {
        }
        renderedAssets.clear();
    }

    private String writePng(String key, BufferedImage image) throws IOException {
        Path output = directory.resolve(sha256(key) + ".png");
        ImageIO.write(image, "png", output.toFile());
        String uri = output.toUri().toASCIIString();
        renderedAssets.put(key, uri);
        return uri;
    }

    private static BufferedImage scaleWithinBounds(BufferedImage source) throws IOException {
        if (source.getWidth() <= MAX_IMAGE_DIMENSION && source.getHeight() <= MAX_IMAGE_DIMENSION) return source;
        double scale = Math.min((double) MAX_IMAGE_DIMENSION / source.getWidth(), (double) MAX_IMAGE_DIMENSION / source.getHeight());
        int width = Math.max(1, (int) Math.round(source.getWidth() * scale));
        int height = Math.max(1, (int) Math.round(source.getHeight() * scale));
        BufferedImage scaled = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = scaled.createGraphics();
        try {
            graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
            graphics.drawImage(source, 0, 0, width, height, null);
        } finally {
            graphics.dispose();
        }
        return scaled;
    }

    private static String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(digest.length * 2);
            for (byte current : digest) hex.append(String.format("%02x", current));
            return hex.toString();
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException("SHA-256 unavailable", error);
        }
    }

    private static String safeMessage(RuntimeException error) {
        String message = error.getMessage();
        return message == null || message.isBlank() ? error.getClass().getSimpleName() : message.replace('\n', ' ');
    }
}
