package shed;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class SymbolService {
    public static final class Symbol {
        private final String name;
        private final String kind;
        private final int line;
        private final int level;

        public Symbol(String name, String kind, int line, int level) {
            this.name = name == null ? "" : name.trim();
            this.kind = kind == null ? "symbol" : kind.trim().toLowerCase(Locale.ROOT);
            this.line = Math.max(1, line);
            this.level = Math.max(1, level);
        }

        public String getName() {
            return name;
        }

        public String getKind() {
            return kind;
        }

        public int getLine() {
            return line;
        }

        public int getLevel() {
            return level;
        }
    }

    private static final Pattern MARKDOWN_HEADING = Pattern.compile("^(#{1,6})\\s+(.+?)\\s*$");
    private static final Pattern CLASS_LIKE = Pattern.compile("\\b(class|interface|enum|record|struct|trait)\\s+([A-Za-z_][A-Za-z0-9_]*)\\b");
    private static final Pattern PY_CLASS = Pattern.compile("^\\s*class\\s+([A-Za-z_][A-Za-z0-9_]*)\\b");
    private static final Pattern PY_DEF = Pattern.compile("^\\s*(?:async\\s+)?def\\s+([A-Za-z_][A-Za-z0-9_]*)\\s*\\(");
    private static final Pattern JS_FUNCTION = Pattern.compile("^\\s*(?:export\\s+)?(?:async\\s+)?function\\s+([A-Za-z_][A-Za-z0-9_]*)\\s*\\(");
    private static final Pattern JS_ARROW = Pattern.compile("^\\s*(?:export\\s+)?(?:const|let|var)\\s+([A-Za-z_][A-Za-z0-9_]*)\\s*=\\s*(?:async\\s*)?(?:\\([^)]*\\)|[A-Za-z_][A-Za-z0-9_]*)\\s*=>");
    private static final Pattern JAVA_METHOD = Pattern.compile(
        "^\\s*(?:public|private|protected|internal|static|final|abstract|synchronized|native|inline|virtual|override|sealed|open|extern|async|unsafe|mut|const|default|\\s)+"
            + "(?:[A-Za-z_][A-Za-z0-9_<>,\\[\\]?\\s\\.]*\\s+)?([A-Za-z_][A-Za-z0-9_]*)\\s*\\([^;{}]*\\)\\s*(?:\\{|=>|$)"
    );

    public List<Symbol> collectSymbols(String text, FileType fileType) {
        if (text == null || text.isEmpty()) {
            return Collections.emptyList();
        }
        String[] lines = text.split("\\n", -1);
        if (fileType == FileType.MARKDOWN) {
            return collectMarkdownSymbols(lines);
        }
        return collectCodeSymbols(lines);
    }

    private List<Symbol> collectMarkdownSymbols(String[] lines) {
        List<Symbol> symbols = new ArrayList<>();
        for (int i = 0; i < lines.length; i++) {
            Matcher matcher = MARKDOWN_HEADING.matcher(lines[i]);
            if (!matcher.matches()) {
                continue;
            }
            int level = matcher.group(1).length();
            String name = matcher.group(2).trim();
            if (!name.isEmpty()) {
                symbols.add(new Symbol(name, "heading", i + 1, level));
            }
        }
        return symbols;
    }

    private List<Symbol> collectCodeSymbols(String[] lines) {
        List<Symbol> symbols = new ArrayList<>();
        int braceDepth = 0;
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            String trimmed = line == null ? "" : line.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("//") || trimmed.startsWith("# ")) {
                braceDepth += netBraceDelta(trimmed);
                continue;
            }

            if (tryAddSymbol(symbols, i + 1, line, braceDepth)) {
                braceDepth += netBraceDelta(trimmed);
                continue;
            }
            braceDepth += netBraceDelta(trimmed);
        }
        return symbols;
    }

    private boolean tryAddSymbol(List<Symbol> symbols, int lineNumber, String line, int braceDepth) {
        Matcher markdownHeading = MARKDOWN_HEADING.matcher(line == null ? "" : line);
        if (markdownHeading.matches()) {
            int headingLevel = markdownHeading.group(1).length();
            String heading = markdownHeading.group(2).trim();
            if (!heading.isEmpty()) {
                symbols.add(new Symbol(heading, "heading", lineNumber, headingLevel));
                return true;
            }
        }

        Matcher pyClass = PY_CLASS.matcher(line == null ? "" : line);
        if (pyClass.find()) {
            int indent = leadingSpaces(line);
            symbols.add(new Symbol(pyClass.group(1), "class", lineNumber, Math.max(1, indent / 4 + 1)));
            return true;
        }

        Matcher classLike = CLASS_LIKE.matcher(line == null ? "" : line);
        if (classLike.find()) {
            symbols.add(new Symbol(classLike.group(2), "class", lineNumber, Math.max(1, braceDepth + 1)));
            return true;
        }

        Matcher pyDef = PY_DEF.matcher(line == null ? "" : line);
        if (pyDef.find()) {
            int indent = leadingSpaces(line);
            symbols.add(new Symbol(pyDef.group(1), "function", lineNumber, Math.max(1, indent / 4 + 1)));
            return true;
        }

        Matcher jsFunction = JS_FUNCTION.matcher(line == null ? "" : line);
        if (jsFunction.find()) {
            symbols.add(new Symbol(jsFunction.group(1), "function", lineNumber, Math.max(1, braceDepth + 1)));
            return true;
        }

        Matcher jsArrow = JS_ARROW.matcher(line == null ? "" : line);
        if (jsArrow.find()) {
            symbols.add(new Symbol(jsArrow.group(1), "function", lineNumber, Math.max(1, braceDepth + 1)));
            return true;
        }

        Matcher javaMethod = JAVA_METHOD.matcher(line == null ? "" : line);
        if (javaMethod.find()) {
            String method = javaMethod.group(1);
            if (method != null && !method.isBlank() && !isControlKeyword(method)) {
                symbols.add(new Symbol(method, "method", lineNumber, Math.max(2, braceDepth + 1)));
                return true;
            }
        }
        return false;
    }

    public List<Symbol> breadcrumbTrail(List<Symbol> symbols, int lineNumber) {
        if (symbols == null || symbols.isEmpty()) {
            return Collections.emptyList();
        }
        int targetLine = Math.max(1, lineNumber);
        Deque<Symbol> stack = new ArrayDeque<>();
        for (Symbol symbol : symbols) {
            if (symbol.getLine() > targetLine) {
                break;
            }
            while (stack.size() >= symbol.getLevel()) {
                stack.removeLast();
            }
            stack.addLast(symbol);
        }
        return new ArrayList<>(stack);
    }

    private boolean isControlKeyword(String value) {
        String lower = value.toLowerCase(Locale.ROOT);
        return "if".equals(lower)
            || "for".equals(lower)
            || "while".equals(lower)
            || "switch".equals(lower)
            || "catch".equals(lower)
            || "return".equals(lower)
            || "new".equals(lower);
    }

    private int netBraceDelta(String line) {
        if (line == null || line.isEmpty()) {
            return 0;
        }
        int delta = 0;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == '{') {
                delta++;
            } else if (c == '}') {
                delta--;
            }
        }
        return delta;
    }

    private int leadingSpaces(String line) {
        if (line == null || line.isEmpty()) {
            return 0;
        }
        int count = 0;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == ' ') {
                count++;
            } else if (c == '\t') {
                count += 4;
            } else {
                break;
            }
        }
        return count;
    }
}
