package shed;

import shed.api.LanguageProfile;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class GrammarHighlightServiceTest {

    @Test
    void extensionProfileLexesKeywordsCommentsAndMultilineStrings() {
        LanguageProfile profile = new LanguageProfile("zed", "Zed", Set.of("zed"), Set.of(), Set.of(),
            List.of("--"), List.of(new LanguageProfile.BlockComment("{-", "-}")),
            List.of(new LanguageProfile.StringDelimiter("\"\"\"", true), new LanguageProfile.StringDelimiter("\"", false)),
            Set.of("let", "module"));
        String text = "module Demo\nlet value = \"\"\"one\ntwo\"\"\"\n-- trailing";

        List<GrammarHighlightService.Token> tokens = new GrammarHighlightService().highlightSnapshot(text, profile);

        assertTrue(tokens.stream().anyMatch(token -> token.scope() == GrammarHighlightService.Scope.KEYWORD && text.substring(token.start(), token.end()).equals("module")));
        assertTrue(tokens.stream().anyMatch(token -> token.scope() == GrammarHighlightService.Scope.STRING && text.substring(token.start(), token.end()).contains("two")));
        assertTrue(tokens.stream().anyMatch(token -> token.scope() == GrammarHighlightService.Scope.COMMENT && text.substring(token.start(), token.end()).equals("-- trailing")));
    }
    @TempDir
    Path temporaryDirectory;

    @Test
    void keepsKeywordsOutOfStringsAndComments() throws Exception {
        String text = "class Demo { String value = \"if\"; // return\\nvoid run() {}\\n}".replace("\\n", "\n");
        List<GrammarHighlightService.Token> tokens = highlight("Demo.java", text);

        assertTrue(has(tokens, text, "class", GrammarHighlightService.Scope.KEYWORD));
        assertTrue(has(tokens, text, "\"if\"", GrammarHighlightService.Scope.STRING));
        assertTrue(has(tokens, text, "// return", GrammarHighlightService.Scope.COMMENT));
        assertFalse(has(tokens, text, "if", GrammarHighlightService.Scope.KEYWORD));
    }

    @Test
    void invalidatesOnlyAffectedSuffixAfterMultilineCommentChanges() throws Exception {
        FileBuffer buffer = buffer("Demo.java", "/* open\nstill");
        GrammarHighlightService service = new GrammarHighlightService();
        service.highlight(buffer, buffer.getContent(), FileType.JAVA);

        String updated = "/* open */\nclass Demo {}";
        List<GrammarHighlightService.Token> tokens = service.highlight(buffer, updated, FileType.JAVA);

        assertTrue(has(tokens, updated, "class", GrammarHighlightService.Scope.KEYWORD));
    }

    @Test
    void recognizesHtmlEmbeddedScriptAndMarkdownFences() throws Exception {
        String html = "<script>const value = 1;</script>";
        assertTrue(has(highlight("page.html", html), html, "const", GrammarHighlightService.Scope.KEYWORD));

        String markdown = "```java\nclass Demo {}\n```";
        assertTrue(has(highlight("notes.md", markdown), markdown, "class", GrammarHighlightService.Scope.KEYWORD));
    }

    @Test
    void virtualizedCodeRangeRetainsMultilineCommentState() {
        String text = "class Demo {\n/* open\nstill comment\n*/\nint value = 1;\n}";
        int start = text.indexOf("still comment");
        int end = start + "still comment".length();

        List<GrammarHighlightService.Token> tokens = new GrammarHighlightService().highlightViewport(text, FileType.JAVA, start, end);

        assertTrue(has(tokens, text, "still comment", GrammarHighlightService.Scope.COMMENT));
    }

    private List<GrammarHighlightService.Token> highlight(String name, String text) throws Exception {
        FileBuffer buffer = buffer(name, text);
        return new GrammarHighlightService().highlight(buffer, text, buffer.getFileType());
    }

    private FileBuffer buffer(String name, String text) throws Exception {
        Path file = temporaryDirectory.resolve(name);
        Files.writeString(file, text, StandardCharsets.UTF_8);
        return new FileBuffer(file.toFile());
    }

    private boolean has(List<GrammarHighlightService.Token> tokens, String text, String value, GrammarHighlightService.Scope scope) {
        return tokens.stream().anyMatch(token -> token.scope() == scope && text.substring(token.start(), token.end()).equals(value));
    }
}
