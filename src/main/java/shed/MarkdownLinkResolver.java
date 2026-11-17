package shed;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import static shed.MarkdownLink.*;

/** Worker-only filesystem resolver. Queries use its immutable results without filesystem access. */
final class MarkdownLinkResolver {
    private final Path root;
    private final List<Path> nested;
    private final Map<Path, Facts> catalog;
    private final boolean complete;
    private final Map<String, List<Path>> basenames = new HashMap<>();
    private final Map<Key, Resolution> paths = new HashMap<>();
    private record Key(Path source, boolean wiki, String path) { }

    MarkdownLinkResolver(Path root, List<Path> nested, Map<Path, Facts> catalog, boolean complete) {
        if (javax.swing.SwingUtilities.isEventDispatchThread()) throw new IllegalStateException("Resolve off EDT");
        this.root = root; this.nested = List.copyOf(nested); this.catalog = catalog; this.complete = complete;
        for (Path path : catalog.keySet()) basenames.computeIfAbsent(nameKey(path.getFileName().toString()), ignored -> new ArrayList<>()).add(path);
    }

    Resolution resolve(Occurrence occurrence) {
        if (javax.swing.SwingUtilities.isEventDispatchThread()) throw new IllegalStateException("Resolve off EDT");
        String destination = occurrence.destination(); boolean wiki = occurrence.kind() == Kind.WIKI;
        int hash = destination.indexOf('#'); String rawPath = hash < 0 ? destination : destination.substring(0, hash);
        String fragment = hash < 0 ? null : destination.substring(hash + 1);
        if (!wiki && (destination.startsWith("//") || destination.matches("^[a-zA-Z][a-zA-Z0-9+.-]*:.*")))
            return Resolution.of(destination.matches("(?i)^(https?|mailto|ftp):.*") || destination.startsWith("//") ? Status.EXTERNAL : Status.UNSUPPORTED, List.of());
        try {
            if (!wiki) {
                URI uri = new URI(rawPath.replace(" ", "%20"));
                if (uri.getRawQuery() != null || uri.getAuthority() != null) return Resolution.of(Status.UNSUPPORTED, List.of());
                rawPath = decode(uri.getRawPath());
                if (fragment != null) fragment = decode(new URI("#" + fragment).getRawFragment());
            }
            if (rawPath == null || rawPath.indexOf('\0') >= 0 || rawPath.indexOf('\\') >= 0 || rawPath.length() > 4096)
                return Resolution.of(Status.UNSUPPORTED, List.of());
            Key key = new Key(occurrence.source().file(), wiki, rawPath);
            Resolution path = paths.get(key);
            if (path == null) { path = resolvePath(key); paths.put(key, path); }
            if (path.status() != Status.RESOLVED) return path;
            Path target = path.candidates().getFirst(); Facts facts = catalog.get(target);
            if (fragment == null || fragment.isEmpty()) return new Resolution(Status.RESOLVED, path.candidates(), Optional.empty(),
                facts == null ? Optional.empty() : Optional.of(facts.origin().version()));
            if (facts == null) return Resolution.of(Status.HEADING_UNSUPPORTED, path.candidates());
            if (facts.state() != State.COMPLETE) return new Resolution(Status.HEADING_PENDING, path.candidates(), Optional.empty(), Optional.of(facts.origin().version()));
            String wanted = fragment;
            var headings = wiki ? facts.headings().stream().filter(h -> h.text().equals(wanted)).toList() : List.<Heading>of();
            if (headings.isEmpty()) headings = facts.headings().stream().filter(h -> h.anchor().equals(wanted)).toList();
            if (headings.size() != 1) return new Resolution(headings.isEmpty() ? Status.MISSING_HEADING : Status.AMBIGUOUS_HEADING, path.candidates(), Optional.empty(), Optional.of(facts.origin().version()));
            return new Resolution(Status.RESOLVED, path.candidates(), Optional.of(headings.getFirst()), Optional.of(facts.origin().version()));
        } catch (java.net.URISyntaxException | java.nio.charset.CharacterCodingException | IllegalArgumentException error) { return Resolution.of(Status.UNSUPPORTED, List.of()); }
    }

    private Resolution resolvePath(Key key) {
        if (key.path().isEmpty()) return eligible(key.source());
        Path path = Path.of(key.path());
        if (path.isAbsolute()) return Resolution.of(Status.OUT_OF_SCOPE, List.of());
        if (key.wiki() && path.getNameCount() == 1 && !key.path().startsWith(".")) {
            var candidates = new TreeSet<Path>();
            for (Path candidate : basenames.getOrDefault(nameKey(key.path()), List.of())) {
                String name = candidate.getFileName().toString();
                int dot = name.lastIndexOf('.'); String extension = dot < 0 ? "" : name.substring(dot);
                String requested = key.path();
                if (!ProjectFileScanner.markdown(path)) requested += extension;
                try {
                    if (Files.isSameFile(candidate, candidate.getParent().resolve(requested))) candidates.add(candidate);
                } catch (IOException | SecurityException ignored) { }
            }
            if (candidates.size() > 1) return Resolution.of(Status.AMBIGUOUS, List.copyOf(candidates));
            if (!complete) return Resolution.of(Status.PENDING, List.copyOf(candidates));
            return candidates.isEmpty() ? Resolution.of(Status.MISSING, List.of()) : eligible(candidates.first());
        }
        Path base = key.wiki() && !key.path().startsWith("./") && !key.path().startsWith("../") ? root : key.source().getParent();
        Path target = base.resolve(path).normalize();
        if (!target.startsWith(root) || outsideNested(target)) return Resolution.of(Status.OUT_OF_SCOPE, List.of());
        if (key.wiki() && !ProjectFileScanner.markdown(target)) {
            var candidates = new ArrayList<Path>();
            for (String extension : List.of(".md", ".markdown")) {
                Path candidate = Path.of(target + extension);
                if (Files.exists(candidate)) candidates.add(candidate);
            }
            if (candidates.size() > 1) return Resolution.of(Status.AMBIGUOUS, candidates);
            if (candidates.size() == 1) target = candidates.getFirst();
        }
        return eligible(target);
    }

    private static String decode(String raw) throws java.nio.charset.CharacterCodingException {
        if (raw == null) return null;
        var value = new StringBuilder();
        for (int at = 0; at < raw.length();) {
            if (raw.charAt(at) != '%') { value.append(raw.charAt(at++)); continue; }
            var bytes = new java.io.ByteArrayOutputStream();
            while (at < raw.length() && raw.charAt(at) == '%') {
                bytes.write(Integer.parseInt(raw.substring(at + 1, at + 3), 16)); at += 3;
            }
            value.append(java.nio.charset.StandardCharsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(bytes.toByteArray())));
        }
        return value.toString();
    }

    private static String nameKey(String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".markdown")) name = name.substring(0, name.length() - 9);
        else if (lower.endsWith(".md")) name = name.substring(0, name.length() - 3);
        return java.text.Normalizer.normalize(name, java.text.Normalizer.Form.NFC).toLowerCase(Locale.ROOT);
    }

    private boolean outsideNested(Path target) { return nested.stream().anyMatch(target::startsWith); }

    private Resolution eligible(Path target) {
        try {
            Path identity = target.toRealPath();
            if (!identity.startsWith(root) || nested.stream().anyMatch(identity::startsWith)) return Resolution.of(Status.OUT_OF_SCOPE, List.of());
            if (!Files.isRegularFile(identity)) return Resolution.of(Status.UNSUPPORTED, List.of());
            if (catalog.containsKey(identity) && catalog.get(identity).state() == State.CONFLICT) return Resolution.of(Status.UNSUPPORTED, List.of(identity));
            return Resolution.of(Status.RESOLVED, List.of(identity));
        } catch (java.nio.file.NoSuchFileException error) { return Resolution.of(Status.MISSING, List.of()); }
        catch (IOException | SecurityException error) { return Resolution.of(Status.UNSUPPORTED, List.of()); }
    }
}
