package shed;

import java.util.Optional;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/** Lazy read-only views over one semantic revision, not another retained AST or resource resolver. */
final class MarkdownOccurrences {
    enum Kind { LINK, IMAGE, REFERENCE_DEFINITION, CODE_FENCE, MERMAID, WIKI_LINK }
    enum ReferenceStatus { NOT_REFERENCE, RESOLVED, DEFINITION, UNKNOWN }
    record Occurrence(MarkdownSemanticSnapshot.Source source, Kind kind, MarkdownSemanticSnapshot.Location location,
                      Optional<String> destination, Optional<String> rawDestination, Optional<String> referenceIdentifier,
                      Optional<String> label, Optional<String> fragment, ReferenceStatus referenceStatus,
                      Optional<String> language, Optional<MarkdownSourceRange> openingMarker,
                      Optional<MarkdownSourceRange> closingMarker, Optional<String> displayAlias) {
        /** Exact single segment only. Multi-line/container occurrences retain their disjoint segments. */
        Optional<MarkdownSourceRange> exactRange() {
            return location.segments().size() == 1 ? Optional.of(location.segments().getFirst()) : Optional.empty();
        }
    }
    private static final Pattern INLINE = Pattern.compile("!?\\[([^\\[\\]\\r\\n\\\\]*)\\]\\(([^\\s()\\\\]+)\\)");
    private static final Pattern REFERENCE = Pattern.compile("!?\\[([^\\[\\]\\r\\n\\\\]*)\\](?:\\[([^\\[\\]\\r\\n\\\\]*)\\])?");
    private final MarkdownSemanticSnapshot semantic;
    MarkdownOccurrences(MarkdownSemanticSnapshot semantic) { this.semantic = java.util.Objects.requireNonNull(semantic); }
    MarkdownSemanticSnapshot.Source source() { return semantic.source(); }
    MarkdownSemanticSnapshot.Status status() { return semantic.status(); }
    MarkdownSemanticSnapshot.Coverage coverage() { return semantic.coverage(); }
    java.util.Set<MarkdownSemanticSnapshot.Limitation> limitations() { return semantic.limitations(); }
    /** Precision of recognized link/reference/fence locations, not support for deferred syntax. */
    boolean sourceLocationsComplete() {
        return status() == MarkdownSemanticSnapshot.Status.SUCCEEDED && unavailableLinkLocations() == 0 && semantic.unavailableWikiLocations() == 0
            && semantic.references().stream().noneMatch(r -> r.location().segments().isEmpty())
            && semantic.codeBlocks().stream().noneMatch(c -> c.location().segments().isEmpty());
    }
    boolean isCurrent(MarkdownSemanticSnapshot.Source source) { return semantic.isCurrent(source); }
    boolean mathSupported() { return false; }
    boolean unresolvedReferenceUsesSupported() { return false; }
    long unavailableLinkLocations() { return semantic.links().stream().filter(l -> l.location().segments().isEmpty()).count(); }

    Stream<Occurrence> stream() {
        if (status() != MarkdownSemanticSnapshot.Status.SUCCEEDED) return Stream.empty();
        Stream<Occurrence> links = semantic.links().stream().filter(l -> !l.location().segments().isEmpty() && !insideWiki(l.location())).map(this::link);
        Stream<Occurrence> wiki = semantic.wikiLinks().stream().map(w -> new Occurrence(source(), Kind.WIKI_LINK,
            new MarkdownSemanticSnapshot.Location(java.util.List.of(w.range())), Optional.of(w.destination()), Optional.of(w.destination()),
            Optional.empty(), w.alias(), w.fragment(), ReferenceStatus.NOT_REFERENCE, Optional.empty(),
            Optional.of(new MarkdownSourceRange(w.range().start(), w.content().start())),
            Optional.of(new MarkdownSourceRange(w.content().end(), w.range().end())), w.alias()));
        Stream<Occurrence> definitions = semantic.references().stream().filter(r -> !r.location().segments().isEmpty()).map(r ->
            occurrence(Kind.REFERENCE_DEFINITION, r.location(), Optional.of(r.destination()), Optional.empty(),
                Optional.of(r.label()), Optional.of(r.label()), ReferenceStatus.DEFINITION, Optional.empty(), Optional.empty(), Optional.empty()));
        Stream<Occurrence> fences = semantic.codeBlocks().stream().filter(c -> !c.location().segments().isEmpty()).map(c ->
            occurrence(c.mermaid() ? Kind.MERMAID : Kind.CODE_FENCE, c.location(), Optional.empty(), Optional.empty(),
                Optional.empty(), Optional.empty(), ReferenceStatus.NOT_REFERENCE, Optional.of(c.language()), c.openingMarker(), c.closingMarker()));
        return Stream.concat(Stream.concat(Stream.concat(links, wiki), definitions), fences);
    }

    private boolean insideWiki(MarkdownSemanticSnapshot.Location location) {
        if (location.segments().isEmpty()) return false;
        int start = location.segments().getFirst().start(), end = location.segments().getLast().end();
        var list = semantic.wikiLinks(); int low = 0, high = list.size();
        while (low < high) { int mid = (low + high) >>> 1; if (list.get(mid).range().start() <= start) low = mid + 1; else high = mid; }
        return low > 0 && list.get(low - 1).range().end() >= end;
    }

    private Occurrence link(MarkdownSemanticSnapshot.Link link) {
        Optional<String> label = Optional.empty(), rawDestination = Optional.empty(), reference = Optional.empty();
        ReferenceStatus status = ReferenceStatus.UNKNOWN;
        if (link.location().segments().size() == 1) {
            String raw = link.location().segments().getFirst().text(source().text());
            var inline = INLINE.matcher(raw); var ref = REFERENCE.matcher(raw);
            if (inline.matches()) {
                label = Optional.of(inline.group(1)); rawDestination = Optional.of(inline.group(2)); status = ReferenceStatus.NOT_REFERENCE;
            } else if (ref.matches()) {
                label = Optional.of(ref.group(1));
                reference = Optional.of(ref.group(2) == null || ref.group(2).isEmpty() ? ref.group(1) : ref.group(2));
                status = ReferenceStatus.RESOLVED; // This occurrence already exists as a resolved CommonMark Link/Image.
            }
        }
        return occurrence(link.kind() == MarkdownSemanticSnapshot.LinkKind.IMAGE ? Kind.IMAGE : Kind.LINK,
            link.location(), Optional.of(link.destination()), rawDestination, reference, label, status,
            Optional.empty(), Optional.empty(), Optional.empty());
    }

    private Occurrence occurrence(Kind kind, MarkdownSemanticSnapshot.Location location, Optional<String> destination,
            Optional<String> rawDestination, Optional<String> reference, Optional<String> label, ReferenceStatus status,
            Optional<String> language, Optional<MarkdownSourceRange> opening, Optional<MarkdownSourceRange> closing) {
        Optional<String> fragment = destination.flatMap(d -> {
            int hash = d.indexOf('#'); return hash < 0 ? Optional.empty() : Optional.of(d.substring(hash + 1));
        });
        return new Occurrence(source(), kind, location, destination, rawDestination, reference, label, fragment, status, language, opening, closing, Optional.empty());
    }
}
