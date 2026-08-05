package shed;

import java.awt.Color;
import java.awt.Font;
import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.commonmark.Extension;
import org.commonmark.ext.autolink.AutolinkExtension;
import org.commonmark.ext.footnotes.FootnotesExtension;
import org.commonmark.ext.gfm.alerts.AlertsExtension;
import org.commonmark.ext.gfm.strikethrough.StrikethroughExtension;
import org.commonmark.ext.gfm.tables.TablesExtension;
import org.commonmark.ext.heading.anchor.HeadingAnchorExtension;
import org.commonmark.ext.task.list.items.TaskListItemsExtension;
import org.commonmark.parser.Parser;
import org.commonmark.renderer.html.HtmlRenderer;
import org.commonmark.renderer.html.UrlSanitizer;

final class MarkdownPreviewRenderer {
    private static final Pattern FENCE = Pattern.compile("^(\\s{0,3})(`{3,}|~{3,})\\s*([^\\s`]*)[^`]*$");
    private static final Pattern IMAGE_TAG = Pattern.compile("<img\\s+([^>]*?)/?>");
    private static final Pattern ATTRIBUTE = Pattern.compile("\\b([A-Za-z][A-Za-z0-9-]*)=\"([^\"]*)\"");
    private static final List<Extension> EXTENSIONS = List.of(
        AutolinkExtension.create(), FootnotesExtension.create(), AlertsExtension.create(), StrikethroughExtension.create(),
        TablesExtension.create(), HeadingAnchorExtension.create(), TaskListItemsExtension.create()
    );
    private static final UrlSanitizer PREVIEW_URL_SANITIZER = new UrlSanitizer() {
        @Override public String sanitizeLinkUrl(String url) { return safeUrl(url); }
        @Override public String sanitizeImageUrl(String url) { return safeUrl(url); }
    };
    private static final Parser PARSER = Parser.builder().extensions(EXTENSIONS).build();
    private static final HtmlRenderer HTML = HtmlRenderer.builder().extensions(EXTENSIONS).escapeHtml(true)
        .sanitizeUrls(true).urlSanitizer(PREVIEW_URL_SANITIZER).build();

    private MarkdownPreviewRenderer() {}

    static String render(String markdown, String title, Font font, Color background, Color foreground,
                         MarkdownPreviewAssets assets, File sourceFile) {
        String safeTitle = title == null || title.isBlank() ? "Markdown Preview" : title;
        Font safeFont = font == null ? new Font(Font.DIALOG, Font.PLAIN, 13) : font;
        Color safeBackground = background == null ? Color.WHITE : background;
        Color safeForeground = foreground == null ? Color.BLACK : foreground;
        String prepared = prepare(markdown == null ? "" : markdown, assets, safeForeground);
        String body = restrictImageSources(HTML.render(PARSER.parse(prepared)), sourceFile);
        return "<!doctype html><html><head><meta charset=\"utf-8\"><title>" + escapeHtml(safeTitle) + "</title><style>"
            + "body{margin:0;padding:22px 26px;font-family:'" + escapeCss(safeFont.getFamily()) + "';font-size:"
            + Math.max(8, safeFont.getSize()) + "pt;line-height:1.5;background:" + colorHex(safeBackground) + ";color:"
            + colorHex(safeForeground) + ";}h1,h2,h3,h4,h5,h6{line-height:1.2;margin:1.15em 0 .45em;}"
            + "h1{font-size:1.9em;border-bottom:1px solid #888;padding-bottom:.25em;}p{margin:.55em 0;}a{color:#2780e3;}"
            + "pre{padding:12px;overflow:auto;background:#20242b;color:#f4f4f4;border-radius:4px;}pre code{background:transparent;padding:0;}"
            + "code{font-family:monospace;background:#00000018;padding:1px 3px;border-radius:3px;}blockquote{margin:.7em 0;padding:.1em .9em;border-left:4px solid #8b949e;color:#6e7681;}"
            + "table{border-collapse:collapse;margin:.8em 0;}th,td{border:1px solid #8b949e;padding:5px 8px;}th{background:#00000014;}"
            + "hr{border:0;border-top:1px solid #8b949e;margin:1.2em 0;}img{max-width:100%;height:auto;}"
            + ".task-list-item{list-style-type:none;}.task-list-item input{margin-right:.45em;}.markdown-alert{padding:.5em .8em;border-left:4px solid #2780e3;background:#0000000d;}"
            + ".blocked-image{font-style:italic;color:#6e7681;}</style></head><body>" + body + "</body></html>";
    }

    private static String prepare(String markdown, MarkdownPreviewAssets assets, Color foreground) {
        return replaceMath(replaceMermaid(normalize(markdown), assets), assets, foreground);
    }

    private static String replaceMermaid(String markdown, MarkdownPreviewAssets assets) {
        String[] lines = markdown.split("\\n", -1);
        StringBuilder output = new StringBuilder(markdown.length());
        for (int index = 0; index < lines.length;) {
            Matcher fence = FENCE.matcher(lines[index]);
            if (!fence.matches() || !"mermaid".equalsIgnoreCase(fence.group(3))) {
                appendLine(output, lines[index++]);
                continue;
            }
            int start = index++;
            String marker = fence.group(2);
            StringBuilder diagram = new StringBuilder();
            while (index < lines.length && !lines[index].trim().startsWith(marker)) {
                if (!diagram.isEmpty()) diagram.append('\n');
                diagram.append(lines[index++]);
            }
            if (index >= lines.length) {
                for (int line = start; line < lines.length; line++) appendLine(output, lines[line]);
                break;
            }
            String closeLine = lines[index++];
            try {
                appendLine(output, "![Mermaid diagram](" + assets.renderMermaid(diagram.toString()) + ")");
            } catch (IOException error) {
                appendLine(output, "> **Mermaid preview error:** " + error.getMessage());
                appendLine(output, lines[start]);
                for (String line : diagram.toString().split("\\n", -1)) appendLine(output, line);
                appendLine(output, closeLine);
            }
        }
        return output.toString();
    }

    private static String replaceMath(String markdown, MarkdownPreviewAssets assets, Color foreground) {
        String[] lines = markdown.split("\\n", -1);
        StringBuilder output = new StringBuilder(markdown.length());
        for (int index = 0; index < lines.length;) {
            Matcher fence = FENCE.matcher(lines[index]);
            if (fence.matches()) {
                String marker = fence.group(2);
                appendLine(output, lines[index++]);
                while (index < lines.length) {
                    String line = lines[index++];
                    appendLine(output, line);
                    if (line.trim().startsWith(marker)) break;
                }
                continue;
            }
            String trimmed = lines[index].trim();
            if (trimmed.startsWith("$$")) {
                int close = trimmed.indexOf("$$", 2);
                if (close > 2 && trimmed.substring(close + 2).isBlank()) {
                    appendMath(output, trimmed.substring(2, close), true, assets, foreground, lines[index++]);
                    continue;
                }
                StringBuilder formula = new StringBuilder();
                int start = index++;
                while (index < lines.length && !lines[index].trim().equals("$$")) {
                    if (!formula.isEmpty()) formula.append('\n');
                    formula.append(lines[index++]);
                }
                if (index < lines.length) {
                    index++;
                    appendMath(output, formula.toString(), true, assets, foreground, joinLines(lines, start, index));
                    continue;
                }
                appendLine(output, lines[start]);
                for (String line : formula.toString().split("\\n", -1)) appendLine(output, line);
                break;
            }
            if (trimmed.startsWith("\\[")) {
                int close = trimmed.indexOf("\\]", 2);
                if (close > 2 && trimmed.substring(close + 2).isBlank()) {
                    appendMath(output, trimmed.substring(2, close), true, assets, foreground, lines[index++]);
                    continue;
                }
                StringBuilder formula = new StringBuilder();
                int start = index++;
                while (index < lines.length && !lines[index].trim().equals("\\]")) {
                    if (!formula.isEmpty()) formula.append('\n');
                    formula.append(lines[index++]);
                }
                if (index < lines.length) {
                    index++;
                    appendMath(output, formula.toString(), true, assets, foreground, joinLines(lines, start, index));
                    continue;
                }
                appendLine(output, lines[start]);
                for (String line : formula.toString().split("\\n", -1)) appendLine(output, line);
                break;
            }
            appendLine(output, replaceInlineMath(lines[index++], assets, foreground));
        }
        return output.toString();
    }

    private static String replaceInlineMath(String line, MarkdownPreviewAssets assets, Color foreground) {
        StringBuilder output = new StringBuilder(line.length());
        for (int index = 0; index < line.length();) {
            if (line.charAt(index) == '`') {
                int end = line.indexOf('`', index + 1);
                if (end >= 0) {
                    output.append(line, index, end + 1);
                    index = end + 1;
                    continue;
                }
            }
            if (line.startsWith("\\(", index)) {
                int end = line.indexOf("\\)", index + 2);
                if (end > index + 2) {
                    String formula = line.substring(index + 2, end);
                    String replacement = inlineMathReplacement(formula, assets, foreground);
                    if (replacement != null) {
                        output.append(replacement);
                        index = end + 2;
                        continue;
                    }
                }
            }
            if (line.charAt(index) == '$' && !isEscaped(line, index) && (index + 1 == line.length() || line.charAt(index + 1) != '$')) {
                int end = nextUnescapedDollar(line, index + 1);
                if (end > index + 1) {
                    String formula = line.substring(index + 1, end);
                    String replacement = inlineMathReplacement(formula, assets, foreground);
                    if (replacement != null) {
                        output.append(replacement);
                        index = end + 1;
                        continue;
                    }
                }
            }
            output.append(line.charAt(index++));
        }
        return output.toString();
    }

    private static String inlineMathReplacement(String formula, MarkdownPreviewAssets assets, Color foreground) {
        if (formula.isBlank() || Character.isWhitespace(formula.charAt(0)) || Character.isWhitespace(formula.charAt(formula.length() - 1))) return null;
        try {
            return "![math](" + assets.renderMath(formula, false, foreground) + ")";
        } catch (IOException | RuntimeException ignored) {
            return null;
        }
    }

    private static void appendMath(StringBuilder output, String formula, boolean display, MarkdownPreviewAssets assets, Color foreground, String fallback) {
        try {
            appendLine(output, "![math](" + assets.renderMath(formula, display, foreground) + ")");
        } catch (IOException | RuntimeException error) {
            appendRaw(output, fallback);
        }
    }

    private static String restrictImageSources(String rendered, File sourceFile) {
        Matcher images = IMAGE_TAG.matcher(rendered);
        StringBuffer output = new StringBuffer();
        while (images.find()) {
            String attributes = images.group(1);
            String source = attribute(attributes, "src");
            String alt = attribute(attributes, "alt");
            String local = resolveLocalImage(source, sourceFile);
            String replacement = local == null
                ? "<span class=\"blocked-image\">[image unavailable: " + escapeHtml(alt == null ? "image" : alt) + "]</span>"
                : "<img src=\"" + escapeAttribute(local) + "\" alt=\"" + escapeAttribute(alt == null ? "" : alt) + "\">";
            images.appendReplacement(output, Matcher.quoteReplacement(replacement));
        }
        images.appendTail(output);
        return output.toString();
    }

    private static String resolveLocalImage(String source, File sourceFile) {
        if (source == null || source.isBlank()) return null;
        try {
            URI uri = new URI(unescapeHtml(source));
            Path path;
            if (uri.getScheme() == null) {
                File parent = sourceFile == null ? null : sourceFile.getAbsoluteFile().getParentFile();
                if (parent == null) return null;
                path = Path.of(parent.toURI().resolve(uri)).normalize();
            } else if ("file".equalsIgnoreCase(uri.getScheme())) {
                path = Path.of(uri);
            } else {
                return null;
            }
            return Files.isRegularFile(path) ? path.toUri().toASCIIString() : null;
        } catch (URISyntaxException | IllegalArgumentException error) {
            return null;
        }
    }

    private static String attribute(String attributes, String key) {
        Matcher matcher = ATTRIBUTE.matcher(attributes);
        while (matcher.find()) {
            if (key.equalsIgnoreCase(matcher.group(1))) return matcher.group(2);
        }
        return null;
    }

    private static String safeUrl(String value) {
        if (value == null || value.isBlank()) return "";
        try {
            URI uri = new URI(value);
            String scheme = uri.getScheme();
            if (scheme == null) return value;
            return switch (scheme.toLowerCase(java.util.Locale.ROOT)) {
                case "http", "https", "mailto", "file" -> value;
                default -> "";
            };
        } catch (URISyntaxException error) {
            return "";
        }
    }

    private static int nextUnescapedDollar(String line, int start) {
        for (int index = start; index < line.length(); index++) {
            if (line.charAt(index) == '$' && !isEscaped(line, index)) return index;
        }
        return -1;
    }

    private static boolean isEscaped(String text, int index) {
        int slashes = 0;
        for (int position = index - 1; position >= 0 && text.charAt(position) == '\\'; position--) slashes++;
        return (slashes & 1) == 1;
    }

    private static void appendLine(StringBuilder output, String line) {
        if (!output.isEmpty()) output.append('\n');
        output.append(line);
    }

    private static void appendRaw(StringBuilder output, String value) {
        if (!output.isEmpty()) output.append('\n');
        output.append(value);
    }

    private static String joinLines(String[] lines, int start, int endExclusive) {
        StringBuilder result = new StringBuilder();
        for (int index = start; index < endExclusive; index++) {
            if (!result.isEmpty()) result.append('\n');
            result.append(lines[index]);
        }
        return result.toString();
    }

    private static String normalize(String markdown) {
        return markdown.replace("\r\n", "\n").replace('\r', '\n');
    }

    static String escapeHtml(String value) {
        return value == null ? "" : value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    private static String unescapeHtml(String value) {
        return value.replace("&amp;", "&").replace("&quot;", "\"").replace("&#39;", "'");
    }

    private static String escapeAttribute(String value) {
        return escapeHtml(value).replace("\"", "&quot;").replace("'", "&#39;");
    }

    private static String escapeCss(String value) {
        return value.replace("\\", "\\\\").replace("'", "\\'").replace("\n", "").replace("\r", "");
    }

    private static String colorHex(Color color) {
        return String.format("#%02x%02x%02x", color.getRed(), color.getGreen(), color.getBlue());
    }
}
