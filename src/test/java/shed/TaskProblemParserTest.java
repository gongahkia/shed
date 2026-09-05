package shed;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

public class TaskProblemParserTest {
    @TempDir
    Path tempDir;

    @Test
    void resolvesRelativeGenericProblemsFromTaskCwd() throws Exception {
        Path cwd = tempDir.resolve("tools");
        Files.createDirectories(cwd.resolve("src"));

        List<QuickfixService.Entry> entries = TaskProblemParser.parseGeneric(
            "src/Main.java:12:4: missing semicolon\nnot a problem", "task:check", cwd.toFile());

        assertEquals(1, entries.size());
        QuickfixService.Entry entry = entries.get(0);
        assertEquals(cwd.resolve("src/Main.java").toFile().getCanonicalPath(), entry.getFilePath());
        assertEquals(12, entry.getLine());
        assertEquals(4, entry.getColumn());
        assertEquals("missing semicolon", entry.getMessage());
        assertEquals("task:check", entry.getSource());
    }

    @Test
    void ignoresMalformedProblemOutput() {
        assertTrue(TaskProblemParser.parseGeneric("src/Main.java:line: bad", "task", tempDir.toFile()).isEmpty());
    }

    @Test
    void parsesTypescriptAndMsCompileDiagnosticsWithSeverity() throws Exception {
        Path cwd = tempDir.resolve("compiler");
        Files.createDirectories(cwd.resolve("src"));

        List<QuickfixService.Entry> typescript = TaskProblemParser.parse(
            "src/app.ts(4,11): error TS2322: Type 'string' is not assignable to type 'number'.", "task:tsc", cwd.toFile(),
            TaskService.ProblemMatcher.TYPESCRIPT);
        List<QuickfixService.Entry> msCompile = TaskProblemParser.parse(
            "src/Program.cs(9,4): warning CS0219: The variable is assigned but its value is never used", "task:build", cwd.toFile(),
            TaskService.ProblemMatcher.MSCOMPILE);

        assertEquals(1, typescript.size());
        assertEquals(cwd.resolve("src/app.ts").toFile().getCanonicalPath(), typescript.getFirst().getFilePath());
        assertEquals(4, typescript.getFirst().getLine());
        assertEquals(11, typescript.getFirst().getColumn());
        assertEquals("TS2322: Type 'string' is not assignable to type 'number'.", typescript.getFirst().getMessage());
        assertEquals(QuickfixService.Severity.ERROR, typescript.getFirst().getSeverity());

        assertEquals(1, msCompile.size());
        assertEquals(cwd.resolve("src/Program.cs").toFile().getCanonicalPath(), msCompile.getFirst().getFilePath());
        assertEquals(QuickfixService.Severity.WARNING, msCompile.getFirst().getSeverity());
    }

    @Test
    void parsesEslintCompactAndStylishDiagnostics() throws Exception {
        Path cwd = tempDir.resolve("lint");
        Files.createDirectories(cwd.resolve("src"));
        String output = "src/compact.js: line 2, col 7, Warning - Unexpected console statement. (no-console)\n"
            + "src/stylish.js\n  5:3  error  Unexpected var, use let or const instead  no-var\n\n✖ 2 problems";

        List<QuickfixService.Entry> entries = TaskProblemParser.parse(output, "task:lint", cwd.toFile(), TaskService.ProblemMatcher.ESLINT);

        assertEquals(2, entries.size());
        assertEquals(cwd.resolve("src/compact.js").toFile().getCanonicalPath(), entries.get(0).getFilePath());
        assertEquals(QuickfixService.Severity.WARNING, entries.get(0).getSeverity());
        assertEquals(cwd.resolve("src/stylish.js").toFile().getCanonicalPath(), entries.get(1).getFilePath());
        assertEquals(5, entries.get(1).getLine());
        assertEquals(3, entries.get(1).getColumn());
        assertEquals(QuickfixService.Severity.ERROR, entries.get(1).getSeverity());
    }
}
