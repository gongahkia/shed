package shed;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

import shed.api.SnippetContribution;
import java.util.List;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.io.TempDir;

class SnippetServiceTest {

    @TempDir
    Path temporaryDirectory;

    private SnippetService service;

    @BeforeEach
    void setUp() {
        service = new SnippetService();
    }

    @Test
    void findExact_findsJavaMain() {
        SnippetService.Snippet snippet = service.findExact(FileType.JAVA, "main");
        assertNotNull(snippet);
        assertEquals("main", snippet.trigger);
        assertTrue(snippet.body.contains("public static void main"));
    }

    @Test
    void findExact_findsPythonDef() {
        SnippetService.Snippet snippet = service.findExact(FileType.PYTHON, "def");
        assertNotNull(snippet);
        assertTrue(snippet.body.contains("def"));
    }

    @Test
    void findExact_returnsNullForUnknown() {
        assertNull(service.findExact(FileType.JAVA, "nonexistent_trigger_xyz"));
    }

    @Test
    void getSnippetsFor_returnsMatches() {
        List<SnippetService.Snippet> snippets = service.getSnippetsFor(FileType.JAVA, "for");
        assertFalse(snippets.isEmpty());
        for (SnippetService.Snippet s : snippets) {
            assertTrue(s.trigger.startsWith("for"));
        }
    }

    @Test
    void expand_removesTabstops() {
        SnippetService.Snippet snippet = service.findExact(FileType.JAVA, "main");
        String expanded = service.expand(snippet);
        assertNotNull(expanded);
        assertFalse(expanded.contains("$0"));
        assertFalse(expanded.contains("$1"));
    }

    @Test
    void cursorOffset_findsPosition() {
        SnippetService.Snippet snippet = service.findExact(FileType.JAVA, "sout");
        assertNotNull(snippet);
        int offset = service.cursorOffset(snippet);
        assertTrue(offset >= 0);
    }

    @Test
    void addUserSnippet_works() {
        service.addUserSnippet("mysnip", "hello world", "test snippet", FileType.TEXT);
        SnippetService.Snippet found = service.findExact(FileType.TEXT, "mysnip");
        assertNotNull(found);
        assertEquals("hello world", found.body);
    }

    @Test
    void listSnippets_containsContent() {
        String listing = service.listSnippets(FileType.JAVA);
        assertNotNull(listing);
        assertTrue(listing.contains("main"));
        assertTrue(listing.contains("Snippets"));
    }

    @Test
    void findExact_markdownSnippets() {
        SnippetService.Snippet link = service.findExact(FileType.MARKDOWN, "link");
        assertNotNull(link);
        assertTrue(link.body.contains("["));

        SnippetService.Snippet table = service.findExact(FileType.MARKDOWN, "table");
        assertNotNull(table);
        assertTrue(table.body.contains("|"));
    }

    @Test
    void getSnippetsFor_emptyPrefix() {
        List<SnippetService.Snippet> all = service.getSnippetsFor(FileType.RUST, "");
        assertFalse(all.isEmpty());
    }

    @Test
    void findExact_goSnippets() {
        assertNotNull(service.findExact(FileType.GO, "fn"));
        assertNotNull(service.findExact(FileType.GO, "ife"));
    }

    @Test
    void findsNativeStarterSnippetsForAdditionalBuiltInLexicalLanguages() {
        assertTrue(service.findExact(FileType.KOTLIN, "fun").body.contains("fun"));
        assertTrue(service.findExact(FileType.CSHARP, "prop").body.contains("get; set;"));
        assertTrue(service.findExact(FileType.PHP, "foreach").body.contains("foreach"));
        assertTrue(service.findExact(FileType.RUBY, "def").body.contains("end"));
        assertTrue(service.findExact(FileType.SWIFT, "struct").body.contains("struct"));
    }

    @Test
    void loadsVsCodeCompatibleScopedAndGlobalSnippets() throws Exception {
        Files.writeString(temporaryDirectory.resolve("java.json"), """
            {"Log":{"prefix":["log","logger"],"body":["System.out.println(${1:value});","$0"],"description":"log"}}
            """);
        Files.writeString(temporaryDirectory.resolve("snippets.json"), """
            {"Todo":{"prefix":"todo","body":"// TODO: ${1:detail}"}}
            """);

        SnippetService.LoadResult loaded = service.loadFromDirectory(temporaryDirectory);

        assertEquals(3, loaded.loaded());
        assertTrue(loaded.errors().isEmpty());
        assertEquals("log", service.findExact(FileType.JAVA, "log").description);
        assertNotNull(service.findExact(FileType.PYTHON, "todo"));
    }

    @Test
    void extensionSnippetsAreLanguageScopedAndFollowUserSnippetPrecedence() {
        SnippetContribution contribution = new SnippetContribution("log", "example", "log", "extension", "extension log");
        assertEquals("extension", service.findExact(FileType.UNKNOWN, "example", "log", List.of(contribution)).body);
        assertNull(service.findExact(FileType.UNKNOWN, "other", "log", List.of(contribution)));

        service.addUserSnippet("log", "user", "user log", FileType.JAVA);
        SnippetService.Snippet selected = service.findExact(FileType.JAVA, "java", "log", List.of(
            new SnippetContribution("java-log", "java", "log", "extension", "extension log")));
        assertEquals("user", selected.body);
        assertTrue(service.listSnippets(FileType.UNKNOWN, "example", List.of(contribution)).contains("[example]"));
    }

    @Test
    void snippetContributionRejectsUnsafeFields() {
        assertThrows(IllegalArgumentException.class, () -> new SnippetContribution("bad id", "example", "log", "body", "description"));
        assertThrows(IllegalArgumentException.class, () -> new SnippetContribution("log", "example", "contains space", "body", "description"));
        assertThrows(IllegalArgumentException.class, () -> new SnippetContribution("log", "example", "log", "", "description"));
        assertThrows(IllegalArgumentException.class, () -> new SnippetContribution("log", "example", "log", "body", "line\nbreak"));
    }
}
