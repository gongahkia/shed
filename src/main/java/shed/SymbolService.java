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
    private static final Pattern GO_TYPE = Pattern.compile("^\\s*type\\s+([A-Za-z_][A-Za-z0-9_]*)\\b");
    private static final Pattern GO_FUNCTION = Pattern.compile("^\\s*func\\s*(?:\\([^)]*\\)\\s*)?([A-Za-z_][A-Za-z0-9_]*)\\s*\\(");
    private static final Pattern RUST_ITEM = Pattern.compile("^\\s*(?:pub(?:\\([^)]*\\))?\\s+)?(?:struct|enum|trait|mod)\\s+([A-Za-z_][A-Za-z0-9_]*)\\b");
    private static final Pattern RUST_FUNCTION = Pattern.compile("^\\s*(?:pub(?:\\([^)]*\\))?\\s+)?(?:async\\s+)?(?:unsafe\\s+)?fn\\s+([A-Za-z_][A-Za-z0-9_]*)\\s*\\(");
    private static final Pattern RUST_IMPL = Pattern.compile("^\\s*impl(?:<[^>{}]*>)?\\s+([A-Za-z_][A-Za-z0-9_]*)");
    private static final Pattern C_CPP_TYPE = Pattern.compile("^\\s*(?:class|struct|enum|namespace)\\s+([A-Za-z_][A-Za-z0-9_]*)\\b");
    private static final Pattern C_CPP_FUNCTION = Pattern.compile("^\\s*(?!if\\b|for\\b|while\\b|switch\\b|catch\\b)(?:[A-Za-z_][A-Za-z0-9_:<>]*\\s*[*&]?\\s+)+([A-Za-z_][A-Za-z0-9_]*)\\s*\\([^;{}]*\\)\\s*(?:const\\s*)?(?:\\{|$)");
    private static final Pattern YAML_KEY = Pattern.compile("^(\\s*)(?:-\\s+)?([A-Za-z0-9_.-]+):");
    private static final Pattern TOML_TABLE = Pattern.compile("^\\s*\\[\\[?\\s*([^\\]]+?)\\s*\\]\\]?\\s*(?:#.*)?$");
    private static final Pattern SQL_DEFINITION = Pattern.compile("^\\s*create\\s+(?:or\\s+replace\\s+)?(?:table|view|index|schema|function|procedure)\\s+(?:if\\s+not\\s+exists\\s+)?([A-Za-z_][A-Za-z0-9_.$]*)\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern SHELL_FUNCTION = Pattern.compile("^\\s*(?:function\\s+)?([A-Za-z_][A-Za-z0-9_]*)\\s*(?:\\(\\s*\\))?\\s*\\{");
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
        return collectCodeSymbols(lines, fileType);
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

    private List<Symbol> collectCodeSymbols(String[] lines, FileType fileType) {
        List<Symbol> symbols = new ArrayList<>();
        int braceDepth = 0;
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            String trimmed = line == null ? "" : line.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("//") || trimmed.startsWith("#")) {
                braceDepth += netBraceDelta(trimmed);
                continue;
            }

            if (tryAddSymbol(symbols, i + 1, line, braceDepth, fileType)) {
                braceDepth += netBraceDelta(trimmed);
                continue;
            }
            braceDepth += netBraceDelta(trimmed);
        }
        return symbols;
    }

    private boolean tryAddSymbol(List<Symbol> symbols, int lineNumber, String line, int braceDepth, FileType fileType) {
        String value = line == null ? "" : line;
        if (fileType == FileType.JAVA) {
            if (value.indexOf("class") >= 0 || value.indexOf("interface") >= 0 || value.indexOf("enum") >= 0
                || value.indexOf("record") >= 0) {
                Matcher classLike = CLASS_LIKE.matcher(value);
                if (classLike.find()) {
                    symbols.add(new Symbol(classLike.group(2), "class", lineNumber, Math.max(1, braceDepth + 1)));
                    return true;
                }
            }
            if (value.indexOf('(') >= 0 && value.indexOf(';') < 0) {
                Matcher javaMethod = JAVA_METHOD.matcher(value);
                if (javaMethod.find()) {
                    String method = javaMethod.group(1);
                    if (method != null && !method.isBlank() && !isControlKeyword(method)) {
                        symbols.add(new Symbol(method, "method", lineNumber, Math.max(2, braceDepth + 1)));
                        return true;
                    }
                }
            }
            return false;
        }
        if (fileType == FileType.PYTHON) {
            Matcher pyClass = PY_CLASS.matcher(value);
            if (pyClass.find()) {
                symbols.add(new Symbol(pyClass.group(1), "class", lineNumber, Math.max(1, leadingSpaces(value) / 4 + 1)));
                return true;
            }
            Matcher pyDef = PY_DEF.matcher(value);
            if (pyDef.find()) {
                symbols.add(new Symbol(pyDef.group(1), "function", lineNumber, Math.max(1, leadingSpaces(value) / 4 + 1)));
                return true;
            }
            return false;
        }
        if (fileType == FileType.JAVASCRIPT || fileType == FileType.TYPESCRIPT) {
            Matcher classLike = CLASS_LIKE.matcher(value);
            if (classLike.find()) {
                symbols.add(new Symbol(classLike.group(2), "class", lineNumber, Math.max(1, braceDepth + 1)));
                return true;
            }
            Matcher jsFunction = JS_FUNCTION.matcher(value);
            if (jsFunction.find()) {
                symbols.add(new Symbol(jsFunction.group(1), "function", lineNumber, Math.max(1, braceDepth + 1)));
                return true;
            }
            Matcher jsArrow = JS_ARROW.matcher(value);
            if (jsArrow.find()) {
                symbols.add(new Symbol(jsArrow.group(1), "function", lineNumber, Math.max(1, braceDepth + 1)));
                return true;
            }
            return false;
        }
        if (fileType == FileType.GO) {
            Matcher type = GO_TYPE.matcher(value);
            if (type.find()) {
                symbols.add(new Symbol(type.group(1), "type", lineNumber, Math.max(1, braceDepth + 1)));
                return true;
            }
            Matcher function = GO_FUNCTION.matcher(value);
            if (function.find()) {
                symbols.add(new Symbol(function.group(1), "function", lineNumber, Math.max(1, braceDepth + 1)));
                return true;
            }
            return false;
        }
        if (fileType == FileType.RUST) {
            Matcher item = RUST_ITEM.matcher(value);
            if (item.find()) {
                symbols.add(new Symbol(item.group(1), "item", lineNumber, Math.max(1, braceDepth + 1)));
                return true;
            }
            Matcher function = RUST_FUNCTION.matcher(value);
            if (function.find()) {
                symbols.add(new Symbol(function.group(1), "function", lineNumber, Math.max(1, braceDepth + 1)));
                return true;
            }
            Matcher implementation = RUST_IMPL.matcher(value);
            if (implementation.find()) {
                symbols.add(new Symbol("impl " + implementation.group(1), "implementation", lineNumber, Math.max(1, braceDepth + 1)));
                return true;
            }
            return false;
        }
        if (fileType == FileType.C || fileType == FileType.CPP) {
            Matcher type = C_CPP_TYPE.matcher(value);
            if (type.find()) {
                symbols.add(new Symbol(type.group(1), "type", lineNumber, Math.max(1, braceDepth + 1)));
                return true;
            }
            Matcher function = C_CPP_FUNCTION.matcher(value);
            if (function.find()) {
                symbols.add(new Symbol(function.group(1), "function", lineNumber, Math.max(1, braceDepth + 1)));
                return true;
            }
            return false;
        }
        if (fileType == FileType.YAML) {
            Matcher key = YAML_KEY.matcher(value);
            if (key.find()) {
                int level = Math.max(1, leadingSpaces(key.group(1)) / 2 + 1);
                symbols.add(new Symbol(key.group(2), "key", lineNumber, level));
                return true;
            }
            return false;
        }
        if (fileType == FileType.TOML) {
            Matcher table = TOML_TABLE.matcher(value);
            if (table.find()) {
                symbols.add(new Symbol(table.group(1), "table", lineNumber, 1));
                return true;
            }
            return false;
        }
        if (fileType == FileType.SQL) {
            Matcher definition = SQL_DEFINITION.matcher(value);
            if (definition.find()) {
                symbols.add(new Symbol(definition.group(1), "definition", lineNumber, 1));
                return true;
            }
            return false;
        }
        if (fileType == FileType.SHELL) {
            Matcher function = SHELL_FUNCTION.matcher(value);
            if (function.find() && !isControlKeyword(function.group(1))) {
                symbols.add(new Symbol(function.group(1), "function", lineNumber, 1));
                return true;
            }
            return false;
        }
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
