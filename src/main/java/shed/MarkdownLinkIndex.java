package shed;

import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;
import java.util.function.BooleanSupplier;
import static shed.MarkdownLink.*;

/** Disposable project partition. Building is worker-only; installed snapshots contain compact facts only. */
final class MarkdownLinkIndex {
    static final int MAX_RECORDS = 16384;
    static final int MAX_RETAINED_UNITS = 1024 * 1024;
    static final int MAX_PASS_UNITS = 16 * 1024 * 1024;
    record Overlay(Path path, MarkdownSemanticSnapshot.Source source, Optional<MarkdownSemanticSnapshot> available) {
        Overlay { path = path.toAbsolutePath().normalize(); }
    }
    record Stamp(long size, java.nio.file.attribute.FileTime modified, Object key) { }
    record Saved(Stamp stamp, Facts facts) { }
    record Metrics(int parsed, int reused, int visited, long workerNanos, int records, long retainedUnits) { }
    record Snapshot(Path root, Path requestedRoot, Map<Path, Saved> saved, Map<Path, Facts> effective,
                    Map<Path, List<Edge>> outgoing, Map<Path, List<Edge>> backlinks,
                    Map<DocumentWorkspace.Id, java.lang.ref.WeakReference<VersionedTextSnapshot>> overlaySnapshots,
                    State state, List<Path> incomplete, String detail, Metrics metrics) {
        Snapshot {
            overlaySnapshots = Map.copyOf(overlaySnapshots); saved = Map.copyOf(saved); effective = Map.copyOf(effective); outgoing = Map.copyOf(outgoing); backlinks = Map.copyOf(backlinks);
            incomplete = List.copyOf(incomplete);
        }
        private Path identity(Path path) {
            path = path.toAbsolutePath().normalize();
            return path.startsWith(requestedRoot) ? root.resolve(requestedRoot.relativize(path)) : path;
        }
        Query outgoing(Path path) { return new Query(state, outgoing.getOrDefault(identity(path), List.of()), incomplete, detail); }
        Query backlinks(Path path) { return new Query(state, backlinks.getOrDefault(identity(path), List.of()), incomplete, detail); }
    }

    static Snapshot build(Path requestedRoot, List<Path> configuredRoots, Snapshot previous, List<Overlay> overlays,
                          Set<Path> forceDisk, BooleanSupplier cancelled) throws java.io.IOException {
        if (javax.swing.SwingUtilities.isEventDispatchThread()) throw new IllegalStateException("Index off EDT");
        check(cancelled); long start = System.nanoTime(); Path root = requestedRoot.toRealPath();
        var nested = new ArrayList<Path>();
        for (Path configured : configuredRoots) {
            Path identity = WorkspaceRootResolver.normalizedIdentity(configured);
            if (!identity.equals(root) && identity.startsWith(root)) nested.add(identity);
        }
        var forceIdentities = new HashSet<Path>();
        for (Path forced : forceDisk) forceIdentities.add(WorkspaceRootResolver.normalizedIdentity(forced));
        var scan = new ProjectFileScanner().markdown(root, nested, cancelled::getAsBoolean);
        check(cancelled);
        var saved = new TreeMap<Path, Saved>(); var effective = new TreeMap<Path, Facts>();
        int parsed = 0, reused = 0, units = 0;
        var physical = new HashMap<Object, Path>(); var conflicts = new HashSet<Path>();
        for (Path file : scan.files()) {
            check(cancelled);
            var origin = new Origin(file, Optional.empty(), "unread");
            try {
                var attributes = Files.readAttributes(file, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
                if (attributes.fileKey() != null) {
                    Path duplicate = physical.putIfAbsent(attributes.fileKey(), file);
                    if (duplicate != null) { conflicts.add(duplicate); conflicts.add(file); }
                }
                Stamp stamp = new Stamp(attributes.size(), attributes.lastModifiedTime(), attributes.fileKey());
                Saved old = previous == null ? null : previous.saved().get(file);
                Facts facts;
                if (!forceIdentities.contains(file) && old != null && stamp.equals(old.stamp()) && old.facts().state() != State.PENDING && old.facts().state() != State.CANCELLED && old.facts().state() != State.CONFLICT
                        && !old.facts().detail().equals("Project retention budget")) {
                    facts = old.facts(); reused++;
                } else if (units >= MAX_PASS_UNITS) facts = Facts.unavailable(origin, State.PENDING, "Per-pass source budget; refresh to continue");
                else {
                    var disk = WorkspaceTextFile.capture(root.toString(), file, false, 4 * 1024 * 1024, cancelled);
                    String text = disk.document().content();
                    origin = new Origin(file, Optional.empty(), disk.document().version());
                    units += Math.min(text.length(), MarkdownSemanticParser.MAX_CHARACTERS);
                    facts = project(origin, new MarkdownSemanticSnapshot.Source(new DocumentWorkspace.Id(0), 0, VersionedTextSnapshot.of(text)), cancelled);
                    parsed++;
                }
                saved.put(file, new Saved(stamp, facts)); effective.put(file, facts);
            } catch (WorkspaceApiException error) {
                check(cancelled);
                var facts = Facts.unavailable(origin, error.code() == WorkspaceApiException.Code.LIMIT_EXCEEDED || error.code() == WorkspaceApiException.Code.UNSUPPORTED
                    ? State.UNSUPPORTED : error.code() == WorkspaceApiException.Code.STALE_VERSION ? State.STALE : State.FAILED, error.getMessage());
                effective.put(file, facts); saved.put(file, new Saved(null, facts));
            } catch (java.io.IOException | SecurityException error) { effective.put(file, Facts.unavailable(origin, State.FAILED, "File changed or inaccessible")); }
        }
        // Conflicting physical aliases have different relative-link bases. Do not choose an arbitrary contribution.
        for (Path conflict : conflicts) {
            var facts = Facts.unavailable(effective.get(conflict).origin(), State.CONFLICT, "Multiple paths identify the same physical file");
            effective.put(conflict, facts); saved.put(conflict, new Saved(saved.get(conflict).stamp(), facts));
        }
        var overlaySnapshots = new HashMap<DocumentWorkspace.Id, java.lang.ref.WeakReference<VersionedTextSnapshot>>();
        var overlayIds = new HashMap<Path, DocumentWorkspace.Id>();
        boolean overlayLimit = false;
        for (Overlay overlay : overlays) {
            check(cancelled);
            Path file = WorkspaceRootResolver.normalizedIdentity(overlay.path());
            if (!file.startsWith(root) || nested.stream().anyMatch(file::startsWith) || !ProjectFileScanner.markdown(file)) continue;
            if (effective.size() >= 1024 && !effective.containsKey(file)) { overlayLimit = true; continue; }
            var source = overlay.source();
            overlaySnapshots.put(source.documentId(), new java.lang.ref.WeakReference<>(source.text()));
            var origin = new Origin(file, Optional.of(source.documentId()), "buffer-" + source.documentId().value() + "-" + source.revision());
            var priorId = overlayIds.putIfAbsent(file, source.documentId());
            if (priorId != null) {
                if (!priorId.equals(source.documentId())) effective.put(file, Facts.unavailable(origin, State.CONFLICT, "Multiple authoritative dirty buffers"));
                continue;
            }
            Facts old = previous == null ? null : previous.effective().get(file);
            if (old != null && old.origin().equals(origin) && previous.overlaySnapshots().containsKey(source.documentId())
                    && previous.overlaySnapshots().get(source.documentId()).get() == source.text() && !old.detail().equals("Project retention budget")) { effective.put(file, old); reused++; continue; }
            Facts facts;
            if (units >= MAX_PASS_UNITS) facts = Facts.unavailable(origin, State.PENDING, "Per-pass source budget");
            else {
                units += Math.min(source.text().length(), MarkdownSemanticParser.MAX_CHARACTERS);
                var available = overlay.available().filter(s -> s.isCurrent(source));
                facts = available.isPresent() ? MarkdownLink.project(origin, available.orElseThrow()) : project(origin, source, cancelled);
                if (available.isPresent()) reused++; else parsed++;
            }
            effective.put(file, facts);
        }
        int records = 0; long retained = 0;
        // Bound saved + effective separately; saved facts are needed to restore discarded overlays.
        var admitted = Collections.newSetFromMap(new IdentityHashMap<Facts, Boolean>());
        var denied = Collections.newSetFromMap(new IdentityHashMap<Facts, Boolean>());
        for (Facts facts : java.util.stream.Stream.concat(saved.values().stream().map(Saved::facts), effective.values().stream()).toList()) {
            if (!admitted.add(facts)) continue;
            int count = facts.links().size() + facts.headings().size(); long size = units(facts);
            if (records + count > MAX_RECORDS || retained + size > MAX_RETAINED_UNITS) { denied.add(facts); continue; }
            records += count; retained += size;
        }
        saved.replaceAll((file, contribution) -> new Saved(contribution.stamp(), denied.contains(contribution.facts())
            ? Facts.unavailable(contribution.facts().origin(), State.PARTIAL, "Project retention budget") : contribution.facts()));
        effective.replaceAll((file, facts) -> denied.contains(facts) ? Facts.unavailable(facts.origin(), State.PARTIAL, "Project retention budget") : facts);
        var resolver = new MarkdownLinkResolver(root, nested, effective, scan.complete() && !overlayLimit);
        var outgoing = new TreeMap<Path, List<Edge>>(); var incoming = new TreeMap<Path, List<Edge>>();
        for (var entry : effective.entrySet()) {
            check(cancelled); var edges = new ArrayList<Edge>();
            for (Occurrence link : entry.getValue().links()) {
                check(cancelled); Edge edge = new Edge(link, resolver.resolve(link)); edges.add(edge);
                if (link.kind() != Kind.DEFINITION) edge.resolution().target().ifPresent(target -> incoming.computeIfAbsent(target, ignored -> new ArrayList<>()).add(edge));
            }
            outgoing.put(entry.getKey(), List.copyOf(edges));
        }
        incoming.replaceAll((path, edges) -> List.copyOf(edges));
        var incomplete = effective.entrySet().stream().filter(e -> e.getValue().state() != State.COMPLETE).map(Map.Entry::getKey).toList();
        boolean complete = scan.complete() && !overlayLimit && incomplete.isEmpty();
        return new Snapshot(root, requestedRoot.toAbsolutePath().normalize(), saved, effective, outgoing, incoming, overlaySnapshots, complete ? State.COMPLETE : State.PARTIAL, incomplete,
            "Discovery: " + scan.visited() + " entries, limited=" + scan.limited() + ", unreadable=" + scan.failures() + "; overlayLimit=" + overlayLimit,
            new Metrics(parsed, reused, scan.visited(), System.nanoTime() - start, records, retained));
    }
    private static Facts project(Origin origin, MarkdownSemanticSnapshot.Source source, BooleanSupplier cancelled) {
        // Preserve 2B's dense-input admission without running its legacy fallback scan.
        if (source.text().length() > 256 * 1024 && source.text().length() <= MarkdownSemanticParser.MAX_CHARACTERS) {
            int markers = 0;
            for (int at = 0; at < source.text().length(); at++) {
                if ((at & 8191) == 0) check(cancelled);
                char c = source.text().charAt(at);
                if ((c == '*' || c == '_' || c == '[' || c == ']' || c == '|' || c == '#') && ++markers > 32768)
                    return Facts.unavailable(origin, State.UNSUPPORTED, "Shared semantic-consumer dense-marker admission");
            }
        }
        return MarkdownLink.project(origin, new MarkdownSemanticParser().parse(source, cancelled));
    }
    private static long units(Facts facts) {
        long size = facts.origin().file().toString().length() + facts.origin().version().length();
        for (var link : facts.links()) size += link.destination().length() + link.rawDestination().map(String::length).orElse(0)
            + link.alias().map(String::length).orElse(0) + link.reference().map(String::length).orElse(0) + link.fragment().map(String::length).orElse(0);
        for (var heading : facts.headings()) size += heading.text().length() + heading.anchor().length();
        return size;
    }
    private static void check(BooleanSupplier cancelled) {
        if (cancelled.getAsBoolean() || Thread.currentThread().isInterrupted()) throw new java.util.concurrent.CancellationException("Markdown index superseded");
    }
}
