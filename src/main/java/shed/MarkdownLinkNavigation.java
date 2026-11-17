package shed;

import java.lang.ref.WeakReference;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.function.BooleanSupplier;
import static shed.MarkdownLink.*;

/** Read-only worker validation for existing navigation. No picker, editor, cache or document ownership. */
final class MarkdownLinkNavigation {
    static final int MAX_ROWS = 200;
    record Open(Path path, DocumentWorkspace.Id document, long revision, WeakReference<VersionedTextSnapshot> snapshot,
                boolean complete, boolean dirty) {
        Open(Path path, MarkdownSemanticSnapshot.Source source, boolean dirty) {
            this(path, source.documentId(), source.revision(), new WeakReference<>(source.text()), source.completeSource(), dirty);
        }
        MarkdownSemanticSnapshot.Source source() {
            var text = snapshot.get();
            if (text == null) throw new IllegalArgumentException("Markdown buffer snapshot released; request again");
            return new MarkdownSemanticSnapshot.Source(document, revision, text, complete);
        }
    }
    record Row(String label, Edge edge) { }
    record Rows(List<Row> rows, boolean limited) { Rows { rows = List.copyOf(rows); } }
    record Target(Path identity, Path openPath, Optional<DocumentWorkspace.Id> document, long revision,
                  WeakReference<VersionedTextSnapshot> snapshot, Optional<String> hash, Optional<String> diskVersion,
                  int offset, List<Path> roots, Path root) {
        Target { roots = List.copyOf(roots); }
        boolean accepts(DocumentWorkspace.Id id, FileBuffer buffer) {
            return document.isPresent() && document.orElseThrow().equals(id) && revision == buffer.documentRevision() && snapshot.get() == buffer.textSnapshot();
        }
        boolean loaded(FileBuffer buffer, BooleanSupplier stop) {
            try {
                Path actual = canonical(openPath, root, roots);
                if (!actual.equals(identity)) return false;
                if (hash.isEmpty()) return true;
                if (buffer.isLargeFile() || !hash.orElseThrow().equals(MarkdownLinkNavigation.hash(buffer.textSnapshot(), stop))) return false;
                var disk = WorkspaceTextFile.capture(root.toString(), identity, false, 4 * 1024 * 1024, stop).document();
                return diskVersion.isPresent() && diskVersion.orElseThrow().equals(disk.version());
            } catch (RuntimeException | java.io.IOException error) { return false; }
        }
    }

    static Optional<Edge> at(Query query, int caret) {
        var matches = query.edges().stream().filter(e -> e.occurrence().ranges().stream().anyMatch(r -> caret >= r.start() && caret < r.end())).toList();
        var hyperlinks = matches.stream().filter(edge -> edge.occurrence().kind() != Kind.IMAGE).toList();
        if (!hyperlinks.isEmpty()) matches = hyperlinks;
        return matches.size() == 1 ? Optional.of(matches.getFirst()) : Optional.empty();
    }
    static Rows rows(Path root, Query query, boolean backlinks) {
        var rows = new ArrayList<Row>();
        for (Edge edge : query.edges()) {
            if (rows.size() == MAX_ROWS) break;
            var occurrence = edge.occurrence();
            String location = root.relativize(occurrence.source().file()).toString();
            String label = backlinks ? location : occurrence.alias().filter(a -> !a.isBlank()).map(a -> text(a, 60) + " → ").orElse("") + occurrence.destination();
            label = occurrence.kind().name().toLowerCase(Locale.ROOT) + " · " + text(label, 180) + "  [" + occurrence.kind().name().toLowerCase(Locale.ROOT) + ", "
                + edge.resolution().status().name().toLowerCase(Locale.ROOT).replace('_', ' ') + "]  "
                + (backlinks ? text(occurrence.destination(), 100) : text(location, 100)) + (occurrence.line() > 0 ? ":" + occurrence.line() + ":" + occurrence.column() : " (offset " + occurrence.offset() + ")") + "  [#" + (rows.size() + 1) + "]";
            rows.add(new Row(label, edge));
        }
        return new Rows(rows, query.edges().size() > MAX_ROWS);
    }
    static String text(String value, int cap) {
        String clean = value.replaceAll("[\\p{Cntrl}]", " ");
        if (clean.length() <= cap) return clean;
        int end = cap; if (Character.isHighSurrogate(clean.charAt(end - 1))) end--;
        return clean.substring(0, end) + "…";
    }
    static String feedback(Query query, boolean incoming) {
        String noun = incoming ? "backlinks" : "outgoing links";
        return switch (query.state()) {
            case COMPLETE -> query.edges().isEmpty() ? "No Markdown " + noun : "Markdown " + noun;
            case PARTIAL -> "Markdown " + noun + " (partial; " + query.incompleteFiles().size() + " known incomplete files; coverage limits may omit others)";
            case PENDING -> "Markdown link index is building; results are not complete";
            case CANCELLED -> "Markdown link indexing cancelled; request again";
            case FAILED -> "Markdown link indexing failed; request again";
            default -> "Markdown links unavailable: " + query.state().name().toLowerCase(Locale.ROOT);
        };
    }
    static Target prepare(MarkdownLinkIndex.Snapshot index, List<Path> roots, Edge edge, Path candidate,
                          boolean backlink, List<Open> open, BooleanSupplier stop) throws Exception {
        worker(); check(stop);
        var source = edge.occurrence().source();
        boolean belongs = index.outgoing(source.file()).edges().contains(edge);
        if (!belongs) throw new IllegalArgumentException("Markdown links changed; request again");
        verifyOrigin(index.root(), roots, source, open, stop);
        Resolution resolution = edge.resolution();
        if (!backlink && candidate != null) {
            var nested = roots.stream().map(WorkspaceRootResolver::normalizedIdentity).filter(p -> !p.equals(index.root()) && p.startsWith(index.root())).toList();
            resolution = new MarkdownLinkResolver(index.root(), nested, index.effective(), index.state() == State.COMPLETE).choose(edge.occurrence(), candidate);
        }
        if (!backlink && resolution.status() != Status.RESOLVED) throw new IllegalArgumentException("Markdown link: " + resolution.status().name().toLowerCase(Locale.ROOT).replace('_', ' '));
        Path target = backlink ? source.file() : resolution.target().orElseThrow();
        target = canonical(target, index.root(), roots);
        int offset = backlink ? edge.occurrence().offset() : resolution.heading().map(Heading::offset).orElse(-1);
        Facts facts = index.effective().get(target);
        Origin expected = backlink ? source : facts == null ? null : facts.origin();
        var matches = new ArrayList<Open>();
        for (Open item : open) if (WorkspaceRootResolver.normalizedIdentity(item.path()).equals(target)) matches.add(item);
        if (matches.size() > 1) throw new IllegalArgumentException("Multiple open buffers identify this target");
        Open existing = matches.isEmpty() ? null : matches.getFirst();
        Optional<String> contentHash = Optional.empty(), diskVersion = Optional.empty();
        if (offset >= 0) {
            if (expected == null) throw new IllegalArgumentException("Markdown target revision unavailable");
            var verified = verifyOrigin(index.root(), roots, expected, open, stop);
            new MarkdownSourceRange(offset, offset).validate(verified);
            contentHash = Optional.of(hash(verified, stop));
            if (existing == null) diskVersion = Optional.of(expected.version());
        }
        check(stop);
        return new Target(target, existing == null ? index.requestedRoot().resolve(index.root().relativize(target)) : existing.path(), existing == null ? Optional.empty() : Optional.of(existing.source().documentId()),
            existing == null ? -1 : existing.source().revision(), new WeakReference<>(existing == null ? null : existing.source().text()),
            contentHash, diskVersion, offset, roots, index.root());
    }
    static void verifySource(MarkdownLinkIndex.Snapshot index, List<Path> roots, Edge edge, List<Open> open, BooleanSupplier stop) throws Exception {
        worker(); verifyOrigin(index.root(), roots, edge.occurrence().source(), open, stop);
    }
    static VersionedTextSnapshot verifyOrigin(Path root, List<Path> roots, Origin origin, List<Open> open, BooleanSupplier stop) throws Exception {
        canonical(origin.file(), root, roots); check(stop);
        Open match = null;
        for (Open item : open) if (WorkspaceRootResolver.normalizedIdentity(item.path()).equals(origin.file())) {
            if (match != null) throw new IllegalArgumentException("Multiple buffers identify a Markdown source"); match = item;
        }
        if (origin.document().isPresent()) {
            if (match == null || !origin.document().orElseThrow().equals(match.source().documentId())
                || !origin.version().equals("buffer-" + match.source().documentId().value() + "-" + match.source().revision()))
                throw new IllegalArgumentException("Markdown source revision changed; request again");
            if (!match.source().completeSource()) throw new IllegalArgumentException("Markdown source is incomplete");
            return match.source().text();
        }
        var disk = WorkspaceTextFile.capture(root.toString(), origin.file(), false, 4 * 1024 * 1024, stop).document();
        if (!origin.version().equals(disk.version())) throw new IllegalArgumentException("Markdown source changed on disk; refresh links");
        var text = VersionedTextSnapshot.of(disk.content());
        if (match != null && (!match.source().completeSource() || !hash(text, stop).equals(hash(match.source().text(), stop))))
            throw new IllegalArgumentException("Markdown source has newer buffer content; refresh links");
        return match == null ? text : match.source().text();
    }
    private static Path canonical(Path path, Path root, List<Path> roots) throws java.io.IOException {
        Path real = path.toRealPath();
        Path owner = WorkspaceRootResolver.configuredRoot(real, roots);
        if (!real.startsWith(root) || owner == null || !WorkspaceRootResolver.normalizedIdentity(owner).equals(root) || !Files.isRegularFile(real))
            throw new IllegalArgumentException("Markdown target is outside the owning project or unavailable");
        return real;
    }
    static String hash(VersionedTextSnapshot text, BooleanSupplier stop) {
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            for (int at = 0; at < text.length(); at += 8192) {
                check(stop); String part = text.substring(at, Math.min(8192, text.length() - at));
                for (int i = 0; i < part.length(); i++) { char c = part.charAt(i); digest.update((byte) (c >>> 8)); digest.update((byte) c); }
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
    private static void worker() { if (javax.swing.SwingUtilities.isEventDispatchThread()) throw new IllegalStateException("Validate navigation off EDT"); }
    private static void check(BooleanSupplier stop) { if (stop.getAsBoolean() || Thread.currentThread().isInterrupted()) throw new java.util.concurrent.CancellationException(); }
}
