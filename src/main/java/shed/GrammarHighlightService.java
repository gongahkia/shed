package shed;

import shed.api.LanguageProfile;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Incremental, stateful lexical grammars for Shed's built-in file types. */
final class GrammarHighlightService {
    enum Scope { KEYWORD, STRING, COMMENT, NUMBER, TYPE, FUNCTION, CONSTANT, ANNOTATION }
    record Token(int start, int end, Scope scope) {}

    private final Map<FileBuffer, Cache> caches = new IdentityHashMap<>();
    private final Map<FileType, Set<String>> keywords = new EnumMap<>(FileType.class);

    GrammarHighlightService() {
        SyntaxHighlightService source = new SyntaxHighlightService();
        for (FileType type : FileType.values()) {
            keywords.put(type, new HashSet<>(Arrays.asList(source.keywordsFor(type))));
        }
    }

    List<Token> highlight(FileBuffer buffer, String text, FileType fileType) {
        if (buffer == null || text == null || text.isEmpty()) return List.of();
        Cache cache = caches.get(buffer);
        if (cache == null || cache.fileType != fileType) {
            cache = new Cache(fileType);
            caches.put(buffer, cache);
        }
        return cache.update(text);
    }

    /** Lexes an immutable background snapshot without sharing an editor cache across threads. */
    List<Token> highlightSnapshot(String text, FileType fileType) {
        if (text == null || text.isEmpty()) return List.of();
        FileType type = fileType == null ? FileType.TEXT : fileType;
        List<Token> tokens = new ArrayList<>();
        if (type != FileType.HTML && type != FileType.MARKDOWN) {
            State state = State.normal();
            int lineStart = 0;
            while (lineStart <= text.length()) {
                int lineEnd = text.indexOf('\n', lineStart);
                if (lineEnd < 0) lineEnd = text.length();
                state = code(text, lineStart, lineEnd, type, state.mode, tokens).withEmbedded(state.embedded, state.marker);
                if (lineEnd == text.length()) break;
                lineStart = lineEnd + 1;
            }
            return List.copyOf(tokens);
        }
        List<Token> lineTokens = new ArrayList<>(8);
        State state = State.normal();
        int lineStart = 0;
        while (lineStart <= text.length()) {
            int lineEnd = text.indexOf('\n', lineStart);
            if (lineEnd < 0) lineEnd = text.length();
            lineTokens.clear();
            state = tokenizeLine(text.substring(lineStart, lineEnd), type, state, lineTokens);
            for (Token token : lineTokens) tokens.add(new Token(lineStart + token.start(), lineStart + token.end(), token.scope()));
            if (lineEnd == text.length()) break;
            lineStart = lineEnd + 1;
        }
        return List.copyOf(tokens);
    }

    /**
     * Lexes a visible code range after cheaply advancing lexical state through
     * the preceding lines. This bounds token allocation for very large editable
     * files while retaining correct multiline comment and string state.
     */
    List<Token> highlightViewport(String text, FileType fileType, int visibleStart, int visibleEnd) {
        if (text == null || text.isEmpty()) return List.of();
        FileType type = fileType == null ? FileType.TEXT : fileType;
        if (type == FileType.HTML || type == FileType.MARKDOWN) return highlightSnapshot(text, type);
        int start = Math.max(0, Math.min(visibleStart, text.length()));
        int end = Math.max(start, Math.min(visibleEnd, text.length()));
        int firstLine = text.lastIndexOf("\n", Math.max(0, start - 1)) + 1;
        int lastLine = text.indexOf('\n', end);
        if (lastLine < 0) lastLine = text.length();
        State state = State.normal();
        int lineStart = 0;
        while (lineStart < firstLine) {
            int lineEnd = text.indexOf('\n', lineStart);
            if (lineEnd < 0) lineEnd = text.length();
            state = code(text, lineStart, lineEnd, type, state.mode, null).withEmbedded(state.embedded, state.marker);
            lineStart = lineEnd + 1;
        }
        List<Token> tokens = new ArrayList<>();
        while (lineStart <= lastLine) {
            int lineEnd = text.indexOf('\n', lineStart);
            if (lineEnd < 0 || lineEnd > lastLine) lineEnd = lastLine;
            state = code(text, lineStart, lineEnd, type, state.mode, tokens).withEmbedded(state.embedded, state.marker);
            if (lineEnd == lastLine) break;
            lineStart = lineEnd + 1;
        }
        return List.copyOf(tokens);
    }

    /**
     * Lexes an extension-provided profile. Profiles use literal delimiters only,
     * so extension metadata cannot inject regular-expression work into typing.
     */
    List<Token> highlightSnapshot(String text, LanguageProfile profile) {
        if (text == null || text.isEmpty() || profile == null) return List.of();
        return highlightProfileRange(text, profile, 0, text.length());
    }

    /** Lexes only a visible range after advancing profile state through prior lines. */
    List<Token> highlightViewport(String text, LanguageProfile profile, int visibleStart, int visibleEnd) {
        if (text == null || text.isEmpty() || profile == null) return List.of();
        int start = Math.max(0, Math.min(visibleStart, text.length()));
        int end = Math.max(start, Math.min(visibleEnd, text.length()));
        int firstLine = text.lastIndexOf("\n", Math.max(0, start - 1)) + 1;
        int lastLine = text.indexOf('\n', end);
        return highlightProfileRange(text, profile, firstLine, lastLine < 0 ? text.length() : lastLine);
    }

    private List<Token> highlightProfileRange(String text, LanguageProfile profile, int firstLine, int lastLine) {
        ProfileState state = ProfileState.normal();
        int lineStart = 0;
        while (lineStart < firstLine) {
            int lineEnd = text.indexOf('\n', lineStart);
            if (lineEnd < 0) lineEnd = text.length();
            state = profileLine(text, lineStart, lineEnd, profile, state, null);
            lineStart = lineEnd + 1;
        }
        List<Token> tokens = new ArrayList<>();
        while (lineStart <= lastLine) {
            int lineEnd = text.indexOf('\n', lineStart);
            if (lineEnd < 0 || lineEnd > lastLine) lineEnd = lastLine;
            state = profileLine(text, lineStart, lineEnd, profile, state, tokens);
            if (lineEnd == lastLine) break;
            lineStart = lineEnd + 1;
        }
        return List.copyOf(tokens);
    }

    private ProfileState profileLine(String text, int from, int to, LanguageProfile profile, ProfileState state, List<Token> tokens) {
        int index = from;
        if (!state.close().isEmpty()) {
            int end = literalEnd(text, index, to, state.close());
            add(tokens, index, end, state.scope());
            if (end < to || endsWith(text, end, state.close())) {
                index = end;
                state = ProfileState.normal();
            } else {
                return state;
            }
        }
        while (index < to) {
            String lineComment = matching(text, index, to, profile.lineCommentPrefixes());
            if (lineComment != null) {
                add(tokens, index, to, Scope.COMMENT);
                return ProfileState.normal();
            }
            LanguageProfile.BlockComment block = matchingBlockComment(text, index, to, profile.blockComments());
            if (block != null) {
                int end = literalEnd(text, index + block.start().length(), to, block.end());
                add(tokens, index, end, Scope.COMMENT);
                if (end >= to && !endsWith(text, end, block.end())) return new ProfileState(block.end(), Scope.COMMENT);
                index = end;
                continue;
            }
            LanguageProfile.StringDelimiter delimiter = matchingDelimiter(text, index, to, profile.stringDelimiters());
            if (delimiter != null) {
                int end = escapedLiteralEnd(text, index + delimiter.value().length(), to, delimiter.value());
                add(tokens, index, end, Scope.STRING);
                if (delimiter.multiline() && end >= to && !endsWith(text, end, delimiter.value())) {
                    return new ProfileState(delimiter.value(), Scope.STRING);
                }
                index = end;
                continue;
            }
            char current = text.charAt(index);
            if (current == '@') {
                int end = identifierEnd(text, index + 1, to);
                add(tokens, index, end, Scope.ANNOTATION);
                index = Math.max(index + 1, end);
                continue;
            }
            if (Character.isDigit(current) && (index == from || !profileIdentifier(text.charAt(index - 1)))) {
                int end = numberEnd(text, index, to);
                add(tokens, index, end, Scope.NUMBER);
                index = end;
                continue;
            }
            if (profileIdentifierStart(current)) {
                int start = index++;
                while (index < to && profileIdentifier(text.charAt(index))) index++;
                Scope scope = profileWordScope(text, start, index, to, profile);
                if (scope != null) add(tokens, start, index, scope);
                continue;
            }
            index++;
        }
        return ProfileState.normal();
    }

    private Scope profileWordScope(String text, int start, int end, int limit, LanguageProfile profile) {
        if (profile.keywords().contains(text.substring(start, end))) return Scope.KEYWORD;
        if (end - start > 1 && isUpperConstant(text, start, end)) return Scope.CONSTANT;
        int next = end;
        while (next < limit && Character.isWhitespace(text.charAt(next))) next++;
        if (next < limit && text.charAt(next) == '(') return Scope.FUNCTION;
        if (Character.isUpperCase(text.charAt(start))) return Scope.TYPE;
        return null;
    }

    private static String matching(String text, int offset, int limit, List<String> candidates) {
        for (String value : candidates) {
            if (offset + value.length() <= limit && startsWith(text, offset, value)) return value;
        }
        return null;
    }

    private static LanguageProfile.BlockComment matchingBlockComment(String text, int offset, int limit,
                                                                       List<LanguageProfile.BlockComment> candidates) {
        for (LanguageProfile.BlockComment value : candidates) {
            if (offset + value.start().length() <= limit && startsWith(text, offset, value.start())) return value;
        }
        return null;
    }

    private static LanguageProfile.StringDelimiter matchingDelimiter(String text, int offset, int limit,
                                                                       List<LanguageProfile.StringDelimiter> candidates) {
        for (LanguageProfile.StringDelimiter value : candidates) {
            if (offset + value.value().length() <= limit && startsWith(text, offset, value.value())) return value;
        }
        return null;
    }

    private static int literalEnd(String text, int from, int limit, String delimiter) {
        int close = text.indexOf(delimiter, from);
        return close < 0 || close + delimiter.length() > limit ? limit : close + delimiter.length();
    }

    private static int escapedLiteralEnd(String text, int from, int limit, String delimiter) {
        boolean escaped = false;
        for (int index = from; index < limit; index++) {
            if (!escaped && startsWith(text, index, delimiter)) return index + delimiter.length();
            char current = text.charAt(index);
            escaped = current == '\\' && !escaped;
            if (current != '\\') escaped = false;
        }
        return limit;
    }

    private static boolean endsWith(String text, int end, String value) {
        return end >= value.length() && text.regionMatches(end - value.length(), value, 0, value.length());
    }

    private static boolean profileIdentifierStart(char value) {
        return Character.isLetter(value) || value == '_' || value == '$';
    }

    private static boolean profileIdentifier(char value) {
        return Character.isLetterOrDigit(value) || value == '_' || value == '$';
    }

    void invalidate(FileBuffer buffer) {
        if (buffer != null) caches.remove(buffer);
    }

    private final class Cache {
        private final FileType fileType;
        private List<Line> lines = List.of();

        private Cache(FileType fileType) {
            this.fileType = fileType;
        }

        private List<Token> update(String source) {
            List<String> next = Arrays.asList(source.split("\\n", -1));
            int prefix = 0;
            while (prefix < lines.size() && prefix < next.size() && lines.get(prefix).text.equals(next.get(prefix))) prefix++;
            int suffix = 0;
            while (suffix < lines.size() - prefix && suffix < next.size() - prefix
                && lines.get(lines.size() - 1 - suffix).text.equals(next.get(next.size() - 1 - suffix))) suffix++;
            List<Line> updated = new ArrayList<>(next.size());
            for (int index = 0; index < prefix; index++) updated.add(lines.get(index));
            State state = prefix == 0 ? State.normal() : updated.get(prefix - 1).end;
            int oldDelta = lines.size() - next.size();
            for (int index = prefix; index < next.size(); index++) {
                int oldIndex = index + oldDelta;
                if (index >= next.size() - suffix && oldIndex >= lines.size() - suffix && oldIndex >= 0
                    && state.equals(lines.get(oldIndex).start)) {
                    for (int tail = oldIndex; tail < lines.size(); tail++) updated.add(lines.get(tail));
                    break;
                }
                List<Token> tokens = new ArrayList<>();
                State end = tokenizeLine(next.get(index), fileType, state, tokens);
                updated.add(new Line(next.get(index), state, end, List.copyOf(tokens)));
                state = end;
            }
            lines = List.copyOf(updated);
            return flatten(lines);
        }
    }

    private List<Token> flatten(List<Line> lines) {
        List<Token> tokens = new ArrayList<>();
        int offset = 0;
        for (Line line : lines) {
            for (Token token : line.tokens) tokens.add(new Token(offset + token.start, offset + token.end, token.scope));
            offset += line.text.length() + 1;
        }
        return List.copyOf(tokens);
    }

    private State tokenizeLine(String text, FileType type, State state, List<Token> tokens) {
        return switch (type) {
            case HTML -> html(text, state, tokens);
            case MARKDOWN -> markdown(text, state, tokens);
            default -> code(text, 0, text.length(), type, state.mode, tokens).withEmbedded(state.embedded, state.marker);
        };
    }

    private State markdown(String text, State state, List<Token> tokens) {
        if (state.mode == Mode.FENCE) {
            String trimmed = text.stripLeading();
            if (trimmed.startsWith(state.marker)) {
                add(tokens, text.indexOf(state.marker), text.length(), Scope.KEYWORD);
                return State.normal();
            }
            State inner = code(text, 0, text.length(), state.embedded, state.innerMode, tokens);
            return new State(Mode.FENCE, state.embedded, inner.mode, state.marker);
        }
        String trimmed = text.stripLeading();
        int lead = text.length() - trimmed.length();
        if (trimmed.startsWith("```") || trimmed.startsWith("~~~")) {
            String marker = trimmed.substring(0, 3);
            add(tokens, lead, text.length(), Scope.KEYWORD);
            String language = trimmed.substring(3).trim().split("\\s+", 2)[0];
            return new State(Mode.FENCE, fenceLanguage(language), Mode.NORMAL, marker);
        }
        if (trimmed.matches("#{1,6}\\s+.*")) add(tokens, lead, text.length(), Scope.KEYWORD);
        else if (trimmed.startsWith(">")) add(tokens, lead, Math.min(text.length(), lead + 1), Scope.KEYWORD);
        else if (trimmed.matches("[-*+]\\s+.*") || trimmed.matches("\\d+[.)]\\s+.*")) add(tokens, lead, Math.min(text.length(), lead + markerLength(trimmed)), Scope.KEYWORD);
        inlineMarkdownCode(text, tokens);
        return State.normal();
    }

    private int markerLength(String text) {
        int index = 0;
        while (index < text.length() && !Character.isWhitespace(text.charAt(index))) index++;
        return index;
    }

    private FileType fenceLanguage(String name) {
        String normalized = name == null ? "" : name.toLowerCase(Locale.ROOT);
        return switch (normalized) {
            case "java" -> FileType.JAVA;
            case "py", "python" -> FileType.PYTHON;
            case "js", "javascript", "jsx" -> FileType.JAVASCRIPT;
            case "ts", "typescript", "tsx" -> FileType.TYPESCRIPT;
            case "rs", "rust" -> FileType.RUST;
            case "go" -> FileType.GO;
            case "c" -> FileType.C;
            case "cpp", "c++", "cxx" -> FileType.CPP;
            case "kt", "kotlin", "kts" -> FileType.KOTLIN;
            case "cs", "csharp", "c#" -> FileType.CSHARP;
            case "php" -> FileType.PHP;
            case "rb", "ruby" -> FileType.RUBY;
            case "swift" -> FileType.SWIFT;
            case "html", "xml" -> FileType.HTML;
            case "css" -> FileType.CSS;
            case "json" -> FileType.JSON;
            case "yaml", "yml" -> FileType.YAML;
            case "toml" -> FileType.TOML;
            case "sql" -> FileType.SQL;
            case "sh", "shell", "bash", "zsh", "fish", "powershell", "pwsh", "ps1" -> FileType.SHELL;
            default -> FileType.TEXT;
        };
    }

    private void inlineMarkdownCode(String text, List<Token> tokens) {
        int start = -1;
        for (int index = 0; index < text.length(); index++) {
            if (text.charAt(index) != '`' || (index > 0 && text.charAt(index - 1) == '\\')) continue;
            if (start < 0) start = index;
            else {
                add(tokens, start, index + 1, Scope.STRING);
                start = -1;
            }
        }
    }

    private State html(String text, State state, List<Token> tokens) {
        int index = 0;
        if (state.mode == Mode.HTML_EMBEDDED) {
            int close = indexOfIgnoreCase(text, "</" + state.marker, 0);
            int end = close < 0 ? text.length() : close;
            State inner = code(text, 0, end, state.embedded, state.innerMode, tokens);
            if (close < 0) return new State(Mode.HTML_EMBEDDED, state.embedded, inner.mode, state.marker);
            state = State.normal();
            index = close;
        }
        if (state.mode == Mode.HTML_COMMENT) {
            int close = text.indexOf("-->");
            int end = close < 0 ? text.length() : close + 3;
            add(tokens, 0, end, Scope.COMMENT);
            if (close < 0) return state;
            index = end;
            state = State.normal();
        }
        while (index < text.length()) {
            int comment = text.indexOf("<!--", index);
            int tag = text.indexOf('<', index);
            int next = minPositive(comment, tag);
            if (next < 0) break;
            if (next == comment) {
                int close = text.indexOf("-->", comment + 4);
                int end = close < 0 ? text.length() : close + 3;
                add(tokens, comment, end, Scope.COMMENT);
                if (close < 0) return new State(Mode.HTML_COMMENT, FileType.TEXT, Mode.NORMAL, "");
                index = end;
                continue;
            }
            TagResult result = tag(text, tag, tokens);
            index = result.end;
            if (result.embedded != null && !result.closing) {
                int close = indexOfIgnoreCase(text, "</" + result.name, index);
                int end = close < 0 ? text.length() : close;
                State inner = code(text, index, end, result.embedded, Mode.NORMAL, tokens);
                if (close < 0) return new State(Mode.HTML_EMBEDDED, result.embedded, inner.mode, result.name);
                index = close;
            }
        }
        return State.normal();
    }

    private TagResult tag(String text, int start, List<Token> tokens) {
        int index = start + 1;
        boolean closing = index < text.length() && text.charAt(index) == '/';
        if (closing) index++;
        while (index < text.length() && Character.isWhitespace(text.charAt(index))) index++;
        int nameStart = index;
        while (index < text.length() && isIdentifier(text.charAt(index))) index++;
        String name = text.substring(nameStart, index).toLowerCase(Locale.ROOT);
        if (nameStart < index) add(tokens, start, index, Scope.KEYWORD);
        while (index < text.length() && text.charAt(index) != '>') {
            if (text.charAt(index) == '"' || text.charAt(index) == '\'') {
                char quote = text.charAt(index);
                int valueStart = index++;
                while (index < text.length() && text.charAt(index) != quote) index++;
                if (index < text.length()) index++;
                add(tokens, valueStart, index, Scope.STRING);
                continue;
            }
            if (isIdentifierStart(text.charAt(index))) {
                int attribute = index++;
                while (index < text.length() && (isIdentifier(text.charAt(index)) || text.charAt(index) == '-')) index++;
                add(tokens, attribute, index, Scope.CONSTANT);
                continue;
            }
            index++;
        }
        if (index < text.length()) index++;
        FileType embedded = name.equals("script") ? FileType.JAVASCRIPT : name.equals("style") ? FileType.CSS : null;
        return new TagResult(index, name, closing, embedded);
    }

    private State code(String text, int from, int to, FileType type, Mode initial, List<Token> tokens) {
        int index = from;
        Mode mode = initial;
        if (mode == Mode.BLOCK_COMMENT) {
            int close = text.indexOf("*/", index);
            int end = close < 0 ? to : Math.min(to, close + 2);
            add(tokens, index, end, Scope.COMMENT);
            if (close < 0 || close + 2 > to) return new State(Mode.BLOCK_COMMENT, type, Mode.NORMAL, "");
            index = end;
            mode = Mode.NORMAL;
        } else if (isStringMode(mode)) {
            int end = continuedStringEnd(text, index, to, mode);
            add(tokens, index, end, Scope.STRING);
            if (end >= to && !closed(text, end, mode)) return new State(mode, type, Mode.NORMAL, "");
            index = end;
            mode = Mode.NORMAL;
        }
        while (index < to) {
            char current = text.charAt(index);
            if (supportsLineComments(type) && current == '/' && index + 1 < to && text.charAt(index + 1) == '/') {
                add(tokens, index, to, Scope.COMMENT);
                break;
            }
            if (type == FileType.SQL && current == '-' && index + 1 < to && text.charAt(index + 1) == '-') {
                add(tokens, index, to, Scope.COMMENT);
                break;
            }
            if (supportsBlockComments(type) && current == '/' && index + 1 < to && text.charAt(index + 1) == '*') {
                int close = text.indexOf("*/", index + 2);
                int end = close < 0 ? to : Math.min(to, close + 2);
                add(tokens, index, end, Scope.COMMENT);
                if (close < 0 || close + 2 > to) return new State(Mode.BLOCK_COMMENT, type, Mode.NORMAL, "");
                index = end;
                continue;
            }
            if (type == FileType.CSHARP && current == '[') {
                int end = index + 1;
                while (end < to && (isIdentifier(text.charAt(end)) || text.charAt(end) == '.')) end++;
                if (end > index + 1) {
                    add(tokens, index, end, Scope.ANNOTATION);
                    index = end;
                    continue;
                }
            }
            if (type == FileType.PHP && current == '#' && index + 1 < to && text.charAt(index + 1) == '[') {
                int close = text.indexOf(']', index + 2);
                int end = close < 0 || close >= to ? to : close + 1;
                add(tokens, index, end, Scope.ANNOTATION);
                index = end;
                continue;
            }
            if ((type == FileType.PYTHON || type == FileType.PHP || type == FileType.RUBY || type == FileType.YAML || type == FileType.TOML || type == FileType.SHELL) && current == '#') {
                add(tokens, index, to, Scope.COMMENT);
                break;
            }
            Mode quote = quoteMode(text, index, type, to);
            if (quote != Mode.NORMAL) {
                int end = stringEnd(text, index, to, quote);
                add(tokens, index, end, Scope.STRING);
                if (end >= to && !closed(text, end, quote) && isMultiline(quote)) return new State(quote, type, Mode.NORMAL, "");
                index = end;
                continue;
            }
            if (current == '@' && (type == FileType.JAVA || type == FileType.KOTLIN || type == FileType.SWIFT || type == FileType.PYTHON || type == FileType.JAVASCRIPT || type == FileType.TYPESCRIPT)) {
                int end = identifierEnd(text, index + 1, to);
                add(tokens, index, end, Scope.ANNOTATION);
                index = end;
                continue;
            }
            if (Character.isDigit(current) && (index == from || !isIdentifier(text.charAt(index - 1)))) {
                int end = numberEnd(text, index, to);
                add(tokens, index, end, Scope.NUMBER);
                index = end;
                continue;
            }
            if (isIdentifierStart(current) || (isDollarLanguage(type) && current == '$')) {
                int start = index++;
                while (index < to && (isIdentifier(text.charAt(index)) || (isDollarLanguage(type) && text.charAt(index) == '$'))) index++;
                Scope scope = wordScope(text, start, index, to, type);
                if (scope != null) add(tokens, start, index, scope);
                continue;
            }
            if ((type == FileType.C || type == FileType.CPP) && current == '#') {
                int end = index + 1;
                while (end < to && Character.isLetter(text.charAt(end))) end++;
                add(tokens, index, end, Scope.KEYWORD);
                index = end;
                continue;
            }
            index++;
        }
        return State.normal();
    }

    private Scope wordScope(String text, int start, int end, int limit, FileType type) {
        if (isKeyword(text, start, end, type)) return Scope.KEYWORD;
        if (end - start > 1 && isUpperConstant(text, start, end)) return Scope.CONSTANT;
        int next = end;
        while (next < limit && Character.isWhitespace(text.charAt(next))) next++;
        if (next < text.length() && text.charAt(next) == '(') return Scope.FUNCTION;
        if (Character.isUpperCase(text.charAt(start))) return Scope.TYPE;
        return null;
    }

    private boolean isKeyword(String text, int start, int end, FileType type) {
        int length = end - start;
        for (String keyword : keywords.getOrDefault(type, Set.of())) {
            if (keyword.length() == length && text.regionMatches(type == FileType.SQL, start, keyword, 0, length)) return true;
        }
        return false;
    }

    private boolean isUpperConstant(String text, int start, int end) {
        for (int index = start; index < end; index++) {
            char value = text.charAt(index);
            if (!(Character.isUpperCase(value) || Character.isDigit(value) || value == '_')) return false;
        }
        return true;
    }

    private Mode quoteMode(String text, int index, FileType type, int limit) {
        if ((type == FileType.JAVA || type == FileType.KOTLIN || type == FileType.SWIFT) && startsWith(text, index, "\"\"\"")) return Mode.TRIPLE_DOUBLE;
        if (type == FileType.PYTHON && startsWith(text, index, "\"\"\"")) return Mode.TRIPLE_DOUBLE;
        if (type == FileType.PYTHON && startsWith(text, index, "'''") ) return Mode.TRIPLE_SINGLE;
        char current = text.charAt(index);
        if (current == '"') return Mode.DOUBLE;
        if (current == '\'') return Mode.SINGLE;
        if ((type == FileType.JAVASCRIPT || type == FileType.TYPESCRIPT) && current == '`') return Mode.BACKTICK;
        return Mode.NORMAL;
    }

    private int stringEnd(String text, int start, int limit, Mode mode) {
        String delimiter = switch (mode) {
            case TRIPLE_DOUBLE -> "\"\"\"";
            case TRIPLE_SINGLE -> "'''";
            case DOUBLE -> "\"";
            case SINGLE -> "'";
            case BACKTICK -> "`";
            default -> "";
        };
        int index = start + delimiter.length();
        boolean escaped = false;
        while (index < limit) {
            if (!escaped && startsWith(text, index, delimiter)) return Math.min(limit, index + delimiter.length());
            char current = text.charAt(index);
            if (!isMultiline(mode) && (current == '\n' || current == '\r')) return index;
            escaped = current == '\\' && !escaped;
            if (current != '\\') escaped = false;
            index++;
        }
        return limit;
    }

    private int continuedStringEnd(String text, int start, int limit, Mode mode) {
        String delimiter = switch (mode) {
            case TRIPLE_DOUBLE -> "\"\"\"";
            case TRIPLE_SINGLE -> "'''";
            case DOUBLE -> "\"";
            case SINGLE -> "'";
            case BACKTICK -> "`";
            default -> "";
        };
        int index = start;
        boolean escaped = false;
        while (index < limit) {
            if (!escaped && startsWith(text, index, delimiter)) return Math.min(limit, index + delimiter.length());
            char current = text.charAt(index);
            if (!isMultiline(mode) && (current == '\n' || current == '\r')) return index;
            escaped = current == '\\' && !escaped;
            if (current != '\\') escaped = false;
            index++;
        }
        return limit;
    }

    private boolean closed(String text, int end, Mode mode) {
        if (end <= 0 || end > text.length()) return false;
        return switch (mode) {
            case TRIPLE_DOUBLE -> end >= 3 && text.substring(end - 3, end).equals("\"\"\"");
            case TRIPLE_SINGLE -> end >= 3 && text.substring(end - 3, end).equals("'''");
            case DOUBLE -> text.charAt(end - 1) == '"';
            case SINGLE -> text.charAt(end - 1) == '\'';
            case BACKTICK -> text.charAt(end - 1) == '`';
            default -> true;
        };
    }

    private int numberEnd(String text, int start, int limit) {
        int index = start;
        while (index < limit) {
            char current = text.charAt(index);
            if (!(Character.isLetterOrDigit(current) || current == '_' || current == '.' || current == '+' || current == '-')) break;
            index++;
        }
        return index;
    }

    private int identifierEnd(String text, int start, int limit) {
        int index = start;
        while (index < limit && (isIdentifier(text.charAt(index)) || text.charAt(index) == '.')) index++;
        return index;
    }

    private boolean supportsLineComments(FileType type) {
        return type == FileType.JAVA || type == FileType.KOTLIN || type == FileType.CSHARP || type == FileType.PHP
            || type == FileType.SWIFT || type == FileType.JAVASCRIPT || type == FileType.TYPESCRIPT || type == FileType.RUST
            || type == FileType.GO || type == FileType.C || type == FileType.CPP;
    }

    private boolean supportsBlockComments(FileType type) {
        return type == FileType.JAVA || type == FileType.KOTLIN || type == FileType.CSHARP || type == FileType.PHP
            || type == FileType.SWIFT || type == FileType.JAVASCRIPT || type == FileType.TYPESCRIPT || type == FileType.RUST
            || type == FileType.GO || type == FileType.C || type == FileType.CPP || type == FileType.CSS || type == FileType.SQL;
    }

    private boolean isDollarLanguage(FileType type) {
        return type == FileType.JAVASCRIPT || type == FileType.TYPESCRIPT;
    }

    private boolean isStringMode(Mode mode) {
        return mode == Mode.DOUBLE || mode == Mode.SINGLE || mode == Mode.BACKTICK || mode == Mode.TRIPLE_DOUBLE || mode == Mode.TRIPLE_SINGLE;
    }

    private boolean isMultiline(Mode mode) {
        return mode == Mode.BACKTICK || mode == Mode.TRIPLE_DOUBLE || mode == Mode.TRIPLE_SINGLE;
    }

    private static boolean isIdentifierStart(char current) {
        return Character.isLetter(current) || current == '_';
    }

    private static boolean isIdentifier(char current) {
        return Character.isLetterOrDigit(current) || current == '_';
    }

    private static boolean startsWith(String text, int offset, String value) {
        return offset >= 0 && offset + value.length() <= text.length() && text.regionMatches(offset, value, 0, value.length());
    }

    private static int minPositive(int first, int second) {
        if (first < 0) return second;
        if (second < 0) return first;
        return Math.min(first, second);
    }

    private static int indexOfIgnoreCase(String text, String target, int from) {
        for (int index = Math.max(0, from); index <= text.length() - target.length(); index++) {
            if (text.regionMatches(true, index, target, 0, target.length())) return index;
        }
        return -1;
    }

    private static void add(List<Token> tokens, int start, int end, Scope scope) {
        if (tokens != null && start >= 0 && end > start) tokens.add(new Token(start, end, scope));
    }

    private record Line(String text, State start, State end, List<Token> tokens) {}
    private record TagResult(int end, String name, boolean closing, FileType embedded) {}
    private record ProfileState(String close, Scope scope) {
        static ProfileState normal() { return new ProfileState("", Scope.STRING); }
    }
    private record State(Mode mode, FileType embedded, Mode innerMode, String marker) {
        static State normal() { return new State(Mode.NORMAL, FileType.TEXT, Mode.NORMAL, ""); }
        State withEmbedded(FileType type, String marker) { return new State(mode, type, Mode.NORMAL, marker); }
    }
    private enum Mode { NORMAL, BLOCK_COMMENT, DOUBLE, SINGLE, BACKTICK, TRIPLE_DOUBLE, TRIPLE_SINGLE, HTML_COMMENT, HTML_EMBEDDED, FENCE }
}
