package shed.api;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Declarative lexical metadata for an extension language.
 *
 * <p>This intentionally describes only safe, editor-local lexical behavior.
 * It is not a TextMate grammar, a parser, or a semantic-token provider.</p>
 */
public record LanguageProfile(
    String languageId,
    String displayName,
    Set<String> fileExtensions,
    Set<String> fileNames,
    Set<String> firstLinePrefixes,
    List<String> lineCommentPrefixes,
    List<BlockComment> blockComments,
    List<StringDelimiter> stringDelimiters,
    Set<String> keywords,
    Integer tabSize,
    Boolean insertSpaces
) {
    private static final int MAX_ITEMS = 128;
    private static final int MAX_TOKEN_LENGTH = 64;

    public LanguageProfile {
        if (languageId == null || !languageId.matches("[A-Za-z0-9._-]+")) {
            throw new IllegalArgumentException("language id must contain only letters, numbers, '.', '_' or '-'");
        }
        if (displayName == null || displayName.isBlank() || displayName.length() > 120) {
            throw new IllegalArgumentException("language display name is required and must be at most 120 characters");
        }
        fileExtensions = extensions(fileExtensions);
        fileNames = fileNames(fileNames);
        firstLinePrefixes = strings(firstLinePrefixes, "first-line prefix", true);
        if (fileExtensions.isEmpty() && fileNames.isEmpty() && firstLinePrefixes.isEmpty()) {
            throw new IllegalArgumentException("a language profile needs a file extension, file name, or first-line prefix");
        }
        lineCommentPrefixes = ordered(strings(lineCommentPrefixes, "line comment prefix", false));
        blockComments = blockComments(blockComments);
        stringDelimiters = stringDelimiters(stringDelimiters);
        keywords = strings(keywords, "keyword", false);
        if (tabSize != null && (tabSize < 1 || tabSize > 16)) {
            throw new IllegalArgumentException("tab size must be between 1 and 16 when specified");
        }
    }

    /**
     * Source- and binary-compatible API-v1 constructor without language-local
     * indentation preferences. The editor then keeps the user's settings.
     */
    public LanguageProfile(String languageId, String displayName, Set<String> fileExtensions, Set<String> fileNames,
                           Set<String> firstLinePrefixes, List<String> lineCommentPrefixes, List<BlockComment> blockComments,
                           List<StringDelimiter> stringDelimiters, Set<String> keywords) {
        this(languageId, displayName, fileExtensions, fileNames, firstLinePrefixes, lineCommentPrefixes, blockComments,
            stringDelimiters, keywords, null, null);
    }

    /** A literal start/end comment pair. */
    public record BlockComment(String start, String end) {
        public BlockComment {
            start = token(start, "block comment start");
            end = token(end, "block comment end");
        }
    }

    /** A literal string delimiter and whether it may span physical lines. */
    public record StringDelimiter(String value, boolean multiline) {
        public StringDelimiter {
            value = token(value, "string delimiter");
        }
    }

    private static Set<String> extensions(Set<String> values) {
        LinkedHashSet<String> result = new LinkedHashSet<>();
        for (String value : values == null ? Set.<String>of() : values) {
            String normalized = value == null ? "" : value.trim().replaceFirst("^\\.", "").toLowerCase(Locale.ROOT);
            if (!normalized.matches("[a-z0-9][a-z0-9+_-]*")) {
                throw new IllegalArgumentException("invalid file extension: " + value);
            }
            add(result, normalized, "file extension");
        }
        return Set.copyOf(result);
    }

    private static Set<String> fileNames(Set<String> values) {
        LinkedHashSet<String> result = new LinkedHashSet<>();
        for (String value : values == null ? Set.<String>of() : values) {
            String normalized = value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
            if (normalized.isEmpty() || normalized.length() > 255 || normalized.indexOf('/') >= 0 || normalized.indexOf('\\') >= 0
                || normalized.indexOf('\0') >= 0 || normalized.indexOf('\n') >= 0 || normalized.indexOf('\r') >= 0) {
                throw new IllegalArgumentException("invalid file name: " + value);
            }
            add(result, normalized, "file name");
        }
        return Set.copyOf(result);
    }

    private static Set<String> strings(Iterable<String> values, String label, boolean preserveCase) {
        LinkedHashSet<String> result = new LinkedHashSet<>();
        if (values == null) return Set.of();
        for (String value : values) {
            add(result, token(value, label), label);
        }
        return Set.copyOf(result);
    }

    private static List<BlockComment> blockComments(List<BlockComment> values) {
        LinkedHashSet<BlockComment> result = new LinkedHashSet<>();
        for (BlockComment value : values == null ? List.<BlockComment>of() : values) {
            if (value == null) throw new IllegalArgumentException("block comment is required");
            if (result.size() >= MAX_ITEMS) throw new IllegalArgumentException("too many block comments");
            result.add(value);
        }
        return ordered(result.stream().toList());
    }

    private static List<StringDelimiter> stringDelimiters(List<StringDelimiter> values) {
        LinkedHashSet<StringDelimiter> result = new LinkedHashSet<>();
        for (StringDelimiter value : values == null ? List.<StringDelimiter>of() : values) {
            if (value == null) throw new IllegalArgumentException("string delimiter is required");
            if (result.size() >= MAX_ITEMS) throw new IllegalArgumentException("too many string delimiters");
            result.add(value);
        }
        List<StringDelimiter> sorted = new ArrayList<>(result);
        sorted.sort(Comparator.comparingInt((StringDelimiter value) -> value.value().length()).reversed().thenComparing(StringDelimiter::value));
        return List.copyOf(sorted);
    }

    private static List<String> ordered(Set<String> values) {
        List<String> sorted = new ArrayList<>(values);
        sorted.sort(Comparator.comparingInt(String::length).reversed().thenComparing(Comparator.naturalOrder()));
        return List.copyOf(sorted);
    }

    private static List<BlockComment> ordered(List<BlockComment> values) {
        List<BlockComment> sorted = new ArrayList<>(values);
        sorted.sort(Comparator.comparingInt((BlockComment value) -> value.start().length()).reversed().thenComparing(BlockComment::start));
        return List.copyOf(sorted);
    }

    private static String token(String value, String label) {
        if (value == null || value.isEmpty() || value.length() > MAX_TOKEN_LENGTH || value.indexOf('\0') >= 0
            || value.indexOf('\n') >= 0 || value.indexOf('\r') >= 0) {
            throw new IllegalArgumentException(label + " must be a non-empty, single-line token of at most " + MAX_TOKEN_LENGTH + " characters");
        }
        return value;
    }

    private static void add(Set<String> result, String value, String label) {
        if (result.size() >= MAX_ITEMS && !result.contains(value)) {
            throw new IllegalArgumentException("too many " + label + " entries");
        }
        result.add(value);
    }
}
