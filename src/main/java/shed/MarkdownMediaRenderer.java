package shed;

import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.channels.Channels;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;
import java.util.function.BooleanSupplier;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import org.apache.batik.transcoder.*;
import org.apache.batik.transcoder.image.ImageTranscoder;
import org.w3c.dom.Document;
import static shed.MarkdownMedia.*;
import static shed.MarkdownMediaService.*;

/** Worker-local adapters. No preview object, HTTP client, temporary asset files or HTML layout. */
final class MarkdownMediaRenderer implements Renderer {
    static final int MAX_FILE_BYTES = 4 * 1024 * 1024;
    static final int MAX_SVG_CHARS = 2 * 1024 * 1024;
    @Override public Prepared prepare(Request request, BooleanSupplier cancelled) throws Exception {
        worker(); check(cancelled);
        if (request.kind() == Kind.MATH) safeMath(request.content());
        if (request.kind() != Kind.IMAGE) return new Prepared(request.key(), request, null);
        Context context = request.context().orElseThrow();
        Path source = WorkspaceRootResolver.normalizedIdentity(context.file());
        Path owning = WorkspaceRootResolver.configuredRoot(source, context.roots());
        if (owning == null) throw new Rejected(State.UNSUPPORTED, "Image source is outside configured project scope");
        Path root = owning.toRealPath();
        List<Path> nested = context.roots().stream().map(WorkspaceRootResolver::normalizedIdentity).filter(p -> !p.equals(root) && p.startsWith(root)).toList();
        var origin = new MarkdownLink.Origin(source, Optional.of(request.stamp().document()), "media");
        var occurrence = new MarkdownLink.Occurrence(origin, MarkdownLink.Kind.IMAGE, request.ranges(), request.content(),
            Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty());
        var resolution = new MarkdownLinkResolver(root, nested, Map.of(), true).resolve(occurrence);
        if (resolution.status() != MarkdownLink.Status.RESOLVED)
            throw new Rejected(State.UNSUPPORTED, "Image destination: " + resolution.status().name().toLowerCase(Locale.ROOT));
        Path file = resolution.target().orElseThrow();
        var before = Files.readAttributes(file, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (!before.isRegularFile() || before.size() < 1 || before.size() > MAX_FILE_BYTES) throw new Rejected(State.RESOURCE_LIMIT, "Image exceeds 4 MiB compressed-input budget");
        byte[] bytes;
        try (var channel = Files.newByteChannel(file, Set.of(StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS));
             var input = Channels.newInputStream(channel); var output = new ByteArrayOutputStream((int) before.size())) {
            byte[] chunk = new byte[8192]; int length;
            while ((length = input.read(chunk)) != -1) { check(cancelled); if ((long) output.size() + length > MAX_FILE_BYTES) throw new Rejected(State.RESOURCE_LIMIT, "Image input grew beyond budget"); output.write(chunk, 0, length); }
            bytes = output.toByteArray();
        }
        var after = Files.readAttributes(file, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        var fresh = new MarkdownLinkResolver(root, nested, Map.of(), true).resolve(occurrence);
        if (!before.lastModifiedTime().equals(after.lastModifiedTime()) || before.size() != after.size()
                || !Objects.equals(before.fileKey(), after.fileKey()) || !fresh.target().equals(Optional.of(file)))
            throw new Rejected(State.SUPERSEDED, "Image changed during capture");
        check(cancelled);
        return new Prepared("image|" + file + "|" + after.fileKey() + "|" + after.lastModifiedTime() + "|" + hash(bytes) + "|" + request.options(), request, bytes);
    }
    @Override public BufferedImage render(Prepared prepared, BooleanSupplier cancelled) throws Exception {
        worker(); check(cancelled); var request = prepared.request(); var options = request.options();
        return switch (request.kind()) {
            case MATH -> {
                try { yield MarkdownPreviewAssets.renderMathImage(request.content(), request.display(), new Color(options.foreground(), true),
                    options.mathSize() * (request.display() ? 1.25f : 1f), options.maxWidth(), options.maxHeight()); }
                catch (IOException error) { throw new Rejected(State.RESOURCE_LIMIT, "Math output exceeds sizing budget"); }
            }
            case IMAGE -> {
                byte[] bytes = prepared.imageBytes(); String prefix = new String(bytes, 0, Math.min(4096, bytes.length), java.nio.charset.StandardCharsets.UTF_8).stripLeading();
                if (prefix.startsWith("\uFEFF")) prefix = prefix.substring(1).stripLeading();
                if (prefix.startsWith("<svg") || prefix.startsWith("<?xml")) {
                    String svg = new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
                    if (svg.length() > MAX_SVG_CHARS) throw new Rejected(State.RESOURCE_LIMIT, "SVG source budget");
                    yield svg(svg, options);
                }
                try { yield MarkdownPreviewAssets.decodeRasterImage(bytes, Math.max(options.maxWidth(), options.maxHeight()), 4L * 1024 * 1024); }
                catch (IOException error) {
                    throw new Rejected(error.getMessage() != null && error.getMessage().contains("dimensions exceed") ? State.RESOURCE_LIMIT : State.UNSUPPORTED, "Image decoding unavailable or over budget");
                }
            }
            case MERMAID -> {
                String source = request.content();
                if (options.theme() != Theme.DEFAULT) source = "%%{init: {\"theme\":\"" + options.theme().name().toLowerCase(Locale.ROOT) + "\"}}%%\n" + source;
                String svg = MarkdownPreviewAssets.renderMermaidSvg(source);
                check(cancelled);
                if (svg == null || svg.isBlank()) throw new IOException("Mermaid produced no diagram");
                if (svg.length() > MAX_SVG_CHARS) throw new Rejected(State.RESOURCE_LIMIT, "Mermaid SVG output budget");
                // Same baseline cleanup as Mermaid.svgToImage; bounded rasterization precedes allocation.
                svg = svg.replaceAll("\\s+(alignment-baseline|dominant-baseline)\\s*=\\s*([\"'])[^\"']*\\2", "")
                    .replaceAll("(alignment-baseline|dominant-baseline)\\s*:\\s*[^;\"'}<]+[;]?", "");
                yield com.aresstack.Mermaid.autoCrop(svg(svg, options));
            }
        };
    }
    private static BufferedImage svg(String source, Options options) throws Exception {
        String lower = source.toLowerCase(Locale.ROOT);
        if (lower.contains("<!doctype") || lower.contains("<!entity")) throw new Rejected(State.UNSUPPORTED, "SVG declarations unsupported");
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance(); factory.setNamespaceAware(true); factory.setXIncludeAware(false); factory.setExpandEntityReferences(false);
        factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, ""); factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
        Document document = factory.newDocumentBuilder().parse(new org.xml.sax.InputSource(new StringReader(source)));
        var elements = document.getElementsByTagName("*");
        if (elements.getLength() > 16384) throw new Rejected(State.RESOURCE_LIMIT, "SVG element budget");
        for (int i = 0; i < elements.getLength(); i++) {
            var element = elements.item(i); String name = element.getLocalName();
            if (Set.of("script", "foreignObject", "image", "use", "a", "animate", "set").contains(name)) throw new Rejected(State.UNSUPPORTED, "SVG active or external content unsupported");
            var attributes = element.getAttributes();
            for (int j = 0; j < attributes.getLength(); j++) {
                var attr = attributes.item(j); String value = attr.getNodeValue().toLowerCase(Locale.ROOT);
                if (attr.getNodeName().toLowerCase(Locale.ROOT).startsWith("on") || value.contains("javascript:") || value.contains("@import")
                        || value.matches("(?s).*url\\(\\s*['\"]?\\s*(?!#)[^)].*")) throw new Rejected(State.UNSUPPORTED, "SVG resource unsupported");
            }
        }
        // Batik's own resource policy also applies to style text and nested resource references.
        class Rasterizer extends ImageTranscoder {
            BufferedImage result;
            @Override public BufferedImage createImage(int width, int height) {
                if (width < 1 || height < 1 || width > options.maxWidth() || height > options.maxHeight() || (long) width * height > 4L * 1024 * 1024)
                    throw new IllegalArgumentException("SVG decoded pixel limit");
                return new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
            }
            @Override public void writeImage(BufferedImage image, TranscoderOutput output) { result = image; }
        }
        var transcoder = new Rasterizer();
        transcoder.addTranscodingHint(XMLAbstractTranscoder.KEY_XML_PARSER_VALIDATING, Boolean.FALSE);
        transcoder.addTranscodingHint(SVGAbstractTranscoder.KEY_EXECUTE_ONLOAD, Boolean.FALSE);
        transcoder.addTranscodingHint(SVGAbstractTranscoder.KEY_ALLOWED_SCRIPT_TYPES, "");
        transcoder.addTranscodingHint(SVGAbstractTranscoder.KEY_ALLOW_EXTERNAL_RESOURCES, Boolean.FALSE);
        transcoder.addTranscodingHint(SVGAbstractTranscoder.KEY_MAX_WIDTH, (float) options.maxWidth());
        transcoder.addTranscodingHint(SVGAbstractTranscoder.KEY_MAX_HEIGHT, (float) options.maxHeight());
        transcoder.transcode(new TranscoderInput(document), new TranscoderOutput());
        if (transcoder.result == null) throw new IOException("SVG produced no image");
        return transcoder.result;
    }
    private static final Set<String> MATH_COMMANDS = Set.of(
        "frac", "dfrac", "tfrac", "sqrt", "sum", "prod", "int", "iint", "oint", "lim", "limits", "nolimits",
        "left", "right", "big", "Big", "bigg", "Bigg", "sin", "cos", "tan", "cot", "sec", "csc", "arcsin", "arccos", "arctan",
        "sinh", "cosh", "tanh", "log", "ln", "exp", "min", "max", "det", "gcd", "inf", "sup", "to", "infty",
        "alpha", "beta", "gamma", "delta", "epsilon", "varepsilon", "zeta", "eta", "theta", "vartheta", "iota", "kappa", "lambda",
        "mu", "nu", "xi", "pi", "varpi", "rho", "sigma", "tau", "upsilon", "phi", "varphi", "chi", "psi", "omega",
        "Gamma", "Delta", "Theta", "Lambda", "Xi", "Pi", "Sigma", "Upsilon", "Phi", "Psi", "Omega",
        "text", "textrm", "mathrm", "mathbf", "mathit", "mathsf", "mathtt", "mathcal", "mathbb", "operatorname",
        "overline", "underline", "hat", "bar", "vec", "dot", "ddot", "overbrace", "underbrace", "substack",
        "times", "cdot", "div", "pm", "mp", "le", "leq", "ge", "geq", "ne", "neq", "approx", "equiv", "sim", "simeq",
        "in", "notin", "subset", "subseteq", "supset", "supseteq", "cup", "cap", "land", "lor", "neg", "forall", "exists",
        "partial", "nabla", "ell", "hbar", "emptyset", "ldots", "cdots", "vdots", "ddots", "quad", "qquad",
        "langle", "rangle", "lbrace", "rbrace", "vert", "Vert", "lvert", "rvert", "lVert", "rVert",
        "rightarrow", "leftarrow", "leftrightarrow", "Rightarrow", "Leftarrow", "Leftrightarrow");
    private static void safeMath(String source) throws Rejected {
        int depth = 0;
        for (int i = 0; i < source.length(); i++) {
            char c = source.charAt(i);
            if (c == '{' && ++depth > 32) throw new Rejected(State.RESOURCE_LIMIT, "Math nesting limit");
            if (c == '}') depth--;
            if (c != '\\') continue;
            int start = ++i;
            while (i < source.length() && Character.isLetter(source.charAt(i))) i++;
            if (i > start) {
                if (!MATH_COMMANDS.contains(source.substring(start, i))) throw new Rejected(State.UNSUPPORTED, "Math command outside isolated rendering subset");
                i--;
            }
        }
    }
    private static void worker() { if (javax.swing.SwingUtilities.isEventDispatchThread()) throw new IllegalStateException("Media processing on EDT"); }
    private static void check(BooleanSupplier cancelled) { if (cancelled.getAsBoolean() || Thread.currentThread().isInterrupted()) throw new java.util.concurrent.CancellationException(); }
}
