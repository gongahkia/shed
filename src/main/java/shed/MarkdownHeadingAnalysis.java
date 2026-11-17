package shed;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.BooleanSupplier;

/** Worker-owned projection for existing heading consumers, with an explicit legacy provenance. */
record MarkdownHeadingAnalysis(MarkdownSemanticSnapshot semantic, Path path, List<Heading> headings,
                               long workerNanos) {
    enum Path { SEMANTIC, LEGACY_UNSUPPORTED, LEGACY_FAILED, LEGACY_UNVERIFIED, CANCELLED, FAILED }
    record Heading(int offset, int line, int level, String title) { }
    MarkdownHeadingAnalysis { headings = List.copyOf(headings); }
    MarkdownSemanticSnapshot.Source source() { return semantic.source(); }

    java.util.Optional<Heading> target(boolean forward, int currentLine, int level) {
        int low = 0, high = headings.size();
        while (low < high) {
            int middle = (low + high) >>> 1;
            int line = headings.get(middle).line();
            if (line < currentLine || forward && line == currentLine) low = middle + 1;
            else high = middle;
        }
        for (int i = forward ? low : low - 1; i >= 0 && i < headings.size(); i += forward ? 1 : -1) {
            Heading heading = headings.get(i);
            if (level == 0 || heading.level() == level) return java.util.Optional.of(heading);
        }
        return java.util.Optional.empty();
    }

    static MarkdownHeadingAnalysis analyze(MarkdownSemanticSnapshot.Source source, BooleanSupplier cancelled) {
        long began = System.nanoTime();
        // 2A measured >550 MiB transient allocation before its AST node cap on dense 1 MiB input.
        // This conservative consumer admission rule avoids that burst; it is not a parser or a new setting.
        if (source.text().length() > 256 * 1024 && source.text().length() <= MarkdownSemanticParser.MAX_CHARACTERS) {
            int markers = 0;
            for (int start = 0; start < source.text().length(); start += 8192) {
                if (cancelled.getAsBoolean()) return cancelled(source, began);
                String chunk = source.text().substring(start, Math.min(8192, source.text().length() - start));
                for (int i = 0; i < chunk.length(); i++) {
                    char c = chunk.charAt(i);
                    if ((c == '*' || c == '_' || c == '[' || c == ']' || c == '|' || c == '#') && ++markers > 32_768)
                        return from(MarkdownSemanticSnapshot.unavailable(source, MarkdownSemanticSnapshot.Status.UNSUPPORTED,
                            "Heading consumer structural-marker admission limit exceeded"), cancelled, began);
                }
            }
        }
        return from(new MarkdownSemanticParser().parse(source, cancelled), cancelled, began);
    }

    static MarkdownHeadingAnalysis from(MarkdownSemanticSnapshot semantic, BooleanSupplier cancelled, long began) {
        var source = semantic.source();
        List<Heading> headings = new ArrayList<>();
        Path path = switch (semantic.status()) {
            case SUCCEEDED -> Path.SEMANTIC;
            case UNSUPPORTED -> Path.LEGACY_UNSUPPORTED;
            case FAILED -> Path.LEGACY_FAILED;
            case CANCELLED -> Path.CANCELLED;
        };
        if (path == Path.SEMANTIC) {
            for (var heading : semantic.headings()) {
                if (cancelled.getAsBoolean()) return cancelled(source, began);
                var extent = heading.location().envelope();
                if (extent.isEmpty()) { path = Path.LEGACY_UNVERIFIED; break; }
                int offset = extent.get().start();
                headings.add(new Heading(offset, source.text().positionAt(offset).line() + 1, heading.level(), heading.text()));
            }
        }
        if (path != Path.SEMANTIC && path != Path.CANCELLED) {
            headings.clear();
            var legacy = new MarkdownService();
            // Preserve the former line-based navigation, including its documented limitations.
            int line = 1, lineStart = 0;
            StringBuilder candidate = null;
            for (int start = 0; start < source.text().length(); start += 8192) {
                if (cancelled.getAsBoolean() || Thread.currentThread().isInterrupted()) return cancelled(source, began);
                String chunk = source.text().substring(start, Math.min(8192, source.text().length() - start));
                for (int index = 0; index < chunk.length(); index++) {
                    char c = chunk.charAt(index);
                    int offset = start + index;
                    if (c == '\n') {
                        addLegacy(headings, legacy, candidate, lineStart, line);
                        candidate = null; line++; lineStart = offset + 1;
                    } else if (offset == lineStart && c == '#') candidate = new StringBuilder().append(c);
                    else if (candidate != null) candidate.append(c);
                }
            }
            addLegacy(headings, legacy, candidate, lineStart, line);
        }
        headings.sort(Comparator.comparingInt(Heading::offset));
        return new MarkdownHeadingAnalysis(semantic, path, headings, System.nanoTime() - began);
    }

    private static void addLegacy(List<Heading> headings, MarkdownService legacy, StringBuilder candidate, int offset, int line) {
        if (candidate == null) return;
        String text = candidate.toString();
        int level = legacy.headingLevel(text);
        if (level > 0) headings.add(new Heading(offset, line, level, legacy.headingText(text)));
    }

    private static MarkdownHeadingAnalysis cancelled(MarkdownSemanticSnapshot.Source source, long began) {
        return new MarkdownHeadingAnalysis(MarkdownSemanticSnapshot.unavailable(source,
            MarkdownSemanticSnapshot.Status.CANCELLED, "Heading request cancelled"), Path.CANCELLED, List.of(), System.nanoTime() - began);
    }
}
