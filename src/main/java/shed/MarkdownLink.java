package shed;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

/** Compact read-only facts. These records deliberately retain neither source snapshots nor buffers. */
final class MarkdownLink {
    private MarkdownLink() { }
    enum State { COMPLETE, PENDING, PARTIAL, UNSUPPORTED, FAILED, STALE, CANCELLED, CONFLICT, CLOSED }
    enum Kind { MARKDOWN, REFERENCE, DEFINITION, IMAGE, WIKI }
    enum Status { RESOLVED, EXTERNAL, MISSING, AMBIGUOUS, OUT_OF_SCOPE, UNSUPPORTED, PENDING, MISSING_HEADING, AMBIGUOUS_HEADING, HEADING_PENDING, HEADING_UNSUPPORTED }
    record Origin(Path file, Optional<DocumentWorkspace.Id> document, String version) {
        Origin { file = file.toAbsolutePath().normalize(); document = java.util.Objects.requireNonNull(document); java.util.Objects.requireNonNull(version); }
    }
    record Occurrence(Origin source, Kind kind, List<MarkdownSourceRange> ranges, String destination,
                      Optional<String> rawDestination, Optional<String> fragment, Optional<String> alias,
                      Optional<String> reference, int line, int column) {
        Occurrence(Origin source, Kind kind, List<MarkdownSourceRange> ranges, String destination,
                   Optional<String> rawDestination, Optional<String> fragment, Optional<String> alias, Optional<String> reference) {
            this(source, kind, ranges, destination, rawDestination, fragment, alias, reference, 0, 0);
        }
        Occurrence { if (line < 0 || column < 0) throw new IllegalArgumentException("Invalid source location"); ranges = List.copyOf(ranges); if (ranges.isEmpty()) throw new IllegalArgumentException("Verified range required"); }
        int offset() { return ranges.getFirst().start(); }
    }
    record Heading(String text, String anchor, int offset) { }
    record Facts(Origin origin, State state, List<Occurrence> links, List<Heading> headings, String detail, boolean anchorsCompatible) {
        Facts(Origin origin, State state, List<Occurrence> links, List<Heading> headings, String detail) { this(origin, state, links, headings, detail, true); }
        Facts { links = List.copyOf(links); headings = List.copyOf(headings); }
        static Facts unavailable(Origin origin, State state, String detail) { return new Facts(origin, state, List.of(), List.of(), detail); }
    }
    record Resolution(Status status, List<Path> candidates, Optional<Heading> heading, Optional<String> targetVersion) {
        Resolution { candidates = List.copyOf(candidates); }
        static Resolution of(Status status, List<Path> candidates) { return new Resolution(status, candidates, Optional.empty(), Optional.empty()); }
        Optional<Path> target() { return (status == Status.RESOLVED || status == Status.MISSING_HEADING || status == Status.AMBIGUOUS_HEADING
            || status == Status.HEADING_PENDING || status == Status.HEADING_UNSUPPORTED) && candidates.size() == 1 ? Optional.of(candidates.getFirst()) : Optional.empty(); }
    }
    record Edge(Occurrence occurrence, Resolution resolution) { }
    /** Completeness accompanies even an empty list. Pending/stale lists contain no confirmed old edges. */
    record Query(State state, List<Edge> edges, List<Path> incompleteFiles, String detail) {
        Query { edges = List.copyOf(edges); incompleteFiles = List.copyOf(incompleteFiles); }
    }

    static Facts project(Origin origin, MarkdownSemanticSnapshot semantic) {
        if (semantic.status() != MarkdownSemanticSnapshot.Status.SUCCEEDED) return Facts.unavailable(origin, switch (semantic.status()) {
            case UNSUPPORTED -> State.UNSUPPORTED; case CANCELLED -> State.CANCELLED; default -> State.FAILED;
        }, semantic.detail());
        var occurrences = new MarkdownOccurrences(semantic);
        var links = new java.util.ArrayList<Occurrence>(); var headings = new java.util.ArrayList<Heading>();
        boolean anchorsCompatible = semantic.headings().stream().noneMatch(heading -> heading.text().indexOf('$') >= 0);
        boolean complete = occurrences.sourceLocationsComplete() && anchorsCompatible;
        long retainedUnits = 0;
        for (var occurrence : occurrences.stream().toList()) {
            if (occurrence.destination().isEmpty()) continue;
            String destination = occurrence.destination().orElseThrow();
            long units = destination.length() + occurrence.rawDestination().map(String::length).orElse(0)
                + occurrence.displayAlias().map(String::length).orElse(0) + occurrence.label().map(String::length).orElse(0)
                + occurrence.referenceIdentifier().map(String::length).orElse(0);
            if (links.size() >= 2048 || occurrence.location().segments().size() > 64 || units > 8192 || retainedUnits + units > 65536) { complete = false; continue; }
            retainedUnits += units;
            Kind kind = switch (occurrence.kind()) {
                case WIKI_LINK -> Kind.WIKI; case IMAGE -> Kind.IMAGE; case REFERENCE_DEFINITION -> Kind.DEFINITION;
                default -> occurrence.referenceStatus() == MarkdownOccurrences.ReferenceStatus.RESOLVED ? Kind.REFERENCE : Kind.MARKDOWN;
            };
            var position = semantic.source().text().positionAt(occurrence.location().segments().getFirst().start());
            links.add(new Occurrence(origin, kind, occurrence.location().segments(), destination, occurrence.rawDestination(), occurrence.fragment(),
                occurrence.displayAlias().or(() -> occurrence.label()), occurrence.referenceIdentifier(), position.line() + 1, position.character() + 1));
        }
        for (var heading : semantic.headings()) {
            if (heading.location().segments().isEmpty() || headings.size() >= 2048 || heading.text().length() + heading.anchor().length() > 4096
                    || retainedUnits + heading.text().length() + heading.anchor().length() > 65536) { complete = false; continue; }
            retainedUnits += heading.text().length() + heading.anchor().length();
            headings.add(new Heading(heading.text(), heading.anchor(), heading.location().segments().getFirst().start()));
        }
        links.sort(java.util.Comparator.comparingInt(Occurrence::offset).thenComparing(Occurrence::kind));
        return new Facts(origin, complete ? State.COMPLETE : State.PARTIAL, links, headings,
            complete ? "Verified supported link syntax; unresolved CommonMark reference text remains unclassified" : "Unavailable source spans, preview-anchor compatibility or compact-record admission limit", anchorsCompatible);
    }
}
