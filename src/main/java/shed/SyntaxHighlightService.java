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
            case KOTLIN:
                rules.add(new SyntaxRule("@\\w+", "annotation"));
                rules.add(new SyntaxRule("\\b[A-Z][a-zA-Z0-9]*\\b", "type"));
                rules.add(new SyntaxRule("\\bfun\\s+(\\w+)", "function"));
                rules.add(new SyntaxRule("\\b[A-Z_]{2,}\\b", "constant"));
                rules.add(new SyntaxRule("\\b\\d+[\\d_.]*[lLfF]?\\b", "number"));
                break;
            case CSHARP:
                rules.add(new SyntaxRule("\\[[A-Za-z_][A-Za-z0-9_.]*", "annotation"));
                rules.add(new SyntaxRule("\\b[A-Z][a-zA-Z0-9_]*\\b", "type"));
                rules.add(new SyntaxRule("\\b[a-z_][a-zA-Z0-9_]*\\s*(?=\\()", "function"));
                rules.add(new SyntaxRule("\\b[A-Z_]{2,}\\b", "constant"));
                rules.add(new SyntaxRule("\\b\\d+[\\d_.]*[mMdDfFlL]?\\b", "number"));
                break;
            case PHP:
                rules.add(new SyntaxRule("\\b[A-Z][a-zA-Z0-9_]*\\b", "type"));
                rules.add(new SyntaxRule("\\bfunction\\s+(\\w+)", "function"));
                rules.add(new SyntaxRule("\\b[A-Z_]{2,}\\b", "constant"));
                rules.add(new SyntaxRule("\\b\\d+[\\d_.]*\\b", "number"));
                break;
            case RUBY:
                rules.add(new SyntaxRule("\\b[A-Z][a-zA-Z0-9_]*\\b", "type"));
                rules.add(new SyntaxRule("\\bdef\\s+([a-zA-Z_][a-zA-Z0-9_!?=]*)", "function"));
                rules.add(new SyntaxRule("\\b[A-Z_]{2,}\\b", "constant"));
                rules.add(new SyntaxRule("\\b\\d+[\\d_.]*\\b", "number"));
                break;
            case SWIFT:
                rules.add(new SyntaxRule("@\\w+", "annotation"));
                rules.add(new SyntaxRule("\\b[A-Z][a-zA-Z0-9_]*\\b", "type"));
                rules.add(new SyntaxRule("\\bfunc\\s+(\\w+)", "function"));
                rules.add(new SyntaxRule("\\b[A-Z_]{2,}\\b", "constant"));
                rules.add(new SyntaxRule("\\b\\d+[\\d_.]*\\b", "number"));
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
            case KOTLIN:
                return new String[] {"as", "break", "by", "catch", "class", "companion", "constructor", "continue", "data", "delegate", "do", "dynamic", "else", "enum", "expect", "external", "false", "field", "file", "finally", "for", "fun", "get", "if", "import", "in", "infix", "init", "inline", "inner", "interface", "internal", "is", "lateinit", "noinline", "null", "object", "open", "operator", "out", "override", "package", "private", "protected", "public", "reified", "return", "sealed", "set", "super", "suspend", "tailrec", "this", "throw", "true", "try", "typealias", "val", "var", "when", "where", "while"};
            case CSHARP:
                return new String[] {"abstract", "as", "async", "await", "base", "bool", "break", "byte", "case", "catch", "char", "checked", "class", "const", "continue", "decimal", "default", "delegate", "do", "double", "else", "enum", "event", "explicit", "extern", "false", "finally", "fixed", "float", "for", "foreach", "goto", "if", "implicit", "in", "int", "interface", "internal", "is", "lock", "long", "namespace", "new", "null", "object", "operator", "out", "override", "params", "private", "protected", "public", "readonly", "record", "ref", "return", "sbyte", "sealed", "short", "sizeof", "stackalloc", "static", "string", "struct", "switch", "this", "throw", "true", "try", "typeof", "uint", "ulong", "unchecked", "unsafe", "ushort", "using", "virtual", "void", "volatile", "while", "yield"};
            case PHP:
                return new String[] {"abstract", "and", "array", "as", "break", "callable", "case", "catch", "class", "clone", "const", "continue", "declare", "default", "do", "echo", "else", "elseif", "empty", "enum", "eval", "exit", "extends", "false", "final", "finally", "fn", "for", "foreach", "function", "global", "if", "implements", "include", "instanceof", "interface", "isset", "list", "match", "namespace", "new", "null", "or", "private", "protected", "public", "readonly", "require", "return", "static", "switch", "throw", "trait", "true", "try", "use", "var", "while", "xor", "yield"};
            case RUBY:
                return new String[] {"BEGIN", "END", "alias", "and", "begin", "break", "case", "class", "def", "defined?", "do", "else", "elsif", "end", "ensure", "false", "for", "if", "in", "module", "next", "nil", "not", "or", "redo", "rescue", "retry", "return", "self", "super", "then", "true", "undef", "unless", "until", "when", "while", "yield"};
            case SWIFT:
                return new String[] {"actor", "any", "as", "associatedtype", "async", "await", "break", "case", "catch", "class", "continue", "convenience", "default", "defer", "deinit", "didSet", "do", "dynamic", "each", "else", "enum", "extension", "fallthrough", "false", "fileprivate", "final", "for", "func", "get", "guard", "if", "import", "in", "indirect", "infix", "init", "inout", "internal", "is", "isolated", "lazy", "let", "mutating", "nil", "nonisolated", "open", "operator", "optional", "override", "package", "postfix", "precedencegroup", "prefix", "private", "protocol", "public", "repeat", "required", "rethrows", "return", "self", "set", "some", "static", "struct", "subscript", "super", "switch", "throws", "true", "try", "typealias", "unowned", "var", "weak", "where", "while", "willSet"};
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
            case YAML:
                return new String[] {"true", "false", "null", "yes", "no", "on", "off"};
            case TOML:
                return new String[] {"true", "false"};
            case CMAKE:
                return new String[] {"if", "elseif", "else", "endif", "foreach", "endforeach", "while", "endwhile", "function",
                    "endfunction", "macro", "endmacro", "block", "endblock", "return", "break", "continue", "cmake_minimum_required",
                    "project", "add_executable", "add_library", "add_subdirectory", "target_link_libraries", "target_include_directories",
                    "target_compile_definitions", "target_compile_features", "find_package", "include", "set", "unset", "option", "message",
                    "configure_file", "file", "install", "enable_testing", "add_test", "include_directories", "link_directories"};
            case SQL:
                return new String[] {"select", "from", "where", "join", "left", "right", "inner", "outer", "on", "as", "insert", "into", "values", "update", "set", "delete", "create", "alter", "drop", "table", "index", "view", "with", "group", "by", "order", "having", "limit", "offset", "union", "all", "distinct", "null", "and", "or", "not", "case", "when", "then", "else", "end"};
            case SHELL:
                return new String[] {"if", "then", "elif", "else", "fi", "for", "in", "do", "done", "while", "case", "esac", "function", "return", "export", "local", "readonly", "set", "unset", "true", "false"};
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
            case KOTLIN:
            case CSHARP:
            case SWIFT:
            case JAVASCRIPT:
            case TYPESCRIPT:
            case C:
            case CPP:
            case GO:
            case RUST:
                return new String[] {"//"};
            case PHP:
                return new String[] {"//", "#"};
            case PYTHON:
            case RUBY:
                return new String[] {"#"};
            case YAML:
            case TOML:
            case CMAKE:
            case SHELL:
                return new String[] {"#"};
            case SQL:
                return new String[] {"--"};
            default:
                return new String[0];
        }
    }

    public String[][] blockCommentPairsFor(FileType fileType) {
        switch (fileType) {
            case JAVA:
            case KOTLIN:
            case CSHARP:
            case PHP:
            case SWIFT:
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
            case SQL:
                return new String[][] {{"/*", "*/"}};
            default:
                return new String[0][0];
        }
    }
}
