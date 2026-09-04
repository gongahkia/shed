package shed;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.jediterm.terminal.model.hyperlinks.LinkResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class TerminalLinkResolverTest {
    @Test
    void resolvesExistingRelativeSourceLocationsAndHttpUrls() throws Exception {
        Path root = Files.createTempDirectory("shed-terminal-link-");
        Path source = root.resolve("src/Main.java");
        Files.createDirectories(source.getParent());
        Files.writeString(source, "class Main {}\n");
        List<TerminalLinkResolver.Link> opened = new ArrayList<>();

        LinkResult result = TerminalLinkResolver.resolve("src/Main.java:12:4 failed; see https://example.com/build/7.", () -> root, opened::add);

        assertEquals(2, result.getItems().size());
        result.getItems().forEach(item -> item.getLinkInfo().navigate());
        TerminalLinkResolver.SourceLink sourceLink = assertInstanceOf(TerminalLinkResolver.SourceLink.class, opened.getFirst());
        assertEquals(source.toRealPath(), sourceLink.path());
        assertEquals(12, sourceLink.line());
        assertEquals(4, sourceLink.column());
        TerminalLinkResolver.BrowserLink browserLink = assertInstanceOf(TerminalLinkResolver.BrowserLink.class, opened.get(1));
        assertEquals("https://example.com/build/7", browserLink.uri().toString());
    }

    @Test
    void doesNotLinkMissingLocationsOrMalformedUrls() throws Exception {
        Path root = Files.createTempDirectory("shed-terminal-link-");
        assertNull(TerminalLinkResolver.resolve("missing/Thing.java:2:1 and https://", () -> root, ignored -> { }));
    }

    @Test
    void resolvesAbsoluteLocationsWithAnImplicitFirstColumn() throws Exception {
        Path root = Files.createTempDirectory("shed-terminal-link-");
        Path source = Files.writeString(root.resolve("Build.kt"), "fun main() = Unit\n");
        List<TerminalLinkResolver.Link> opened = new ArrayList<>();

        LinkResult result = TerminalLinkResolver.resolve("at " + source + ":7", () -> root, opened::add);

        assertEquals(1, result.getItems().size());
        result.getItems().getFirst().getLinkInfo().navigate();
        TerminalLinkResolver.SourceLink link = assertInstanceOf(TerminalLinkResolver.SourceLink.class, opened.getFirst());
        assertEquals(source.toRealPath(), link.path());
        assertEquals(7, link.line());
        assertEquals(1, link.column());
    }
}
