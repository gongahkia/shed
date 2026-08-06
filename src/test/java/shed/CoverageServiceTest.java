package shed;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CoverageServiceTest {
    @TempDir Path root;

    @Test
    void importsJacocoCoberturaLcovAndGoProfiles() throws Exception {
        Files.createDirectories(root.resolve("pkg"));
        Files.createDirectories(root.resolve("src"));
        Files.writeString(root.resolve("pkg/Thing.java"), "class Thing {}\n");
        Files.writeString(root.resolve("src/app.py"), "print('x')\n");
        Files.writeString(root.resolve("web.js"), "export const x = 1;\n");
        Files.writeString(root.resolve("main.go"), "package main\nfunc main() {}\n");
        Files.writeString(root.resolve("jacoco.xml"), "<report><package name=\"pkg\"><sourcefile name=\"Thing.java\"><line nr=\"1\" ci=\"2\" mi=\"0\" cb=\"1\" mb=\"1\"/></sourcefile></package></report>");
        Files.writeString(root.resolve("coverage.xml"), "<coverage><packages><package><classes><class filename=\"src/app.py\"><lines><line number=\"1\" hits=\"0\" condition-coverage=\"50% (1/2)\"/></lines></class></classes></package></packages></coverage>");
        Files.writeString(root.resolve("lcov.info"), "TN:\nSF:web.js\nDA:1,3\nBRDA:1,0,0,1\nBRDA:1,0,1,0\nend_of_record\n");
        Files.writeString(root.resolve("cover.out"), "mode: count\nmain.go:1.1,2.1 1 4\n");

        CoverageService service = new CoverageService();
        CoverageService.ImportResult jacoco = service.importReport(root, root.resolve("jacoco.xml"));
        CoverageService.ImportResult cobertura = service.importReport(root, root.resolve("coverage.xml"));
        CoverageService.ImportResult lcov = service.importReport(root, root.resolve("lcov.info"));
        CoverageService.ImportResult go = service.importReport(root, root.resolve("cover.out"));

        assertEquals(CoverageService.Format.JACOCO, jacoco.format());
        assertEquals(2, jacoco.report().hits(root.resolve("pkg/Thing.java")).get(0));
        assertEquals(CoverageService.Format.COBERTURA, cobertura.format());
        assertEquals(0, cobertura.report().hits(root.resolve("src/app.py")).get(0));
        assertEquals(CoverageService.Format.LCOV, lcov.format());
        assertEquals(3, lcov.report().hits(root.resolve("web.js")).get(0));
        assertEquals(CoverageService.Format.GO, go.format());
        assertEquals(4, go.report().hits(root.resolve("main.go")).get(1));
        CoverageService.Summary summary = jacoco.report().merge(cobertura.report()).merge(lcov.report()).merge(go.report()).summary();
        assertEquals(4, summary.files());
        assertEquals(5, summary.lines());
        assertEquals(4, summary.coveredLines());
        assertEquals(6, summary.branches());
        assertEquals(3, summary.coveredBranches());
    }

    @Test
    void rejectsUnsupportedAndExternalXmlReports() throws Exception {
        Files.writeString(root.resolve("unknown.txt"), "nothing useful");
        Files.writeString(root.resolve("unsafe.xml"), "<!DOCTYPE report [<!ENTITY xxe SYSTEM \"file:///etc/passwd\">]><report><package name=\"pkg\"><sourcefile name=\"&xxe;\"/></package></report>");
        CoverageService service = new CoverageService();
        assertThrows(java.io.IOException.class, () -> service.importReport(root, root.resolve("unknown.txt")));
        assertThrows(java.io.IOException.class, () -> service.importReport(root, root.resolve("unsafe.xml")));
    }

    @Test
    void ignoresPathsOutsideWorkspace() throws Exception {
        Files.writeString(root.resolve("outside.info"), "SF:/tmp/not-shed.js\nDA:1,1\nend_of_record\n");
        assertThrows(java.io.IOException.class, () -> new CoverageService().importReport(root, root.resolve("outside.info")));
    }
}
