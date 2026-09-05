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
    void lexesCommonConfigurationSqlAndShellFilesWithoutEnablingAnLsp() throws Exception {
        String yaml = "enabled: true # local comment";
        assertTrue(has(highlight("service.yaml", yaml), yaml, "true", GrammarHighlightService.Scope.KEYWORD));
        assertTrue(has(highlight("service.yaml", yaml), yaml, "# local comment", GrammarHighlightService.Scope.COMMENT));

        String toml = "enabled = false # local comment";
        assertTrue(has(highlight("config.toml", toml), toml, "false", GrammarHighlightService.Scope.KEYWORD));
        assertTrue(has(highlight("config.toml", toml), toml, "# local comment", GrammarHighlightService.Scope.COMMENT));

        String sql = "SELECT id FROM users -- local comment";
        assertTrue(has(highlight("query.sql", sql), sql, "SELECT", GrammarHighlightService.Scope.KEYWORD));
        assertTrue(has(highlight("query.sql", sql), sql, "-- local comment", GrammarHighlightService.Scope.COMMENT));

        String shell = "if true; then # local comment";
        assertTrue(has(highlight("deploy.sh", shell), shell, "if", GrammarHighlightService.Scope.KEYWORD));
        assertTrue(has(highlight("deploy.sh", shell), shell, "# local comment", GrammarHighlightService.Scope.COMMENT));

        String cmake = "CMAKE_MINIMUM_REQUIRED(VERSION 3.21) # local comment";
        assertTrue(has(highlight("CMakeLists.txt", cmake), cmake, "CMAKE_MINIMUM_REQUIRED", GrammarHighlightService.Scope.KEYWORD));
        assertTrue(has(highlight("CMakeLists.txt", cmake), cmake, "# local comment", GrammarHighlightService.Scope.COMMENT));
    }

    @Test
    void lexesAdditionalLanguageFilesWithoutClaimingGrammarOrServerParity() throws Exception {
        String kotlin = "class Engine { fun run() { val value = 1 } // local comment }";
        assertTrue(has(highlight("Engine.kt", kotlin), kotlin, "fun", GrammarHighlightService.Scope.KEYWORD));
        assertTrue(has(highlight("Engine.kt", kotlin), kotlin, "// local comment }", GrammarHighlightService.Scope.COMMENT));

        String csharp = "[Fact]\npublic class Engine { public void Run() {} // local comment }";
        assertTrue(has(highlight("Engine.cs", csharp), csharp, "[Fact", GrammarHighlightService.Scope.ANNOTATION));
        assertTrue(has(highlight("Engine.cs", csharp), csharp, "// local comment }", GrammarHighlightService.Scope.COMMENT));

        String php = "#[Route]\nclass Engine { public function run() {} # local comment }";
        assertTrue(has(highlight("index.php", php), php, "#[Route]", GrammarHighlightService.Scope.ANNOTATION));
        assertTrue(has(highlight("index.php", php), php, "function", GrammarHighlightService.Scope.KEYWORD));
        assertTrue(has(highlight("index.php", php), php, "# local comment }", GrammarHighlightService.Scope.COMMENT));

        String ruby = "class Engine\n  def run\n  end\nend # local comment";
        assertTrue(has(highlight("engine.rb", ruby), ruby, "def", GrammarHighlightService.Scope.KEYWORD));
        assertTrue(has(highlight("engine.rb", ruby), ruby, "# local comment", GrammarHighlightService.Scope.COMMENT));

        String swift = "struct Engine { func run() {} }\nlet message = \"\"\"one\ntwo\"\"\"";
        assertTrue(has(highlight("Engine.swift", swift), swift, "func", GrammarHighlightService.Scope.KEYWORD));
        assertTrue(has(highlight("Engine.swift", swift), swift, "two\"\"\"", GrammarHighlightService.Scope.STRING));
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
