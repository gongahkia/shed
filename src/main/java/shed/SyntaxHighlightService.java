package shed;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

public class SyntaxHighlightService {

    public static class SyntaxRule {
        public final Pattern pattern;
        public final String scope; // "type", "function", "annotation", "constant", "number"
        public SyntaxRule(String regex, String scope) {
            this.pattern = Pattern.compile(regex);
            this.scope = scope;
        }
    }

    public List<SyntaxRule> scopeRulesFor(FileType fileType) {
        List<SyntaxRule> rules = new ArrayList<>();
        switch (fileType) {
            case JAVA:
                rules.add(new SyntaxRule("@\\w+", "annotation"));
                rules.add(new SyntaxRule("\\b[A-Z][a-zA-Z0-9]*\\b", "type"));
                rules.add(new SyntaxRule("\\b[a-z_][a-zA-Z0-9_]*\\s*(?=\\()", "function"));
                rules.add(new SyntaxRule("\\b[A-Z_]{2,}\\b", "constant"));
                rules.add(new SyntaxRule("\\b\\d+[\\d_.]*[lLfFdD]?\\b", "number"));
                break;
            case PYTHON:
                rules.add(new SyntaxRule("@\\w+", "annotation"));
                rules.add(new SyntaxRule("\\b[A-Z][a-zA-Z0-9]*\\b", "type"));
                rules.add(new SyntaxRule("\\bdef\\s+(\\w+)", "function"));
                rules.add(new SyntaxRule("\\b[A-Z_]{2,}\\b", "constant"));
                rules.add(new SyntaxRule("\\b\\d+[\\d_.]*\\b", "number"));
                break;
            case JAVASCRIPT:
            case TYPESCRIPT:
                rules.add(new SyntaxRule("@\\w+", "annotation"));
                rules.add(new SyntaxRule("\\b[A-Z][a-zA-Z0-9]*\\b", "type"));
                rules.add(new SyntaxRule("\\b[a-z_$][a-zA-Z0-9_$]*\\s*(?=\\()", "function"));
                rules.add(new SyntaxRule("\\b[A-Z_]{2,}\\b", "constant"));
                rules.add(new SyntaxRule("\\b\\d+[\\d_.]*\\b", "number"));
                break;
            case RUST:
                rules.add(new SyntaxRule("#\\[\\w+[^]]*\\]", "annotation"));
                rules.add(new SyntaxRule("\\b[A-Z][a-zA-Z0-9]*\\b", "type"));
                rules.add(new SyntaxRule("\\bfn\\s+(\\w+)", "function"));
                rules.add(new SyntaxRule("\\b[A-Z_]{2,}\\b", "constant"));
                rules.add(new SyntaxRule("\\b\\d+[\\d_.]*[uif]?\\d*\\b", "number"));
                break;
            case GO:
                rules.add(new SyntaxRule("\\b[A-Z][a-zA-Z0-9]*\\b", "type"));
                rules.add(new SyntaxRule("\\bfunc\\s+(\\w+)", "function"));
                rules.add(new SyntaxRule("\\b[A-Z_]{2,}\\b", "constant"));
                rules.add(new SyntaxRule("\\b\\d+[\\d_.]*\\b", "number"));
                break;
            case C:
            case CPP:
                rules.add(new SyntaxRule("\\b[A-Z][a-zA-Z0-9_]*\\b", "type"));
                rules.add(new SyntaxRule("\\b[a-z_][a-zA-Z0-9_]*\\s*(?=\\()", "function"));
                rules.add(new SyntaxRule("\\b[A-Z_]{2,}\\b", "constant"));
                rules.add(new SyntaxRule("\\b\\d+[\\d_.xXbBeEpP]*[uUlLfF]*\\b", "number"));
                break;
            default:
                rules.add(new SyntaxRule("\\b\\d+[\\d_.]*\\b", "number"));
                break;
        }
        return rules;
    }

    public String[] keywordsFor(FileType fileType) {
        switch (fileType) {
            case JAVA:
                return new String[] {"abstract", "assert", "boolean", "break", "byte", "case", "catch", "char", "class", "const", "continue", "default", "do", "double", "else", "enum", "exports", "extends", "final", "finally", "float", "for", "if", "implements", "import", "instanceof", "int", "interface", "long", "module", "native", "new", "non-sealed", "null", "open", "opens", "package", "permits", "private", "protected", "provides", "public", "record", "requires", "return", "sealed", "short", "static", "strictfp", "super", "switch", "synchronized", "this", "throw", "throws", "to", "transient", "transitive", "true", "try", "uses", "var", "void", "volatile", "when", "while", "with", "yield", "false"};
            case JAVASCRIPT:
            case TYPESCRIPT:
                return new String[] {"as", "async", "await", "break", "case", "catch", "class", "const", "continue", "debugger", "default", "delete", "do", "else", "enum", "export", "extends", "false", "finally", "for", "from", "function", "if", "implements", "import", "in", "instanceof", "interface", "let", "new", "null", "private", "protected", "public", "readonly", "return", "static", "super", "switch", "this", "throw", "true", "try", "type", "typeof", "undefined", "var", "void", "while", "yield"};
            case PYTHON:
                return new String[] {"and", "as", "assert", "async", "await", "break", "class", "continue", "def", "del", "elif", "else", "except", "False", "finally", "for", "from", "global", "if", "import", "in", "is", "lambda", "None", "nonlocal", "not", "or", "pass", "raise", "return", "True", "try", "while", "with", "yield"};
            case RUST:
                return new String[] {"as", "async", "await", "break", "const", "continue", "crate", "dyn", "else", "enum", "extern", "false", "fn", "for", "if", "impl", "in", "let", "loop", "match", "mod", "move", "mut", "pub", "ref", "return", "Self", "self", "static", "struct", "super", "trait", "true", "type", "unsafe", "use", "where", "while"};
            case GO:
                return new String[] {"break", "case", "chan", "const", "continue", "default", "defer", "else", "fallthrough", "for", "func", "go", "goto", "if", "import", "interface", "map", "package", "range", "return", "select", "struct", "switch", "type", "var"};
            case C:
            case CPP:
                return new String[] {"alignas", "alignof", "asm", "auto", "bool", "break", "case", "catch", "char", "class", "const", "constexpr", "continue", "default", "delete", "do", "double", "else", "enum", "extern", "false", "float", "for", "goto", "if", "inline", "int", "long", "mutable", "namespace", "new", "nullptr", "operator", "private", "protected", "public", "register", "return", "short", "signed", "sizeof", "static", "struct", "switch", "template", "this", "throw", "true", "try", "typedef", "typename", "union", "unsigned", "using", "virtual", "void", "volatile", "while", "#include", "#define"};
            case HTML:
                return new String[] {"<!DOCTYPE", "<html", "<head", "<body", "<main", "<section", "<article", "<aside", "<nav", "<header", "<footer", "<div", "<span", "<p", "<a", "<img", "<button", "<input", "<label", "<form", "<ul", "<ol", "<li", "<table", "<tr", "<td", "<th", "<script", "<style", "class", "id", "href", "src"};
            case CSS:
                return new String[] {"display", "position", "color", "background", "background-color", "padding", "margin", "width", "height", "max-width", "min-width", "font-family", "font-size", "font-weight", "line-height", "text-align", "border", "border-radius", "box-shadow", "opacity", "flex", "flex-direction", "justify-content", "align-items", "grid", "grid-template-columns", "gap", "overflow", "z-index", "absolute", "relative", "fixed", "sticky"};
            case JSON:
                return new String[] {"true", "false", "null"};
            case MARKDOWN:
                return new String[] {"# ", "## ", "### ", "#### ", "##### ", "###### ", "- ", "* ", "> ", "```"};
            default:
                return new String[0];
        }
    }

    public boolean isStringDelimiter(FileType fileType, char c) {
        if (c == '"' || c == '\'') return true;
        return (fileType == FileType.JAVASCRIPT || fileType == FileType.TYPESCRIPT || fileType == FileType.MARKDOWN) && c == '`';
    }

    public String[] lineCommentPrefixesFor(FileType fileType) {
        switch (fileType) {
            case JAVA:
            case JAVASCRIPT:
            case TYPESCRIPT:
            case C:
            case CPP:
            case GO:
            case RUST:
                return new String[] {"//"};
            case PYTHON:
                return new String[] {"#"};
            default:
                return new String[0];
        }
    }

    public String[][] blockCommentPairsFor(FileType fileType) {
        switch (fileType) {
            case JAVA:
            case JAVASCRIPT:
            case TYPESCRIPT:
            case C:
            case CPP:
            case GO:
            case RUST:
            case CSS:
                return new String[][] {{"/*", "*/"}};
            case HTML:
            case MARKDOWN:
                return new String[][] {{"<!--", "-->"}};
            default:
                return new String[0][0];
        }
    }
}
