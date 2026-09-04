package shed;

import com.jediterm.terminal.model.hyperlinks.HyperlinkFilter;
import com.jediterm.terminal.model.hyperlinks.LinkInfo;
import com.jediterm.terminal.model.hyperlinks.LinkResult;
import com.jediterm.terminal.model.hyperlinks.LinkResultItem;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Turns concrete local source locations and HTTP(S) output into terminal hyperlinks. */
final class TerminalLinkResolver {
    private static final int MAX_LINE_LENGTH = 16 * 1024;
    private static final Pattern WEB_URL = Pattern.compile("(?i)https?://[^\\s<>()\\[\\]{}\"']+");
    private static final Pattern SOURCE_LOCATION = Pattern.compile(
        "(?<![A-Za-z0-9_./\\\\-])((?:[A-Za-z]:)?(?:[./\\\\][^\\s:()\\[\\]{}\"']*|[A-Za-z0-9_][A-Za-z0-9_./\\\\-]*\\.[A-Za-z0-9_+-]+)):(\\d+)(?::(\\d+))?");

    private TerminalLinkResolver() {
    }

    sealed interface Link permits BrowserLink, SourceLink {
    }

    record BrowserLink(URI uri) implements Link {
        BrowserLink {
            if (uri == null || !("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()))
                    || uri.getHost() == null) {
                throw new IllegalArgumentException("HTTP(S) URL required");
            }
        }
    }

    record SourceLink(Path path, int line, int column) implements Link {
        SourceLink {
            if (path == null || line < 1 || column < 1) {
                throw new IllegalArgumentException("regular file location required");
            }
        }
    }

    static HyperlinkFilter create(Supplier<Path> workingDirectory, Consumer<Link> opener) {
        if (workingDirectory == null || opener == null) {
            throw new IllegalArgumentException("working directory and opener are required");
        }
        return line -> resolve(line, workingDirectory, opener);
    }

    static LinkResult resolve(String line, Supplier<Path> workingDirectory, Consumer<Link> opener) {
        if (line == null || line.isEmpty() || line.length() > MAX_LINE_LENGTH) {
            return null;
        }
        List<ResolvedMatch> matches = new ArrayList<>();
        Matcher urls = WEB_URL.matcher(line);
        while (urls.find()) {
            String raw = trimTrailingPunctuation(urls.group());
            BrowserLink link = browserLink(raw);
            if (link != null) {
                matches.add(new ResolvedMatch(urls.start(), urls.start() + raw.length(), link));
            }
        }

        Matcher locations = SOURCE_LOCATION.matcher(line);
        while (locations.find()) {
            if (overlaps(matches, locations.start(), locations.end())) {
                continue;
            }
            SourceLink link = sourceLink(locations.group(1), locations.group(2), locations.group(3), workingDirectory.get());
            if (link != null) {
                matches.add(new ResolvedMatch(locations.start(), locations.end(), link));
            }
        }
        if (matches.isEmpty()) {
            return null;
        }
        matches.sort(Comparator.comparingInt(ResolvedMatch::start));
        List<LinkResultItem> result = new ArrayList<>();
        for (ResolvedMatch match : matches) {
            result.add(new LinkResultItem(match.start(), match.end(), new LinkInfo(() -> opener.accept(match.link()))));
        }
        return new LinkResult(result);
    }

    private static BrowserLink browserLink(String raw) {
        try {
            return new BrowserLink(URI.create(raw));
        } catch (IllegalArgumentException error) {
            return null;
        }
    }

    private static SourceLink sourceLink(String rawPath, String rawLine, String rawColumn, Path workingDirectory) {
        if (rawPath == null || workingDirectory == null) {
            return null;
        }
        try {
            Path candidate = Path.of(rawPath);
            if (!candidate.isAbsolute()) {
                candidate = workingDirectory.resolve(candidate);
            }
            candidate = candidate.normalize();
            if (!Files.isRegularFile(candidate)) {
                return null;
            }
            int line = positiveNumber(rawLine);
            int column = rawColumn == null ? 1 : positiveNumber(rawColumn);
            return line < 1 || column < 1 ? null : new SourceLink(candidate.toRealPath(), line, column);
        } catch (RuntimeException | java.io.IOException error) {
            return null;
        }
    }

    private static int positiveNumber(String value) {
        try {
            long parsed = Long.parseLong(value);
            return parsed > 0 && parsed <= Integer.MAX_VALUE ? (int) parsed : -1;
        } catch (NumberFormatException error) {
            return -1;
        }
    }

    private static boolean overlaps(List<ResolvedMatch> matches, int start, int end) {
        for (ResolvedMatch match : matches) {
            if (start < match.end() && end > match.start()) {
                return true;
            }
        }
        return false;
    }

    private static String trimTrailingPunctuation(String value) {
        int end = value.length();
        while (end > 0 && ".,;:!?".indexOf(value.charAt(end - 1)) >= 0) {
            end--;
        }
        return value.substring(0, end);
    }

    private record ResolvedMatch(int start, int end, Link link) {
    }
}
