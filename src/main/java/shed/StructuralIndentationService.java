package shed;

import java.util.List;
import java.util.Set;

/** Bounded lexical indentation hints; this deliberately is not a parser or formatter. */
final class StructuralIndentationService {
    private static final int MAXIMUM_SOURCE_LENGTH = 1024 * 1024;
    private static final Set<FileType> BRACE_LANGUAGES = Set.of(
        FileType.JAVA, FileType.KOTLIN, FileType.CSHARP, FileType.PHP, FileType.SWIFT, FileType.JAVASCRIPT,
        FileType.TYPESCRIPT, FileType.GO, FileType.RUST, FileType.C, FileType.CPP, FileType.CSS, FileType.JSON, FileType.SQL,
        FileType.SHELL
    );

    String indentationForNewLine(String source, int caret, FileType fileType, boolean expandTabs, int tabSize,
        GrammarHighlightService grammar) {
        String text = source == null ? "" : source;
        int position = Math.max(0, Math.min(caret, text.length()));
        int lineStart = text.lastIndexOf('\n', Math.max(0, position - 1)) + 1;
        String line = text.substring(lineStart, position);
        String base = leadingWhitespace(line);
        FileType type = fileType == null ? FileType.UNKNOWN : fileType;
        if (position == 0 || text.length() > MAXIMUM_SOURCE_LENGTH || type == FileType.MARKDOWN || type == FileType.TEXT
            || type == FileType.UNKNOWN) return base;
        int marker = lastNonWhitespace(text, lineStart, position);
        if (marker < lineStart || !codePosition(text, marker, type, grammar)) return base;
        String content = line.stripLeading().stripTrailing();
        if (BRACE_LANGUAGES.contains(type) && opensDelimitedBlock(text.charAt(marker))) return base + indentationUnit(expandTabs, tabSize);
        if (type == FileType.PYTHON && pythonBlockHeader(content)) return base + indentationUnit(expandTabs, tabSize);
        if (type == FileType.RUBY && rubyBlockHeader(content)) return base + indentationUnit(expandTabs, tabSize);
        if (type == FileType.YAML && yamlMappingHeader(content)) return base + indentationUnit(expandTabs, tabSize);
        if (type == FileType.CMAKE && cmakeBlockHeader(content)) return base + indentationUnit(expandTabs, tabSize);
        return base;
    }

    private static String leadingWhitespace(String line) {
        int index = 0;
        while (index < line.length() && (line.charAt(index) == ' ' || line.charAt(index) == '\t')) index++;
        return line.substring(0, index);
    }

    private static int lastNonWhitespace(String source, int start, int end) {
        for (int index = end - 1; index >= start; index--) if (!Character.isWhitespace(source.charAt(index))) return index;
        return -1;
    }

    private static boolean codePosition(String source, int offset, FileType type, GrammarHighlightService grammar) {
        if (grammar == null) return true;
        List<GrammarHighlightService.Token> tokens = grammar.highlightViewport(source, type, offset, offset + 1);
        for (GrammarHighlightService.Token token : tokens) {
            if (offset < token.start() || offset >= token.end()) continue;
            if (token.scope() == GrammarHighlightService.Scope.COMMENT || token.scope() == GrammarHighlightService.Scope.STRING) return false;
        }
        return true;
    }

    private static String indentationUnit(boolean expandTabs, int tabSize) {
        return expandTabs ? " ".repeat(Math.max(1, Math.min(16, tabSize))) : "\t";
    }

    private static boolean opensDelimitedBlock(char marker) {
        return marker == '{' || marker == '[' || marker == '(';
    }

    private static boolean pythonBlockHeader(String line) {
        return line.matches("(?i)(?:async\\s+)?(?:def|class|if|elif|else|for|while|try|except|finally|with|match|case)\\b.*:");
    }

    private static boolean rubyBlockHeader(String line) {
        return line.matches("(?i)(?:class|module|def|if|unless|case|while|until|for|begin)\\b.*")
            || line.matches("(?i).*\\bdo(?:\\s*\\|[^|]*\\|)?\\s*");
    }

    private static boolean yamlMappingHeader(String line) {
        return line.matches("(?:-\\s+)?[A-Za-z0-9_.-]+\\s*:");
    }

    private static boolean cmakeBlockHeader(String line) {
        return line.matches("(?i)(?:if|elseif|else|foreach|while|function|macro)\\s*\\(.*\\)");
    }
}
