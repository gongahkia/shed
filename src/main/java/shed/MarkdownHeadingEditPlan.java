package shed;

import java.util.Objects;
import java.util.Optional;

/** A short-lived proposal over shared immutable source. It owns no document, surface or history. */
record MarkdownHeadingEditPlan(MarkdownSemanticSnapshot.Source source, MarkdownSourceRange range,
                               String expected, String replacement, boolean demote, int dot, int mark) {
    enum Status { READY, NOT_HEADING, BOUNDARY, UNSUPPORTED, UNVERIFIED, FAILED, CANCELLED }
    record Result(Status status, Optional<MarkdownHeadingEditPlan> edit, String message) {
        Result {
            Objects.requireNonNull(status); Objects.requireNonNull(edit); Objects.requireNonNull(message);
            if ((status == Status.READY) != edit.isPresent()) throw new IllegalArgumentException("Inconsistent edit plan");
        }
    }

    MarkdownHeadingEditPlan {
        Objects.requireNonNull(source); Objects.requireNonNull(range);
        Objects.requireNonNull(expected); Objects.requireNonNull(replacement);
        if (!source.completeSource() || !range.text(source.text()).equals(expected)
            || expected.isEmpty() || expected.length() > 6 || !expected.equals("#".repeat(expected.length()))
            || replacement.length() != expected.length() + (demote ? 1 : -1)
            || replacement.isEmpty() || replacement.length() > 6 || !replacement.equals("#".repeat(replacement.length())))
            throw new IllegalArgumentException("Invalid heading marker proposal");
        new MarkdownSourceRange(dot, dot).validate(source.text());
        new MarkdownSourceRange(mark, mark).validate(source.text());
    }

    static Result plan(MarkdownSemanticSnapshot semantic, int dot, int mark, boolean demote) {
        if (semantic.status() != MarkdownSemanticSnapshot.Status.SUCCEEDED) return rejected(switch (semantic.status()) {
            case CANCELLED -> Status.CANCELLED;
            case FAILED -> Status.FAILED;
            default -> Status.UNSUPPORTED;
        }, "Markdown heading edit unavailable: " + semantic.status().name().toLowerCase(java.util.Locale.ROOT));
        if (semantic.coverage() != MarkdownSemanticSnapshot.Coverage.COMPLETE || !semantic.source().completeSource())
            return rejected(Status.UNSUPPORTED, "Markdown heading edit unavailable: incomplete coverage");
        try {
            var text = semantic.source().text();
            new MarkdownSourceRange(dot, dot).validate(text); new MarkdownSourceRange(mark, mark).validate(text);
            int line = text.positionAt(dot).line();
            int lineStart = text.lineStartOffset(line), lineEnd = text.lineEndOffset(line);
            MarkdownSemanticSnapshot.Heading target = null;
            for (var heading : semantic.headings()) {
                var segments = heading.location().segments();
                if (segments.isEmpty()) return rejected(Status.UNVERIFIED, "Markdown heading location unavailable");
                if (segments.getLast().end() < lineStart || segments.getFirst().start() > lineEnd) continue;
                if (target != null) return rejected(Status.UNVERIFIED, "Ambiguous Markdown heading location");
                target = heading;
            }
            if (target == null) return rejected(Status.NOT_HEADING, "Not on a Markdown heading");
            if (target.marker().isEmpty()) return rejected(Status.UNVERIFIED, "Markdown heading marker unavailable");
            var marker = target.marker().get();
            String expected = marker.text(text);
            if (expected.isEmpty()) return rejected(Status.UNVERIFIED, "Markdown heading marker unavailable");
            if (expected.chars().allMatch(c -> c == '=' || c == '-'))
                return rejected(Status.UNSUPPORTED, "Setext heading marker editing unavailable");
            if (target.level() < 1 || target.level() > 6 || !expected.equals("#".repeat(target.level()))
                || target.location().segments().stream().noneMatch(s -> marker.start() >= s.start() && marker.end() <= s.end())
                || text.positionAt(marker.start()).line() != line)
                return rejected(Status.UNVERIFIED, "Markdown heading marker unavailable");
            if (demote && target.level() == 6 || !demote && target.level() == 1)
                return rejected(Status.BOUNDARY, demote ? "Already at h6" : "Already at h1");
            var edit = new MarkdownHeadingEditPlan(semantic.source(), marker, expected,
                "#".repeat(target.level() + (demote ? 1 : -1)), demote, dot, mark);
            return new Result(Status.READY, Optional.of(edit), demote ? "Demoted heading" : "Promoted heading");
        } catch (IllegalArgumentException invalid) {
            return rejected(Status.UNVERIFIED, "Markdown heading marker or selection unavailable");
        }
    }

    private static Result rejected(Status status, String message) { return new Result(status, Optional.empty(), message); }
}
