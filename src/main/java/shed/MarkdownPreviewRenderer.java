package shed;

import java.awt.Color;
import java.awt.Font;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class MarkdownPreviewRenderer {
    private static final Pattern FENCE = Pattern.compile("^\\s{0,3}(`{3,}|~{3,})\\s*([^`]*)$");
    private static final Pattern HEADING = Pattern.compile("^\\s{0,3}(#{1,6})\\s+(.+?)\\s*#*\\s*$");
    private static final Pattern UNORDERED_LIST = Pattern.compile("^\\s*[-+*]\\s+(.+)$");
    private static final Pattern ORDERED_LIST = Pattern.compile("^\\s*\\d+[.)]\\s+(.+)$");
    private static final Pattern TABLE_SEPARATOR = Pattern.compile("^\\s*\\|?\\s*:?-{3,}:?\\s*(?:\\|\\s*:?-{3,}:?\\s*)+\\|?\\s*$");
    private static final Pattern STRONG_ASTERISKS = Pattern.compile("(?<!\\*)\\*\\*([^*\\n]+)\\*\\*");
    private static final Pattern STRONG_UNDERSCORES = Pattern.compile("(?<!_)__([^_\\n]+)__");
    private static final Pattern STRIKETHROUGH = Pattern.compile("~~([^~\\n]+)~~");
    private static final Pattern EMPHASIS_ASTERISKS = Pattern.compile("(?<!\\*)\\*([^*\\n]+)\\*");
    private static final Pattern EMPHASIS_UNDERSCORES = Pattern.compile("(?<!_)_([^_\\n]+)_");

    private MarkdownPreviewRenderer() {}

    static String render(String markdown, String title, Font font, Color background, Color foreground) {
        String safeTitle = title == null || title.isBlank() ? "Markdown Preview" : title;
        Font safeFont = font == null ? new Font(Font.DIALOG, Font.PLAIN, 13) : font;
        Color safeBackground = background == null ? Color.WHITE : background;
        Color safeForeground = foreground == null ? Color.BLACK : foreground;
        StringBuilder html = new StringBuilder(4096);
        html.append("<!doctype html><html><head><meta charset=\"utf-8\"><title>")
            .append(escapeHtml(safeTitle)).append("</title><style>")
            .append("body{margin:0;padding:22px 26px;font-family:'").append(escapeCss(safeFont.getFamily()))
            .append("';font-size:").append(Math.max(8, safeFont.getSize())).append("pt;line-height:1.5;background:")
            .append(colorHex(safeBackground)).append(";color:").append(colorHex(safeForeground)).append(";}")
            .append("h1,h2,h3,h4,h5,h6{line-height:1.2;margin:1.15em 0 .45em;}h1{font-size:1.9em;border-bottom:1px solid #888;padding-bottom:.25em;}")
            .append("p{margin:.55em 0;}a{color:#2780e3;}pre{padding:12px;overflow:auto;background:#20242b;color:#f4f4f4;}code{font-family:monospace;background:#00000018;padding:1px 3px;}pre code{background:transparent;padding:0;}blockquote{margin:.7em 0;padding:.1em .9em;border-left:4px solid #8b949e;color:#6e7681;}table{border-collapse:collapse;margin:.8em 0;}th,td{border:1px solid #8b949e;padding:5px 8px;}th{background:#00000014;}hr{border:0;border-top:1px solid #8b949e;margin:1.2em 0;}ul,ol{padding-left:1.6em;}.task{font-family:monospace;}.image-link{font-style:italic;}")
            .append("</style></head><body>");
        renderBlocks(normalize(markdown), html);
        html.append("</body></html>");
        return html.toString();
    }

    private static void renderBlocks(String markdown, StringBuilder html) {
        String[] lines = markdown.split("\\n", -1);
        Map<String, Integer> headingIds = new HashMap<>();
        for (int index = 0; index < lines.length;) {
            String line = lines[index];
            if (line.isBlank()) {
                index++;
                continue;
            }
            Matcher fence = FENCE.matcher(line);
            if (fence.matches()) {
                String marker = fence.group(1);
                String language = sanitizeLanguage(fence.group(2));
                StringBuilder code = new StringBuilder();
                index++;
                while (index < lines.length && !lines[index].trim().startsWith(marker)) {
                    code.append(lines[index++]).append('\n');
                }
                if (index < lines.length) index++;
                html.append("<pre><code");
                if (!language.isEmpty()) html.append(" class=\"language-").append(language).append("\"");
                html.append(">").append(escapeHtml(code.toString())).append("</code></pre>");
                continue;
            }
            Matcher heading = HEADING.matcher(line);
            if (heading.matches()) {
                int level = heading.group(1).length();
                String text = heading.group(2).trim();
                String id = uniqueHeadingId(text, headingIds);
                html.append("<h").append(level).append(" id=\"").append(id).append("\">")
                    .append(renderInline(text)).append("</h").append(level).append(">");
                index++;
                continue;
            }
            if (isHorizontalRule(line)) {
                html.append("<hr>");
                index++;
                continue;
            }
            if (index + 1 < lines.length && line.contains("|") && TABLE_SEPARATOR.matcher(lines[index + 1]).matches()) {
                index = renderTable(lines, index, html);
                continue;
            }
            if (line.startsWith(">")) {
                StringBuilder quote = new StringBuilder();
                while (index < lines.length && lines[index].startsWith(">")) {
                    if (!quote.isEmpty()) quote.append("<br>");
                    String quoteLine = lines[index++].substring(1);
                    if (quoteLine.startsWith(" ")) quoteLine = quoteLine.substring(1);
                    quote.append(renderInline(quoteLine));
                }
                html.append("<blockquote>").append(quote).append("</blockquote>");
                continue;
            }
            Matcher unordered = UNORDERED_LIST.matcher(line);
            Matcher ordered = ORDERED_LIST.matcher(line);
            if (unordered.matches() || ordered.matches()) {
                boolean isOrdered = ordered.matches();
                html.append(isOrdered ? "<ol>" : "<ul>");
                while (index < lines.length) {
                    Matcher item = (isOrdered ? ORDERED_LIST : UNORDERED_LIST).matcher(lines[index]);
                    if (!item.matches()) break;
                    html.append("<li>").append(renderListItem(item.group(1))).append("</li>");
                    index++;
                }
                html.append(isOrdered ? "</ol>" : "</ul>");
                continue;
            }
            StringBuilder paragraph = new StringBuilder();
            while (index < lines.length && !lines[index].isBlank() && !startsBlock(lines, index)) {
                if (!paragraph.isEmpty()) paragraph.append("<br>");
                paragraph.append(renderInline(lines[index++]));
            }
            if (paragraph.isEmpty()) {
                paragraph.append(renderInline(lines[index++]));
            }
            html.append("<p>").append(paragraph).append("</p>");
        }
    }

    private static boolean startsBlock(String[] lines, int index) {
        String line = lines[index];
        return FENCE.matcher(line).matches() || HEADING.matcher(line).matches() || isHorizontalRule(line)
            || line.startsWith(">") || UNORDERED_LIST.matcher(line).matches() || ORDERED_LIST.matcher(line).matches()
            || (index + 1 < lines.length && line.contains("|") && TABLE_SEPARATOR.matcher(lines[index + 1]).matches());
    }

    private static int renderTable(String[] lines, int index, StringBuilder html) {
        List<String> headers = tableCells(lines[index]);
        List<String> alignments = tableAlignments(lines[index + 1]);
        html.append("<table><thead><tr>");
        for (int column = 0; column < headers.size(); column++) {
            html.append("<th").append(alignmentAttribute(alignments, column)).append(">")
                .append(renderInline(headers.get(column))).append("</th>");
        }
        html.append("</tr></thead><tbody>");
        index += 2;
        while (index < lines.length && !lines[index].isBlank() && lines[index].contains("|")) {
            List<String> cells = tableCells(lines[index++]);
            html.append("<tr>");
            for (int column = 0; column < headers.size(); column++) {
                String cell = column < cells.size() ? cells.get(column) : "";
                html.append("<td").append(alignmentAttribute(alignments, column)).append(">")
                    .append(renderInline(cell)).append("</td>");
            }
            html.append("</tr>");
        }
        html.append("</tbody></table>");
        return index;
    }

    private static List<String> tableCells(String line) {
        String trimmed = line.trim();
        if (trimmed.startsWith("|")) trimmed = trimmed.substring(1);
        if (trimmed.endsWith("|") && !trimmed.endsWith("\\|")) trimmed = trimmed.substring(0, trimmed.length() - 1);
        List<String> cells = new ArrayList<>();
        StringBuilder cell = new StringBuilder();
        boolean escaped = false;
        for (int index = 0; index < trimmed.length(); index++) {
            char current = trimmed.charAt(index);
            if (escaped) {
                cell.append(current);
                escaped = false;
            } else if (current == '\\') {
                escaped = true;
            } else if (current == '|') {
                cells.add(cell.toString().trim());
                cell.setLength(0);
            } else {
                cell.append(current);
            }
        }
        if (escaped) cell.append('\\');
        cells.add(cell.toString().trim());
        return cells;
    }

    private static List<String> tableAlignments(String line) {
        List<String> alignments = new ArrayList<>();
        for (String cell : tableCells(line)) {
            String trimmed = cell.trim();
            boolean left = trimmed.startsWith(":");
            boolean right = trimmed.endsWith(":");
            alignments.add(left && right ? "center" : left ? "left" : right ? "right" : "");
        }
        return alignments;
    }

    private static String alignmentAttribute(List<String> alignments, int column) {
        if (column >= alignments.size() || alignments.get(column).isEmpty()) return "";
        return " align=\"" + alignments.get(column) + "\"";
    }

    private static String renderListItem(String item) {
        if (item.length() >= 3 && item.charAt(0) == '[' && item.charAt(2) == ']' && (item.charAt(1) == ' ' || item.charAt(1) == 'x' || item.charAt(1) == 'X')) {
            String mark = item.charAt(1) == ' ' ? "☐" : "☑";
            return "<span class=\"task\">" + mark + "</span> " + renderInline(item.substring(3).trim());
        }
        return renderInline(item);
    }

    private static String renderInline(String source) {
        StringBuilder text = new StringBuilder();
        List<String> tokens = new ArrayList<>();
        for (int index = 0; index < source.length();) {
            char current = source.charAt(index);
            if (current == '\\' && index + 1 < source.length()) {
                text.append(token(tokens, escapeHtml(String.valueOf(source.charAt(index + 1)))));
                index += 2;
                continue;
            }
            if (current == '`') {
                int end = source.indexOf('`', index + 1);
                if (end > index + 1) {
                    text.append(token(tokens, "<code>" + escapeHtml(source.substring(index + 1, end)) + "</code>"));
                    index = end + 1;
                    continue;
                }
            }
            if (current == '!' && index + 1 < source.length() && source.charAt(index + 1) == '[') {
                int labelEnd = source.indexOf("](", index + 2);
                int hrefEnd = labelEnd < 0 ? -1 : source.indexOf(')', labelEnd + 2);
                if (labelEnd >= 0 && hrefEnd >= 0) {
                    String label = source.substring(index + 2, labelEnd);
                    String href = safeHref(source.substring(labelEnd + 2, hrefEnd));
                    String content = href == null ? "<span class=\"image-link\">[image: " + escapeHtml(label) + "]</span>"
                        : "<a class=\"image-link\" href=\"" + escapeAttribute(href) + "\">[image: " + escapeHtml(label) + "]</a>";
                    text.append(token(tokens, content));
                    index = hrefEnd + 1;
                    continue;
                }
            }
            if (current == '[') {
                int labelEnd = source.indexOf("](", index + 1);
                int hrefEnd = labelEnd < 0 ? -1 : source.indexOf(')', labelEnd + 2);
                if (labelEnd >= 0 && hrefEnd >= 0) {
                    String href = safeHref(source.substring(labelEnd + 2, hrefEnd));
                    String label = source.substring(index + 1, labelEnd);
                    String content = href == null ? escapeHtml(label)
                        : "<a href=\"" + escapeAttribute(href) + "\">" + escapeHtml(label) + "</a>";
                    text.append(token(tokens, content));
                    index = hrefEnd + 1;
                    continue;
                }
            }
            text.append(current);
            index++;
        }
        String rendered = escapeHtml(text.toString());
        rendered = replaceAll(STRONG_ASTERISKS, rendered, "<strong>$1</strong>");
        rendered = replaceAll(STRONG_UNDERSCORES, rendered, "<strong>$1</strong>");
        rendered = replaceAll(STRIKETHROUGH, rendered, "<del>$1</del>");
        rendered = replaceAll(EMPHASIS_ASTERISKS, rendered, "<em>$1</em>");
        rendered = replaceAll(EMPHASIS_UNDERSCORES, rendered, "<em>$1</em>");
        for (int index = 0; index < tokens.size(); index++) {
            rendered = rendered.replace(tokenMarker(index), tokens.get(index));
        }
        return rendered;
    }

    private static String replaceAll(Pattern pattern, String input, String replacement) {
        return pattern.matcher(input).replaceAll(replacement);
    }

    private static String token(List<String> tokens, String html) {
        int index = tokens.size();
        tokens.add(html);
        return tokenMarker(index);
    }

    private static String tokenMarker(int index) {
        return "\uE000" + index + "\uE001";
    }

    private static String safeHref(String rawHref) {
        String href = rawHref == null ? "" : rawHref.trim();
        if (href.startsWith("<") && href.endsWith(">") && href.length() > 2) href = href.substring(1, href.length() - 1).trim();
        if (href.isEmpty() || href.chars().anyMatch(Character::isWhitespace) || href.chars().anyMatch(Character::isISOControl)) return null;
        int colon = href.indexOf(':');
        int slash = href.indexOf('/');
        int hash = href.indexOf('#');
        if (colon < 0 || (slash >= 0 && slash < colon) || (hash >= 0 && hash < colon)) return href;
        String scheme = href.substring(0, colon).toLowerCase(Locale.ROOT);
        return switch (scheme) {
            case "http", "https", "mailto", "file" -> href;
            default -> null;
        };
    }

    private static boolean isHorizontalRule(String line) {
        String compact = line.replace(" ", "").trim();
        if (compact.length() < 3) return false;
        char marker = compact.charAt(0);
        if (marker != '-' && marker != '*' && marker != '_') return false;
        for (int index = 1; index < compact.length(); index++) {
            if (compact.charAt(index) != marker) return false;
        }
        return true;
    }

    private static String uniqueHeadingId(String text, Map<String, Integer> seen) {
        StringBuilder id = new StringBuilder();
        boolean lastDash = false;
        for (int index = 0; index < text.length(); index++) {
            char current = Character.toLowerCase(text.charAt(index));
            if (Character.isLetterOrDigit(current)) {
                id.append(current);
                lastDash = false;
            } else if (!lastDash && !id.isEmpty()) {
                id.append('-');
                lastDash = true;
            }
        }
        while (!id.isEmpty() && id.charAt(id.length() - 1) == '-') id.setLength(id.length() - 1);
        String base = id.isEmpty() ? "section" : id.toString();
        int occurrence = seen.merge(base, 1, Integer::sum);
        return occurrence == 1 ? base : base + "-" + occurrence;
    }

    private static String sanitizeLanguage(String language) {
        StringBuilder safe = new StringBuilder();
        for (int index = 0; index < language.length(); index++) {
            char current = language.charAt(index);
            if (Character.isLetterOrDigit(current) || current == '_' || current == '-' || current == '+') safe.append(current);
        }
        return safe.toString();
    }

    private static String normalize(String markdown) {
        return (markdown == null ? "" : markdown).replace("\r\n", "\n").replace('\r', '\n');
    }

    static String escapeHtml(String value) {
        return value == null ? "" : value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
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
