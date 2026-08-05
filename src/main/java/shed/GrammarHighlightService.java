package shed;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.EnumSet;
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
            case "html", "xml" -> FileType.HTML;
            case "css" -> FileType.CSS;
            case "json" -> FileType.JSON;
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
            if (supportsBlockComments(type) && current == '/' && index + 1 < to && text.charAt(index + 1) == '*') {
                int close = text.indexOf("*/", index + 2);
                int end = close < 0 ? to : Math.min(to, close + 2);
                add(tokens, index, end, Scope.COMMENT);
                if (close < 0 || close + 2 > to) return new State(Mode.BLOCK_COMMENT, type, Mode.NORMAL, "");
                index = end;
                continue;
            }
            if (type == FileType.PYTHON && current == '#') {
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
            if (current == '@' && (type == FileType.JAVA || type == FileType.PYTHON || type == FileType.JAVASCRIPT || type == FileType.TYPESCRIPT)) {
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
                String word = text.substring(start, index);
                Scope scope = wordScope(word, text, index, type);
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

    private Scope wordScope(String word, String text, int end, FileType type) {
        if (keywords.getOrDefault(type, Set.of()).contains(word)) return Scope.KEYWORD;
        if (word.length() > 1 && word.chars().allMatch(value -> Character.isUpperCase((char) value) || Character.isDigit((char) value) || value == '_')) return Scope.CONSTANT;
        int next = end;
        while (next < text.length() && Character.isWhitespace(text.charAt(next))) next++;
        if (next < text.length() && text.charAt(next) == '(') return Scope.FUNCTION;
        if (Character.isUpperCase(word.charAt(0))) return Scope.TYPE;
        return null;
    }

    private Mode quoteMode(String text, int index, FileType type, int limit) {
        if (type == FileType.JAVA && startsWith(text, index, "\"\"\"")) return Mode.TRIPLE_DOUBLE;
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
        return EnumSet.of(FileType.JAVA, FileType.JAVASCRIPT, FileType.TYPESCRIPT, FileType.RUST, FileType.GO, FileType.C, FileType.CPP).contains(type);
    }

    private boolean supportsBlockComments(FileType type) {
        return EnumSet.of(FileType.JAVA, FileType.JAVASCRIPT, FileType.TYPESCRIPT, FileType.RUST, FileType.GO, FileType.C, FileType.CPP, FileType.CSS).contains(type);
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
        if (start >= 0 && end > start) tokens.add(new Token(start, end, scope));
    }

    private record Line(String text, State start, State end, List<Token> tokens) {}
    private record TagResult(int end, String name, boolean closing, FileType embedded) {}
    private record State(Mode mode, FileType embedded, Mode innerMode, String marker) {
        static State normal() { return new State(Mode.NORMAL, FileType.TEXT, Mode.NORMAL, ""); }
        State withEmbedded(FileType type, String marker) { return new State(mode, type, Mode.NORMAL, marker); }
    }
    private enum Mode { NORMAL, BLOCK_COMMENT, DOUBLE, SINGLE, BACKTICK, TRIPLE_DOUBLE, TRIPLE_SINGLE, HTML_COMMENT, HTML_EMBEDDED, FENCE }
}
