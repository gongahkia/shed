package shed;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class MarkdownService {

    // --- Heading detection ---

    private static final Pattern HEADING_PATTERN = Pattern.compile("^(#{1,6})\\s+(.*)$");
    private static final Pattern CHECKBOX_UNCHECKED = Pattern.compile("^(\\s*[-*+]\\s+)\\[ \\](.*)$");
    private static final Pattern CHECKBOX_CHECKED = Pattern.compile("^(\\s*[-*+]\\s+)\\[x\\](.*)$", Pattern.CASE_INSENSITIVE);
    private static final Pattern ORDERED_LIST = Pattern.compile("^(\\s*)(\\d+)\\.\\s+(.*)$");
    private static final Pattern UNORDERED_LIST = Pattern.compile("^(\\s*)([-*+])\\s+(.*)$");
    private static final Pattern TABLE_ROW = Pattern.compile("^\\|(.+)\\|\\s*$");
    private static final Pattern TABLE_SEPARATOR = Pattern.compile("^\\|([:\\-\\s|]+)\\|\\s*$");
    private static final Pattern LINK_PATTERN = Pattern.compile("\\[([^\\]]+)\\]\\(([^)]+)\\)");
    private static final Pattern BOLD_PATTERN = Pattern.compile("\\*\\*(.+?)\\*\\*");
    private static final Pattern ITALIC_PATTERN = Pattern.compile("(?<![*])\\*(?![*])(.+?)(?<![*])\\*(?![*])");
    private static final Pattern STRIKETHROUGH_PATTERN = Pattern.compile("~~(.+?)~~");
    private static final Pattern INLINE_CODE_PATTERN = Pattern.compile("`([^`]+)`");
    private static final Pattern HR_PATTERN = Pattern.compile("^\\s*([-*_])\\1{2,}\\s*$");

    public int headingLevel(String line) {
        if (line == null) return 0;
        Matcher m = HEADING_PATTERN.matcher(line);
        return m.matches() ? m.group(1).length() : 0;
    }

    public String headingText(String line) {
        if (line == null) return "";
        Matcher m = HEADING_PATTERN.matcher(line);
        return m.matches() ? m.group(2) : "";
    }

    public boolean isHeading(String line) {
        return headingLevel(line) > 0;
    }

    // --- Heading folding ---

    public static class FoldRange {
        public final int startLine;
        public final int endLine;
        public final int level;
        public FoldRange(int startLine, int endLine, int level) {
            this.startLine = startLine;
            this.endLine = endLine;
            this.level = level;
        }
    }

    public FoldRange computeFoldRange(String[] lines, int headingLine) {
        int level = headingLevel(lines[headingLine]);
        if (level == 0) return null;
        int end = headingLine;
        for (int i = headingLine + 1; i < lines.length; i++) {
            int otherLevel = headingLevel(lines[i]);
            if (otherLevel > 0 && otherLevel <= level) {
                break;
            }
            end = i;
        }
        if (end == headingLine) return null;
        return new FoldRange(headingLine, end, level);
    }

    public List<FoldRange> computeAllFoldRanges(String[] lines) {
        List<FoldRange> ranges = new ArrayList<>();
        for (int i = 0; i < lines.length; i++) {
            if (isHeading(lines[i])) {
                FoldRange range = computeFoldRange(lines, i);
                if (range != null) {
                    ranges.add(range);
                }
            }
        }
        return ranges;
    }

    // --- Heading navigation ---

    public int nextHeading(String[] lines, int currentLine) {
        for (int i = currentLine + 1; i < lines.length; i++) {
            if (isHeading(lines[i])) return i;
        }
        return -1;
    }

    public int prevHeading(String[] lines, int currentLine) {
        for (int i = currentLine - 1; i >= 0; i--) {
            if (isHeading(lines[i])) return i;
        }
        return -1;
    }

    public int nextHeadingAtLevel(String[] lines, int currentLine, int level) {
        for (int i = currentLine + 1; i < lines.length; i++) {
            if (headingLevel(lines[i]) == level) return i;
        }
        return -1;
    }

    public int prevHeadingAtLevel(String[] lines, int currentLine, int level) {
        for (int i = currentLine - 1; i >= 0; i--) {
            if (headingLevel(lines[i]) == level) return i;
        }
        return -1;
    }

    public int parentHeading(String[] lines, int currentLine) {
        int currentLevel = headingLevel(lines[currentLine]);
        if (currentLevel == 0) {
            for (int i = currentLine - 1; i >= 0; i--) {
                if (isHeading(lines[i])) return i;
            }
            return -1;
        }
        for (int i = currentLine - 1; i >= 0; i--) {
            int level = headingLevel(lines[i]);
            if (level > 0 && level < currentLevel) return i;
        }
        return -1;
    }

    // --- Heading promotion/demotion ---

    public String promoteHeading(String line) {
        int level = headingLevel(line);
        if (level <= 1) return line;
        return line.substring(1);
    }

    public String demoteHeading(String line) {
        int level = headingLevel(line);
        if (level == 0) return line;
        if (level >= 6) return line;
        return "#" + line;
    }

    public String[] promoteSubtree(String[] lines, int headingLine) {
        FoldRange range = computeFoldRange(lines, headingLine);
        if (range == null) {
            String[] result = lines.clone();
            result[headingLine] = promoteHeading(result[headingLine]);
            return result;
        }
        String[] result = lines.clone();
        for (int i = range.startLine; i <= range.endLine; i++) {
            if (isHeading(result[i])) {
                result[i] = promoteHeading(result[i]);
            }
        }
        return result;
    }

    public String[] demoteSubtree(String[] lines, int headingLine) {
        FoldRange range = computeFoldRange(lines, headingLine);
        if (range == null) {
            String[] result = lines.clone();
            result[headingLine] = demoteHeading(result[headingLine]);
            return result;
        }
        String[] result = lines.clone();
        for (int i = range.startLine; i <= range.endLine; i++) {
            if (isHeading(result[i])) {
                result[i] = demoteHeading(result[i]);
            }
        }
        return result;
    }

    // --- Table of contents ---

    public String generateToc(String[] lines) {
        StringBuilder toc = new StringBuilder();
        toc.append("Table of Contents\n");
        toc.append("=================\n\n");
        int tocEntry = 0;
        for (int i = 0; i < lines.length; i++) {
            int level = headingLevel(lines[i]);
            if (level > 0) {
                tocEntry++;
                String indent = "  ".repeat(level - 1);
                String text = headingText(lines[i]);
                toc.append(indent).append(tocEntry).append(". ").append(text)
                   .append(" (line ").append(i + 1).append(")\n");
            }
        }
        if (tocEntry == 0) {
            toc.append("(no headings found)\n");
        }
        return toc.toString();
    }

    // --- Table operations ---

    public boolean isTableRow(String line) {
        return line != null && TABLE_ROW.matcher(line.trim()).matches();
    }

    public boolean isTableSeparator(String line) {
        return line != null && TABLE_SEPARATOR.matcher(line.trim()).matches();
    }

    public boolean isInsideTable(String[] lines, int lineIndex) {
        if (lineIndex < 0 || lineIndex >= lines.length) return false;
        return isTableRow(lines[lineIndex]);
    }

    public int tableStartLine(String[] lines, int lineIndex) {
        if (!isInsideTable(lines, lineIndex)) return -1;
        int start = lineIndex;
        while (start > 0 && isTableRow(lines[start - 1])) {
            start--;
        }
        return start;
    }

    public int tableEndLine(String[] lines, int lineIndex) {
        if (!isInsideTable(lines, lineIndex)) return -1;
        int end = lineIndex;
        while (end < lines.length - 1 && isTableRow(lines[end + 1])) {
            end++;
        }
        return end;
    }

    public String[] parseCells(String line) {
        if (line == null) return new String[0];
        String trimmed = line.trim();
        if (trimmed.startsWith("|")) trimmed = trimmed.substring(1);
        if (trimmed.endsWith("|")) trimmed = trimmed.substring(0, trimmed.length() - 1);
        String[] cells = trimmed.split("\\|", -1);
        for (int i = 0; i < cells.length; i++) {
            cells[i] = cells[i].trim();
        }
        return cells;
    }

    public String formatRow(String[] cells, int[] widths) {
        StringBuilder sb = new StringBuilder("|");
        for (int i = 0; i < cells.length; i++) {
            int w = i < widths.length ? widths[i] : cells[i].length();
            sb.append(" ");
            sb.append(padRight(cells[i], w));
            sb.append(" |");
        }
        return sb.toString();
    }

    public String formatSeparator(int[] widths, String[] alignments) {
        StringBuilder sb = new StringBuilder("|");
        for (int i = 0; i < widths.length; i++) {
            String align = i < alignments.length ? alignments[i] : "left";
            sb.append(" ");
            if ("center".equals(align)) {
                sb.append(":");
                sb.append("-".repeat(Math.max(1, widths[i] - 2)));
                sb.append(":");
            } else if ("right".equals(align)) {
                sb.append("-".repeat(Math.max(1, widths[i] - 1)));
                sb.append(":");
            } else {
                sb.append("-".repeat(widths[i]));
            }
            sb.append(" |");
        }
        return sb.toString();
    }

    public String[] detectAlignments(String separatorLine) {
        String[] cells = parseCells(separatorLine);
        String[] alignments = new String[cells.length];
        for (int i = 0; i < cells.length; i++) {
            String cell = cells[i].trim();
            boolean left = cell.startsWith(":");
            boolean right = cell.endsWith(":");
            if (left && right) alignments[i] = "center";
            else if (right) alignments[i] = "right";
            else alignments[i] = "left";
        }
        return alignments;
    }

    public String alignTable(String[] lines, int startLine, int endLine) {
        List<String[]> rows = new ArrayList<>();
        int separatorIndex = -1;
        String[] alignments = null;

        for (int i = startLine; i <= endLine; i++) {
            if (isTableSeparator(lines[i])) {
                separatorIndex = i - startLine;
                alignments = detectAlignments(lines[i]);
                rows.add(null);
            } else {
                rows.add(parseCells(lines[i]));
            }
        }

        int maxCols = 0;
        for (String[] row : rows) {
            if (row != null) maxCols = Math.max(maxCols, row.length);
        }

        int[] widths = new int[maxCols];
        for (String[] row : rows) {
            if (row == null) continue;
            for (int c = 0; c < row.length; c++) {
                widths[c] = Math.max(widths[c], row[c].length());
            }
        }
        for (int c = 0; c < widths.length; c++) {
            widths[c] = Math.max(widths[c], 3);
        }

        if (alignments == null) {
            alignments = new String[maxCols];
            Arrays.fill(alignments, "left");
        }

        StringBuilder result = new StringBuilder();
        for (int i = 0; i < rows.size(); i++) {
            if (i > 0) result.append("\n");
            if (rows.get(i) == null) {
                result.append(formatSeparator(widths, alignments));
            } else {
                String[] row = rows.get(i);
                String[] padded = new String[maxCols];
                for (int c = 0; c < maxCols; c++) {
                    padded[c] = c < row.length ? row[c] : "";
                }
                result.append(formatRow(padded, widths));
            }
        }
        return result.toString();
    }

    public String sortTable(String[] lines, int startLine, int endLine, int columnIndex, boolean ascending) {
        List<String[]> headerRows = new ArrayList<>();
        List<String[]> dataRows = new ArrayList<>();
        boolean pastSeparator = false;
        String[] alignments = null;
        int separatorRowIndex = -1;

        for (int i = startLine; i <= endLine; i++) {
            if (isTableSeparator(lines[i])) {
                alignments = detectAlignments(lines[i]);
                pastSeparator = true;
                separatorRowIndex = i - startLine;
                continue;
            }
            String[] cells = parseCells(lines[i]);
            if (!pastSeparator) {
                headerRows.add(cells);
            } else {
                dataRows.add(cells);
            }
        }

        final int col = columnIndex;
        dataRows.sort((a, b) -> {
            String va = col < a.length ? a[col] : "";
            String vb = col < b.length ? b[col] : "";
            try {
                double da = Double.parseDouble(va);
                double db = Double.parseDouble(vb);
                return ascending ? Double.compare(da, db) : Double.compare(db, da);
            } catch (NumberFormatException e) {
                return ascending ? va.compareToIgnoreCase(vb) : vb.compareToIgnoreCase(va);
            }
        });

        int maxCols = 0;
        for (String[] r : headerRows) maxCols = Math.max(maxCols, r.length);
        for (String[] r : dataRows) maxCols = Math.max(maxCols, r.length);

        int[] widths = new int[maxCols];
        for (String[] r : headerRows) for (int c = 0; c < r.length; c++) widths[c] = Math.max(widths[c], r[c].length());
        for (String[] r : dataRows) for (int c = 0; c < r.length; c++) widths[c] = Math.max(widths[c], r[c].length());
        for (int c = 0; c < widths.length; c++) widths[c] = Math.max(widths[c], 3);

        if (alignments == null) {
            alignments = new String[maxCols];
            Arrays.fill(alignments, "left");
        }

        StringBuilder result = new StringBuilder();
        for (String[] row : headerRows) {
            if (result.length() > 0) result.append("\n");
            String[] padded = padRow(row, maxCols);
            result.append(formatRow(padded, widths));
        }
        if (separatorRowIndex >= 0) {
            result.append("\n").append(formatSeparator(widths, alignments));
        }
        for (String[] row : dataRows) {
            result.append("\n");
            String[] padded = padRow(row, maxCols);
            result.append(formatRow(padded, widths));
        }
        return result.toString();
    }

    public String insertColumn(String[] lines, int startLine, int endLine, int afterCol) {
        StringBuilder result = new StringBuilder();
        for (int i = startLine; i <= endLine; i++) {
            if (i > startLine) result.append("\n");
            if (isTableSeparator(lines[i])) {
                String[] alignments = detectAlignments(lines[i]);
                List<String> newAlignments = new ArrayList<>(Arrays.asList(alignments));
                int insertAt = Math.min(afterCol + 1, newAlignments.size());
                newAlignments.add(insertAt, "left");
                String[] cells = parseCells(lines[i]);
                int[] widths = new int[newAlignments.size()];
                Arrays.fill(widths, 3);
                result.append(formatSeparator(widths, newAlignments.toArray(new String[0])));
            } else {
                String[] cells = parseCells(lines[i]);
                List<String> newCells = new ArrayList<>(Arrays.asList(cells));
                int insertAt = Math.min(afterCol + 1, newCells.size());
                newCells.add(insertAt, "");
                int[] widths = new int[newCells.size()];
                for (int c = 0; c < newCells.size(); c++) widths[c] = Math.max(3, newCells.get(c).length());
                result.append(formatRow(newCells.toArray(new String[0]), widths));
            }
        }
        return result.toString();
    }

    public String deleteColumn(String[] lines, int startLine, int endLine, int colIndex) {
        StringBuilder result = new StringBuilder();
        for (int i = startLine; i <= endLine; i++) {
            if (i > startLine) result.append("\n");
            if (isTableSeparator(lines[i])) {
                String[] alignments = detectAlignments(lines[i]);
                List<String> newAlignments = new ArrayList<>(Arrays.asList(alignments));
                if (colIndex < newAlignments.size()) newAlignments.remove(colIndex);
                if (newAlignments.isEmpty()) {
                    result.append(lines[i]);
                    continue;
                }
                int[] widths = new int[newAlignments.size()];
                Arrays.fill(widths, 3);
                result.append(formatSeparator(widths, newAlignments.toArray(new String[0])));
            } else {
                String[] cells = parseCells(lines[i]);
                List<String> newCells = new ArrayList<>(Arrays.asList(cells));
                if (colIndex < newCells.size()) newCells.remove(colIndex);
                if (newCells.isEmpty()) {
                    result.append(lines[i]);
                    continue;
                }
                int[] widths = new int[newCells.size()];
                for (int c = 0; c < newCells.size(); c++) widths[c] = Math.max(3, newCells.get(c).length());
                result.append(formatRow(newCells.toArray(new String[0]), widths));
            }
        }
        return result.toString();
    }

    public String newTableRow(int columnCount, int[] widths) {
        String[] cells = new String[columnCount];
        Arrays.fill(cells, "");
        return formatRow(cells, widths);
    }

    public String createTableTemplate(int cols, int rows) {
        int[] widths = new int[cols];
        Arrays.fill(widths, 8);
        String[] header = new String[cols];
        for (int i = 0; i < cols; i++) header[i] = "Col " + (i + 1);
        String[] alignments = new String[cols];
        Arrays.fill(alignments, "left");

        StringBuilder sb = new StringBuilder();
        sb.append(formatRow(header, widths)).append("\n");
        sb.append(formatSeparator(widths, alignments));
        for (int r = 0; r < rows; r++) {
            sb.append("\n");
            String[] cells = new String[cols];
            Arrays.fill(cells, "");
            sb.append(formatRow(cells, widths));
        }
        return sb.toString();
    }

    public int cellColumn(String line, int charOffset) {
        if (line == null || charOffset < 0) return 0;
        int col = 0;
        for (int i = 0; i < Math.min(charOffset, line.length()); i++) {
            if (line.charAt(i) == '|') col++;
        }
        return Math.max(0, col - 1);
    }

    public int nextCellOffset(String line, int currentOffset) {
        if (line == null) return currentOffset;
        for (int i = currentOffset; i < line.length(); i++) {
            if (line.charAt(i) == '|' && i + 1 < line.length()) {
                int next = i + 1;
                while (next < line.length() && line.charAt(next) == ' ') next++;
                return Math.min(next, line.length() - 1);
            }
        }
        return currentOffset;
    }

    public int prevCellOffset(String line, int currentOffset) {
        if (line == null) return currentOffset;
        int pipeCount = 0;
        int lastPipe = -1;
        int secondLastPipe = -1;
        for (int i = 0; i < Math.min(currentOffset, line.length()); i++) {
            if (line.charAt(i) == '|') {
                pipeCount++;
                secondLastPipe = lastPipe;
                lastPipe = i;
            }
        }
        if (secondLastPipe >= 0) {
            int next = secondLastPipe + 1;
            while (next < line.length() && line.charAt(next) == ' ') next++;
            return next;
        }
        if (lastPipe >= 0) {
            int next = lastPipe + 1;
            while (next < line.length() && line.charAt(next) == ' ') next++;
            return next;
        }
        return 0;
    }

    // --- Checkbox toggling ---

    public boolean isCheckbox(String line) {
        return CHECKBOX_UNCHECKED.matcher(line).matches() || CHECKBOX_CHECKED.matcher(line).matches();
    }

    public String toggleCheckbox(String line) {
        Matcher unchecked = CHECKBOX_UNCHECKED.matcher(line);
        if (unchecked.matches()) {
            return unchecked.group(1) + "[x]" + unchecked.group(2);
        }
        Matcher checked = CHECKBOX_CHECKED.matcher(line);
        if (checked.matches()) {
            return checked.group(1) + "[ ]" + checked.group(2);
        }
        return line;
    }

    // --- Smart list continuation ---

    public String listContinuation(String currentLine) {
        if (currentLine == null) return null;

        Matcher checkbox = CHECKBOX_UNCHECKED.matcher(currentLine);
        if (checkbox.matches()) {
            String prefix = checkbox.group(1);
            if (checkbox.group(2).trim().isEmpty()) return null;
            return prefix + "[ ] ";
        }
        Matcher checkedBox = CHECKBOX_CHECKED.matcher(currentLine);
        if (checkedBox.matches()) {
            String prefix = checkedBox.group(1);
            if (checkedBox.group(2).trim().isEmpty()) return null;
            return prefix + "[ ] ";
        }

        Matcher ordered = ORDERED_LIST.matcher(currentLine);
        if (ordered.matches()) {
            if (ordered.group(3).trim().isEmpty()) return null;
            String indent = ordered.group(1);
            int num = Integer.parseInt(ordered.group(2));
            return indent + (num + 1) + ". ";
        }

        Matcher unordered = UNORDERED_LIST.matcher(currentLine);
        if (unordered.matches()) {
            if (unordered.group(3).trim().isEmpty()) return null;
            return unordered.group(1) + unordered.group(2) + " ";
        }

        if (currentLine.matches("^\\s*>\\s+.*$") && !currentLine.matches("^\\s*>\\s*$")) {
            int idx = currentLine.indexOf('>');
            return currentLine.substring(0, idx + 1) + " ";
        }

        return null;
    }

    public boolean isEmptyListItem(String line) {
        if (line == null) return false;
        Matcher ordered = ORDERED_LIST.matcher(line);
        if (ordered.matches() && ordered.group(3).trim().isEmpty()) return true;
        Matcher unordered = UNORDERED_LIST.matcher(line);
        if (unordered.matches() && unordered.group(3).trim().isEmpty()) return true;
        Matcher cb = CHECKBOX_UNCHECKED.matcher(line);
        if (cb.matches() && cb.group(2).trim().isEmpty()) return true;
        return line.matches("^\\s*>\\s*$");
    }

    // --- Link helpers ---

    public String extractLinkUrl(String line, int charOffset) {
        Matcher m = LINK_PATTERN.matcher(line);
        while (m.find()) {
            if (charOffset >= m.start() && charOffset < m.end()) {
                return m.group(2);
            }
        }
        return null;
    }

    public String insertLinkTemplate() {
        return "[text](url)";
    }

    public String insertImageTemplate() {
        return "![alt](path)";
    }

    // --- Horizontal rule detection ---

    public boolean isHorizontalRule(String line) {
        return line != null && HR_PATTERN.matcher(line).matches();
    }

    // --- Code fence language suggestions ---

    private static final String[] COMMON_LANGUAGES = {
        "java", "javascript", "typescript", "python", "rust", "go", "c", "cpp",
        "csharp", "ruby", "php", "swift", "kotlin", "scala", "bash", "shell",
        "sql", "html", "css", "json", "yaml", "toml", "xml", "markdown",
        "lua", "haskell", "elixir", "clojure", "r", "dart", "zig", "nim"
    };

    public String[] getCodeFenceLanguages() {
        return COMMON_LANGUAGES.clone();
    }

    public String[] filterCodeFenceLanguages(String prefix) {
        if (prefix == null || prefix.isEmpty()) return getCodeFenceLanguages();
        String lower = prefix.toLowerCase();
        List<String> matches = new ArrayList<>();
        for (String lang : COMMON_LANGUAGES) {
            if (lang.startsWith(lower)) matches.add(lang);
        }
        for (String lang : COMMON_LANGUAGES) {
            if (lang.contains(lower) && !lang.startsWith(lower)) matches.add(lang);
        }
        return matches.toArray(new String[0]);
    }

    // --- Concealment helpers ---

    public static class ConcealRange {
        public final int start;
        public final int end;
        public final String type;
        public ConcealRange(int start, int end, String type) {
            this.start = start;
            this.end = end;
            this.type = type;
        }
    }

    public List<ConcealRange> findConcealRanges(String line, int lineOffset) {
        List<ConcealRange> ranges = new ArrayList<>();

        Matcher bold = BOLD_PATTERN.matcher(line);
        while (bold.find()) {
            ranges.add(new ConcealRange(lineOffset + bold.start(), lineOffset + bold.start() + 2, "bold_marker"));
            ranges.add(new ConcealRange(lineOffset + bold.end() - 2, lineOffset + bold.end(), "bold_marker"));
        }

        Matcher italic = ITALIC_PATTERN.matcher(line);
        while (italic.find()) {
            ranges.add(new ConcealRange(lineOffset + italic.start(), lineOffset + italic.start() + 1, "italic_marker"));
            ranges.add(new ConcealRange(lineOffset + italic.end() - 1, lineOffset + italic.end(), "italic_marker"));
        }

        Matcher strike = STRIKETHROUGH_PATTERN.matcher(line);
        while (strike.find()) {
            ranges.add(new ConcealRange(lineOffset + strike.start(), lineOffset + strike.start() + 2, "strike_marker"));
            ranges.add(new ConcealRange(lineOffset + strike.end() - 2, lineOffset + strike.end(), "strike_marker"));
        }

        Matcher link = LINK_PATTERN.matcher(line);
        while (link.find()) {
            ranges.add(new ConcealRange(lineOffset + link.start(), lineOffset + link.start() + 1, "link_open"));
            int urlStart = line.indexOf("](", link.start()) + lineOffset;
            ranges.add(new ConcealRange(urlStart, lineOffset + link.end(), "link_url"));
        }

        return ranges;
    }

    // --- Helpers ---

    private String padRight(String s, int width) {
        if (s.length() >= width) return s;
        return s + " ".repeat(width - s.length());
    }

    private String[] padRow(String[] row, int maxCols) {
        String[] padded = new String[maxCols];
        for (int c = 0; c < maxCols; c++) {
            padded[c] = c < row.length ? row[c] : "";
        }
        return padded;
    }
}
