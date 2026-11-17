package shed;

import static org.junit.jupiter.api.Assertions.*;
import static shed.MarkdownLink.*;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MarkdownLinkNavigationTest {
    @TempDir Path directory;
    Path file(String name, String text) throws Exception {
        Path path = directory.resolve(name); Files.createDirectories(path.getParent()); Files.writeString(path, text); return path.toRealPath();
    }
    MarkdownLinkIndex.Snapshot index() throws Exception { return MarkdownLinkIndex.build(directory, List.of(directory), null, List.of(), Set.of(), () -> false); }
    MarkdownLinkNavigation.Target target(MarkdownLinkIndex.Snapshot index, Edge edge, Path choice, boolean backlink) throws Exception {
        return MarkdownLinkNavigation.prepare(index, List.of(directory), edge, choice, backlink, List.of(), () -> false);
    }
    @Test void relativeWikiUnicodeAndFragmentTargetsUseVerifiedLocations() throws Exception {
        Path note = file("folder/你好.md", "# One\n\n## 标题\n"); Path source = file("source.md", "[a](folder/%E4%BD%A0%E5%A5%BD.md#标题) [[folder/你好#标题|Alias]]"); var index = index();
        for (var edge : index.outgoing(source).edges()) {
            var target = target(index, edge, null, false); assertEquals(note, target.identity()); assertEquals(7, target.offset());
            assertTrue(target.hash().isPresent()); assertTrue(target.diskVersion().isPresent());
        }
    }
    @Test void explicitAmbiguityChoiceIsRequiredAndPreservesHeading() throws Exception {
        Path a = file("a/note.md", "# Heading"); Path b = file("b/note.md", "\n# Heading"); Path source = file("source.md", "[[note#Heading]]"); var index = index(); var edge = index.outgoing(source).edges().getFirst();
        assertThrows(IllegalArgumentException.class, () -> target(index, edge, null, false));
        assertEquals(b, target(index, edge, b, false).identity()); assertEquals(1, target(index, edge, b, false).offset());
        assertThrows(IllegalArgumentException.class, () -> target(index, edge, directory.resolve("other.md"), false)); assertTrue(Files.exists(a));
    }
    @Test void backlinkUsesSourceRevisionAndRejectsChangedOrDeletedDiskSource() throws Exception {
        Path source = file("a.md", "line\n[[b]]"); file("b.md", ""); var index = index(); var edge = index.outgoing(source).edges().getFirst();
        assertEquals(5, target(index, edge, null, true).offset());
        Files.writeString(source, "[[b]]\nchanged"); assertThrows(IllegalArgumentException.class, () -> target(index, edge, null, true));
        Files.delete(source); assertThrows(Exception.class, () -> target(index, edge, null, true));
    }
    @Test void changedHeadingOrRenamedTargetCannotApplyOldOffset() throws Exception {
        Path note = file("note.md", "# Title"); Path source = file("source.md", "[x](note.md#title)"); var index = index(); var edge = index.outgoing(source).edges().getFirst();
        Files.writeString(note, "preface\n# Title"); assertThrows(IllegalArgumentException.class, () -> target(index, edge, null, false));
        Files.move(note, directory.resolve("renamed.md")); assertThrows(Exception.class, () -> target(index, edge, null, false));
    }
    @Test void missingUnsupportedAndAmbiguousHeadingsDoNotGuess() throws Exception {
        file("note.md", "# Same\n# Same"); file("math.md", "# $x$"); Path source = file("source.md", "[[note#Same]] [x](note.md#missing) [x](math.md#x) [[missing]] [x](../outside.md)"); var index = index();
        for (var edge : index.outgoing(source).edges()) assertThrows(IllegalArgumentException.class, () -> target(index, edge, null, false));
    }
    @Test void codeEscapesAndMalformedLinksHaveNoCaretOccurrence() throws Exception {
        String text = "\\[[escaped]] `[[inline]]`\n```\n[x](note.md)\n[[fenced]]\n```\n[[real]]";
        Path source = file("source.md", text); file("real.md", ""); var query = index().outgoing(source);
        for (String inactive : List.of("escaped", "inline", "[x]", "fenced")) assertTrue(MarkdownLinkNavigation.at(query, text.indexOf(inactive)).isEmpty());
        var edge = MarkdownLinkNavigation.at(query, text.indexOf("real")).orElseThrow(); assertEquals(Kind.WIKI, edge.occurrence().kind());
        assertTrue(MarkdownLinkNavigation.at(query, edge.occurrence().ranges().getLast().end()).isEmpty());
    }
    @Test void externalLinksAreClassifiedButNeverOpenedByWorker() throws Exception {
        Path source = file("a.md", "[x](https://example.invalid)"); var index = index(); var edge = index.outgoing(source).edges().getFirst();
        assertEquals(Status.EXTERNAL, edge.resolution().status()); MarkdownLinkNavigation.verifySource(index, List.of(directory), edge, List.of(), () -> false);
        assertThrows(IllegalArgumentException.class, () -> target(index, edge, null, false));
    }
    @Test void sourceAndTargetSymlinkBoundariesAreRechecked() throws Exception {
        Path note = file("note.md", "# Title"); Path alias = directory.resolve("alias.md"); Files.createSymbolicLink(alias, note);
        Path source = file("source.md", "[x](alias.md#title)"); var index = index(); var edge = index.outgoing(source).edges().getFirst();
        Path outside = Files.createTempFile("shed-nav-outside", ".md");
        try {
            Files.delete(alias); Files.createSymbolicLink(alias, outside);
            assertThrows(IllegalArgumentException.class, () -> target(index, edge, null, false));
            Files.delete(alias); Files.createSymbolicLink(alias, note);
            Files.delete(note); Files.createSymbolicLink(note, outside); assertThrows(IllegalArgumentException.class, () -> target(index, edge, null, false));
        }
        finally { Files.deleteIfExists(outside); }
    }
    @Test void pendingAndPartialFeedbackCannotClaimCompleteEmptyResults() {
        var empty = List.<Edge>of();
        assertEquals("No Markdown backlinks", MarkdownLinkNavigation.feedback(new Query(State.COMPLETE, empty, List.of(), ""), true));
        assertTrue(MarkdownLinkNavigation.feedback(new Query(State.PENDING, empty, List.of(), ""), true).contains("not complete"));
        assertTrue(MarkdownLinkNavigation.feedback(new Query(State.PARTIAL, empty, List.of(directory), ""), true).contains("partial"));
        for (State state : List.of(State.FAILED, State.STALE, State.UNSUPPORTED, State.CANCELLED))
            assertFalse(MarkdownLinkNavigation.feedback(new Query(state, empty, List.of(), ""), false).startsWith("No Markdown"));
    }
    @Test void rowsAreBoundedOrderedAndSafeForExistingPlainRenderer() throws Exception {
        Path source = file("source.md", ("[[note|Alias]] ").repeat(205)); file("note.md", ""); var query = index().outgoing(source);
        var rows = MarkdownLinkNavigation.rows(directory.toRealPath(), query, false); assertTrue(rows.limited()); assertEquals(200, rows.rows().size());
        assertEquals(200, rows.rows().stream().map(MarkdownLinkNavigation.Row::label).distinct().count());
        assertTrue(rows.rows().stream().noneMatch(row -> row.label().startsWith("<html>")));
        assertTrue(rows.rows().getFirst().label().contains(":1:1"));
    }
    @Test void unsavedSourceAndHeadingTargetUseCapturedBufferRevisions() throws Exception {
        Path source = file("a.md", ""); Path note = file("note.md", "# old");
        var a = new MarkdownSemanticSnapshot.Source(new DocumentWorkspace.Id(1), 3, VersionedTextSnapshot.of("[[note#New]]"));
        var b = new MarkdownSemanticSnapshot.Source(new DocumentWorkspace.Id(2), 8, VersionedTextSnapshot.of("text\n# New"));
        var overlays = List.of(new MarkdownLinkIndex.Overlay(source, a, Optional.empty()), new MarkdownLinkIndex.Overlay(note, b, Optional.empty()));
        var index = MarkdownLinkIndex.build(directory, List.of(directory), null, overlays, Set.of(), () -> false); var edge = index.outgoing(source).edges().getFirst();
        var open = List.of(new MarkdownLinkNavigation.Open(source, a, true), new MarkdownLinkNavigation.Open(note, b, true));
        var result = MarkdownLinkNavigation.prepare(index, List.of(directory), edge, null, false, open, () -> false);
        assertEquals(5, result.offset()); assertEquals(b.documentId(), result.document().orElseThrow()); assertSame(b.text(), result.snapshot().get());
        var changed = new MarkdownSemanticSnapshot.Source(a.documentId(), 4, VersionedTextSnapshot.of("removed"));
        assertThrows(IllegalArgumentException.class, () -> MarkdownLinkNavigation.prepare(index, List.of(directory), edge, null, false,
            List.of(new MarkdownLinkNavigation.Open(source, changed, true)), () -> false));
    }
    @Test void validationIsReadOnlyCancellableAndNotAllowedOnEdt() throws Exception {
        Path source = file("a.md", "[[b]]"); Path note = file("b.md", ""); byte[] before = Files.readAllBytes(source); var index = index(); var edge = index.outgoing(source).edges().getFirst();
        target(index, edge, null, true); assertArrayEquals(before, Files.readAllBytes(source)); assertTrue(Files.exists(note));
        assertThrows(java.util.concurrent.CancellationException.class, () -> MarkdownLinkNavigation.prepare(index, List.of(directory), edge, null, true, List.of(), () -> true));
        javax.swing.SwingUtilities.invokeAndWait(() -> assertThrows(IllegalStateException.class, () -> target(index, edge, null, false)));
    }
    @Test void linkedImagesPreferTheirVerifiedHyperlinkAndDuplicateMatchesAreRejected() throws Exception {
        Path source = file("source.md", "[![alt](image.png)](note.md)"); file("note.md", ""); file("image.png", "fake");
        var query = index().outgoing(source); var selected = MarkdownLinkNavigation.at(query, 5).orElseThrow(); assertEquals(Kind.MARKDOWN, selected.occurrence().kind());
        var duplicate = new Query(State.COMPLETE, List.of(selected, selected), List.of(), "test"); assertTrue(MarkdownLinkNavigation.at(duplicate, 5).isEmpty());
    }
}
