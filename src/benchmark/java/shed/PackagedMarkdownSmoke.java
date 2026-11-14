package shed;

import java.awt.Color;
import java.awt.Font;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import javax.imageio.ImageIO;
import javax.swing.JEditorPane;
import javax.swing.SwingUtilities;

/** Opt-in distribution regression; compiled independently, never shipped or run by ordinary tests. */
public final class PackagedMarkdownSmoke {
    private static final String SERVICE = "META-INF/services/com.oracle.truffle.api.TruffleLanguage$Provider";
    private static final String SIMPLE = "flowchart LR\n  Source --> Preview";
    private static final String MULTIPLE = "flowchart TD\n  A[Start] --> B{Ready}\n  B --> C[Edit]\n  B --> D[Preview]\n  C --> E[Save]\n  D --> E";
    private static final String INVALID = "this is not a Mermaid diagram";
    private static final Font FONT = new Font(Font.DIALOG, Font.PLAIN, 13);

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private static void event(String operation, Map<String, Object> values) {
        Map<String, Object> row = new LinkedHashMap<>(values);
        row.put("operation", operation);
        System.out.println("SMOKE " + MiniJson.stringify(row));
        System.out.flush();
    }

    private static void memory(String phase) {
        var heap = ManagementFactory.getMemoryMXBean().getHeapMemoryUsage();
        event(phase, Map.of("heap_used_bytes", heap.getUsed(), "heap_committed_bytes", heap.getCommitted(),
            "threads", ManagementFactory.getThreadMXBean().getThreadCount()));
    }

    private static String render(String source, MarkdownPreviewAssets assets, Path file) {
        return MarkdownPreviewRenderer.render(source, "Packaged smoke", FONT, Color.WHITE, Color.BLACK, assets, file.toFile());
    }

    private static void image(String uri) throws Exception {
        Path path = Path.of(URI.create(uri));
        require(Files.size(path) > 0, "Empty image: " + uri);
        BufferedImage decoded = ImageIO.read(path.toFile());
        require(decoded != null && decoded.getWidth() > 0 && decoded.getHeight() > 0, "Undecodable image: " + uri);
    }

    private static String diagram(String operation, String source, MarkdownPreviewAssets assets) throws Exception {
        long start = System.nanoTime();
        String uri = assets.renderMermaid(source);
        event(operation, Map.of("render_ms", (System.nanoTime() - start) / 1e6));
        image(uri); // Correctness is outside render timing; empty/null results cannot pass.
        return uri;
    }

    public static void main(String[] args) throws Exception {
        require(args.length == 2, "expected application JAR/classes origin and disposable work directory");
        Path expected = Path.of(args[0]).toRealPath();
        Path actual = Path.of(MarkdownPreviewRenderer.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toRealPath();
        require(expected.equals(actual), "Unexpected application origin: " + actual);
        if (Files.isRegularFile(expected)) {
            Path dependency = Path.of(com.aresstack.Mermaid.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toRealPath();
            require(expected.equals(dependency), "Mermaid fell back to an external dependency: " + dependency);
        }
        Set<String> providers = new LinkedHashSet<>();
        var resources = PackagedMarkdownSmoke.class.getClassLoader().getResources(SERVICE);
        int descriptors = 0;
        while (resources.hasMoreElements()) {
            descriptors++;
            try (var input = resources.nextElement().openStream()) {
                new String(input.readAllBytes(), StandardCharsets.UTF_8).lines()
                    .map(s -> s.split("#", 2)[0].trim()).filter(s -> !s.isEmpty()).forEach(providers::add);
            }
        }
        event("runtime", Map.of("application_origin", actual.toString(), "classpath", System.getProperty("java.class.path"),
            "provider_descriptors", descriptors, "providers", providers.stream().toList()));
        require(providers.contains("com.oracle.truffle.js.lang.JavaScriptLanguageProvider"), "Missing JavaScript provider");
        require(providers.contains("com.oracle.truffle.regex.RegexLanguageProvider"), "Missing regex provider");
        if (Files.isRegularFile(expected)) require(descriptors == 1, "Expected one merged provider descriptor");

        Path directory = Path.of(args[1]);
        Path source = directory.resolve("first.md");
        Files.writeString(source, "# First document\n");
        Path local = directory.resolve("local.png");
        require(ImageIO.write(new BufferedImage(4, 4, BufferedImage.TYPE_INT_ARGB), "png", local.toFile()), "PNG writer missing");
        memory("before_assets");
        try (MarkdownPreviewAssets assets = new MarkdownPreviewAssets()) {
            String plain = render("# Prior document\n\n- [x] task\n\n| a | b |\n| --- | --- |\n| 1 | 2 |\n\n<script>unsafe</script>", assets, source);
            require(plain.contains("Prior document") && plain.contains("<table>") && plain.contains("checkbox"), "Basic Markdown regressed");
            require(!plain.contains("<script>"), "Unsafe HTML survived");
            memory("plain_before_first_mermaid");
            long mathStart = System.nanoTime();
            image(assets.renderMath("x^2 + 1", false, Color.BLACK));
            event("math_first", Map.of("render_ms", (System.nanoTime() - mathStart) / 1e6));

            String first = diagram("mermaid_first_after_plain_and_math", SIMPLE, assets);
            for (int n = 0; n < 3; n++) {
                require(first.equals(diagram("mermaid_identical_cached", SIMPLE, assets)), "Identical diagram missed session cache");
            }
            diagram("mermaid_multiple_nodes", MULTIPLE, assets);
            diagram("mermaid_changed", "flowchart LR\n  A --> B --> C", assets);

            String html = render("# Rich document\n\n![local](local.png)\n\n$x^2$\n\n```mermaid\n" + SIMPLE + "\n```\n", assets, source);
            require(html.contains(local.toUri().toASCIIString()), "Relative local image was not resolved");
            require(html.contains("alt=\"math\"") && html.contains("alt=\"Mermaid diagram\""), "Rich assets missing");
            require(!html.contains("Mermaid preview error"), "Valid diagram became an error fallback");
            var images = Pattern.compile("<img[^>]+src=\"([^\"]+)\"").matcher(html);
            int count = 0;
            while (images.find()) { image(images.group(1)); count++; }
            require(count >= 3, "Expected local, math and Mermaid images");
            SwingUtilities.invokeAndWait(() -> {
                JEditorPane pane = new JEditorPane();
                pane.setEditorKit(new MarkdownPreviewHtmlKit());
                pane.setText(html);
                pane.setSize(640, 480);
                require(pane.getPreferredSize().height > 0, "HTML installation/layout failed");
            });

            try {
                assets.renderMermaid(INVALID);
                throw new AssertionError("Invalid Mermaid returned an image");
            } catch (IOException expectedError) {
                require(expectedError.getMessage() != null && !expectedError.getMessage().isBlank(), "Missing render error");
            }
            String invalid = render("```mermaid\n" + INVALID + "\n```\n", assets, source);
            require(invalid.contains("Mermaid preview error") && invalid.contains(INVALID), "Error path lost the source fence");
            require(invalid.contains("language-mermaid") && !invalid.contains("alt=\"Mermaid diagram\""), "Invalid fence became successful content");
            require(first.equals(diagram("mermaid_valid_after_invalid", SIMPLE, assets)), "Invalid diagram poisoned cached rendering");
            diagram("mermaid_changed_after_invalid", "flowchart LR\n  Recovery --> Good", assets);
            memory("after_renders_no_forced_gc");
        }
        // Another Markdown document/session in the same process must still discover and use the renderer.
        try (MarkdownPreviewAssets assets = new MarkdownPreviewAssets()) {
            Path second = directory.resolve("second.md");
            Files.writeString(second, "# Second document\n");
            require(render("# Second document", assets, second).contains("Second document"), "Second document failed");
            String html = render("```mermaid\n" + SIMPLE + "\n```\n", assets, second);
            require(html.contains("alt=\"Mermaid diagram\"") && !html.contains("Mermaid preview error"), "Second session render failed");
        }
        memory("after_assets_close_no_forced_gc");
        System.out.println("SMOKE_PASS");
        System.exit(0);
    }
}
