package shed;

import com.aresstack.Mermaid;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import org.apache.batik.transcoder.SVGAbstractTranscoder;
import org.apache.batik.transcoder.TranscoderInput;
import org.apache.batik.transcoder.TranscoderOutput;
import org.apache.batik.transcoder.XMLAbstractTranscoder;
import org.apache.batik.transcoder.image.PNGTranscoder;
import org.scilab.forge.jlatexmath.TeXConstants;
import org.scilab.forge.jlatexmath.TeXFormula;
import org.scilab.forge.jlatexmath.TeXIcon;

/** Session-scoped local assets for Markdown math, Mermaid, and explicit-preview remote images. */
final class MarkdownPreviewAssets implements AutoCloseable {
    private static final int MAX_MERMAID_SOURCE_CHARS = 65_536;
    private static final int MAX_IMAGE_DIMENSION = 4_096;
    private static final long MAX_IMAGE_PIXELS = 16L * 1024L * 1024L;
    private static final int MAX_REMOTE_IMAGE_BYTES = 12 * 1024 * 1024;
    private static final int MAX_REMOTE_IMAGE_REQUESTS = 32;
    private static final Duration REMOTE_IMAGE_TIMEOUT = Duration.ofSeconds(12);
    private static final Object MERMAID_LOCK = new Object();
    private final Path directory;
    private final HttpClient remoteHttp;
    private final Map<String, String> renderedAssets = new HashMap<>();
    private final BooleanSupplier remoteImagesEnabled;
    private final Runnable remoteImageReady;
    private final ExecutorService remoteImageExecutor;
    private final Map<String, String> remoteImages = new ConcurrentHashMap<>();
    private final Set<String> pendingRemoteImages = ConcurrentHashMap.newKeySet();
    private final Set<String> failedRemoteImages = ConcurrentHashMap.newKeySet();
    private final AtomicInteger remoteImageRequests = new AtomicInteger();

    MarkdownPreviewAssets() {
        this(() -> false, () -> { });
    }

    MarkdownPreviewAssets(BooleanSupplier remoteImagesEnabled, Runnable remoteImageReady) {
        try {
            directory = Files.createTempDirectory("shed-markdown-preview-");
        } catch (IOException error) {
            throw new IllegalStateException("cannot create Markdown preview cache", error);
        }
        remoteHttp = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5))
            .followRedirects(HttpClient.Redirect.NORMAL).build();
        this.remoteImagesEnabled = remoteImagesEnabled == null ? () -> false : remoteImagesEnabled;
        this.remoteImageReady = remoteImageReady == null ? () -> { } : remoteImageReady;
        remoteImageExecutor = Executors.newFixedThreadPool(2, daemonFactory());
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

    /** Resolves an approved HTTPS image from the session cache and starts a bounded background fetch if needed. */
    RemoteImageResolution resolveRemoteImage(String source) {
        if (!remoteImagesEnabled.getAsBoolean()) return RemoteImageResolution.unavailable();
        URI uri = remoteImageUri(source);
        if (uri == null) return RemoteImageResolution.unavailable();
        String key = uri.toASCIIString();
        String cached = remoteImages.get(key);
        if (cached != null) return RemoteImageResolution.available(cached);
        if (failedRemoteImages.contains(key)) return RemoteImageResolution.unavailable();
        if (pendingRemoteImages.add(key)) {
            if (remoteImageRequests.incrementAndGet() > MAX_REMOTE_IMAGE_REQUESTS) {
                pendingRemoteImages.remove(key);
                failedRemoteImages.add(key);
                return RemoteImageResolution.unavailable();
            }
            remoteImageExecutor.submit(() -> fetchRemoteImage(uri, key));
        }
        return RemoteImageResolution.pending();
    }

    @Override
    public void close() {
        remoteImageExecutor.shutdownNow();
        remoteHttp.shutdownNow();
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
        remoteImages.clear();
        pendingRemoteImages.clear();
        failedRemoteImages.clear();
    }

    static boolean isAllowedRemoteImageUri(String source) {
        return remoteImageUri(source) != null;
    }

    static BufferedImage decodeRemoteImageBytes(byte[] content) throws IOException {
        if (content == null || content.length == 0 || content.length > MAX_REMOTE_IMAGE_BYTES) {
            throw new IOException("remote image has an invalid size");
        }
        return looksLikeSvg(content) ? rasterizeSafeSvg(content) : decodeRasterImage(content);
    }

    private void fetchRemoteImage(URI uri, String key) {
        try {
            if (!hasOnlyPublicAddresses(uri.getHost())) throw new IOException("remote image host is not public");
            HttpRequest request = HttpRequest.newBuilder(uri)
                .timeout(REMOTE_IMAGE_TIMEOUT)
                .header("Accept", "image/png,image/jpeg,image/gif,image/bmp,image/svg+xml;q=0.9,*/*;q=0.1")
                .GET()
                .build();
            HttpResponse<InputStream> response = remoteHttp.send(request, HttpResponse.BodyHandlers.ofInputStream());
            try (InputStream body = response.body()) {
                if (response.statusCode() < 200 || response.statusCode() >= 300) {
                    throw new IOException("remote image returned HTTP " + response.statusCode());
                }
                URI effective = remoteImageUri(response.uri() == null ? "" : response.uri().toASCIIString());
                if (effective == null || !hasOnlyPublicAddresses(effective.getHost())) {
                    throw new IOException("remote image redirect is not allowed");
                }
                String contentType = response.headers().firstValue("Content-Type").orElse("").toLowerCase(java.util.Locale.ROOT);
                if (!contentType.isBlank() && !contentType.startsWith("image/") && !contentType.startsWith("application/octet-stream")) {
                    throw new IOException("remote response is not an image");
                }
                String length = response.headers().firstValue("Content-Length").orElse("");
                if (!length.isBlank() && Long.parseLong(length) > MAX_REMOTE_IMAGE_BYTES) {
                    throw new IOException("remote image exceeds 12 MiB");
                }
                BufferedImage image = decodeRemoteImageBytes(readBounded(body));
                remoteImages.put(key, writeRemotePng(key, image));
            }
        } catch (Exception ignored) {
            failedRemoteImages.add(key);
        } finally {
            pendingRemoteImages.remove(key);
            remoteImageReady.run();
        }
    }

    private String writePng(String key, BufferedImage image) throws IOException {
        Path output = directory.resolve(sha256(key) + ".png");
        ImageIO.write(image, "png", output.toFile());
        String uri = output.toUri().toASCIIString();
        renderedAssets.put(key, uri);
        return uri;
    }

    private String writeRemotePng(String key, BufferedImage image) throws IOException {
        Path output = directory.resolve("remote-" + sha256(key) + ".png");
        ImageIO.write(scaleWithinBounds(image), "png", output.toFile());
        return output.toUri().toASCIIString();
    }

    private static URI remoteImageUri(String source) {
        if (source == null || source.isBlank()) return null;
        try {
            URI uri = new URI(source.replace("&amp;", "&")).normalize();
            String scheme = uri.getScheme();
            String host = uri.getHost();
            if (!"https".equalsIgnoreCase(scheme) || host == null || host.isBlank() || uri.getRawUserInfo() != null
                || (uri.getPort() != -1 && uri.getPort() != 443) || localOrLiteralHost(host)) {
                return null;
            }
            return new URI("https", null, host, uri.getPort(), uri.getRawPath(), uri.getRawQuery(), null).normalize();
        } catch (URISyntaxException error) {
            return null;
        }
    }

    private static boolean localOrLiteralHost(String host) {
        String normalized = host.toLowerCase(java.util.Locale.ROOT);
        if (normalized.equals("localhost") || normalized.endsWith(".localhost") || normalized.indexOf(':') >= 0) return true;
        return normalized.matches("[0-9.]+") || normalized.matches("[0-9a-f.:-]+") && normalized.indexOf(':') >= 0;
    }

    private static boolean hasOnlyPublicAddresses(String host) throws IOException {
        for (InetAddress address : InetAddress.getAllByName(host)) {
            if (address.isAnyLocalAddress() || address.isLoopbackAddress() || address.isLinkLocalAddress()
                || address.isSiteLocalAddress() || address.isMulticastAddress()) {
                return false;
            }
        }
        return true;
    }

    private static byte[] readBounded(InputStream source) throws IOException {
        try (ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int total = 0;
            int read;
            while ((read = source.read(buffer)) >= 0) {
                total += read;
                if (total > MAX_REMOTE_IMAGE_BYTES) throw new IOException("remote image exceeds 12 MiB");
                output.write(buffer, 0, read);
            }
            return output.toByteArray();
        }
    }

    private static boolean looksLikeSvg(byte[] content) {
        int length = Math.min(content.length, 4096);
        String prefix = new String(content, 0, length, StandardCharsets.UTF_8).replaceFirst("^\\uFEFF?\\s*", "").toLowerCase(java.util.Locale.ROOT);
        return prefix.startsWith("<svg") || prefix.startsWith("<?xml") && prefix.contains("<svg");
    }

    private static BufferedImage rasterizeSafeSvg(byte[] content) throws IOException {
        String source = new String(content, StandardCharsets.UTF_8);
        String normalized = source.toLowerCase(java.util.Locale.ROOT);
        if (normalized.contains("<!doctype") || normalized.contains("<!entity") || normalized.contains("<script")
            || normalized.contains("<foreignobject") || normalized.contains("javascript:") || normalized.matches("(?s).*\\son[a-z]+\\s*=.*")) {
            throw new IOException("remote SVG contains unsupported active content");
        }
        PNGTranscoder transcoder = new PNGTranscoder();
        transcoder.addTranscodingHint(XMLAbstractTranscoder.KEY_XML_PARSER_VALIDATING, Boolean.FALSE);
        transcoder.addTranscodingHint(SVGAbstractTranscoder.KEY_EXECUTE_ONLOAD, Boolean.FALSE);
        transcoder.addTranscodingHint(SVGAbstractTranscoder.KEY_ALLOWED_SCRIPT_TYPES, "");
        transcoder.addTranscodingHint(SVGAbstractTranscoder.KEY_CONSTRAIN_SCRIPT_ORIGIN, Boolean.TRUE);
        transcoder.addTranscodingHint(SVGAbstractTranscoder.KEY_ALLOW_EXTERNAL_RESOURCES, Boolean.FALSE);
        transcoder.addTranscodingHint(SVGAbstractTranscoder.KEY_MAX_WIDTH, Float.valueOf(MAX_IMAGE_DIMENSION));
        transcoder.addTranscodingHint(SVGAbstractTranscoder.KEY_MAX_HEIGHT, Float.valueOf(MAX_IMAGE_DIMENSION));
        try (ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            transcoder.transcode(new TranscoderInput(new ByteArrayInputStream(content)), new TranscoderOutput(output));
            return decodeRasterImage(output.toByteArray());
        } catch (Exception error) {
            throw new IOException("remote SVG could not be rasterized", error);
        }
    }

    private static BufferedImage decodeRasterImage(byte[] content) throws IOException {
        try (ImageInputStream input = ImageIO.createImageInputStream(new ByteArrayInputStream(content))) {
            if (input == null) throw new IOException("remote image format is unsupported");
            Iterator<ImageReader> readers = ImageIO.getImageReaders(input);
            if (!readers.hasNext()) throw new IOException("remote image format is unsupported");
            ImageReader reader = readers.next();
            try {
                reader.setInput(input, true, true);
                int width = reader.getWidth(0);
                int height = reader.getHeight(0);
                if (width <= 0 || height <= 0 || width > MAX_IMAGE_DIMENSION || height > MAX_IMAGE_DIMENSION
                    || (long) width * height > MAX_IMAGE_PIXELS) {
                    throw new IOException("remote image dimensions exceed 4096 px or 16 megapixels");
                }
                BufferedImage image = reader.read(0);
                if (image == null) throw new IOException("remote image is unreadable");
                return image;
            } finally {
                reader.dispose();
            }
        }
    }

    private static BufferedImage scaleWithinBounds(BufferedImage source) throws IOException {
        if (source == null || source.getWidth() <= 0 || source.getHeight() <= 0) throw new IOException("image is invalid");
        double scale = Math.min(1.0, Math.min((double) MAX_IMAGE_DIMENSION / source.getWidth(), (double) MAX_IMAGE_DIMENSION / source.getHeight()));
        long pixels = (long) source.getWidth() * source.getHeight();
        if (pixels > MAX_IMAGE_PIXELS) scale = Math.min(scale, Math.sqrt((double) MAX_IMAGE_PIXELS / pixels));
        if (scale >= 1.0) return source;
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

    private static ThreadFactory daemonFactory() {
        AtomicInteger count = new AtomicInteger();
        return runnable -> {
            Thread thread = new Thread(runnable, "shed-markdown-image-" + count.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        };
    }

    private static String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
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

    record RemoteImageResolution(String uri, State state) {
        enum State { AVAILABLE, PENDING, UNAVAILABLE }
        static RemoteImageResolution available(String uri) { return new RemoteImageResolution(uri, State.AVAILABLE); }
        static RemoteImageResolution pending() { return new RemoteImageResolution("", State.PENDING); }
        static RemoteImageResolution unavailable() { return new RemoteImageResolution("", State.UNAVAILABLE); }
        boolean isAvailable() { return state == State.AVAILABLE; }
        boolean isPending() { return state == State.PENDING; }
    }
}
