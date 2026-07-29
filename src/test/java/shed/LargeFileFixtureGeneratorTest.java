package shed;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

public class LargeFileFixtureGeneratorTest {
    @TempDir
    Path tempDir;

    @Test
    void definesRequiredDefaultFixtureSizes() {
        assertEquals(List.of("large-1m.txt", "large-100m.txt", "large-1g.txt"),
            LargeFileFixtureGenerator.DEFAULT_FIXTURES.stream().map(LargeFileFixtureGenerator.Fixture::name).toList());
        assertEquals(List.of(1L << 20, 100L << 20, 1L << 30),
            LargeFileFixtureGenerator.DEFAULT_FIXTURES.stream().map(LargeFileFixtureGenerator.Fixture::bytes).toList());
    }

    @Test
    void generatesDeterministicChecksumVerifiedUtf8Corpora() throws Exception {
        List<LargeFileFixtureGenerator.Fixture> fixtures = List.of(
            new LargeFileFixtureGenerator.Fixture("small-a.txt", 65_549L),
            new LargeFileFixtureGenerator.Fixture("small-b.txt", 131_071L)
        );
        Path first = tempDir.resolve("first");
        Path second = tempDir.resolve("second");

        LargeFileFixtureGenerator.Report firstReport = LargeFileFixtureGenerator.generate(first, fixtures);
        LargeFileFixtureGenerator.Report secondReport = LargeFileFixtureGenerator.generate(second, fixtures);
        LargeFileFixtureGenerator.Report verification = LargeFileFixtureGenerator.verify(first, fixtures);

        assertTrue(firstReport.verified());
        assertTrue(verification.verified());
        assertEquals(firstReport.results().stream().map(LargeFileFixtureGenerator.Result::checksum).toList(),
            secondReport.results().stream().map(LargeFileFixtureGenerator.Result::checksum).toList());
        for (LargeFileFixtureGenerator.Fixture fixture : fixtures) {
            Path file = first.resolve(fixture.name());
            assertEquals(fixture.bytes(), Files.size(file));
            assertValidUtf8(Files.readAllBytes(file));
        }
        assertTrue(Files.readString(first.resolve("fixtures.manifest"), StandardCharsets.UTF_8).contains("fixtures.algorithm=SHA-256\n"));
    }

    @Test
    void verificationRejectsModifiedFixtureAndManifest() throws Exception {
        List<LargeFileFixtureGenerator.Fixture> fixtures = List.of(new LargeFileFixtureGenerator.Fixture("small.txt", 1_024L));
        Path output = tempDir.resolve("fixtures");
        LargeFileFixtureGenerator.generate(output, fixtures);
        Files.write(output.resolve("small.txt"), new byte[] {'x'}, StandardOpenOption.APPEND);
        Files.writeString(output.resolve("fixtures.manifest"), "modified\n", StandardCharsets.UTF_8);

        LargeFileFixtureGenerator.Report verification = LargeFileFixtureGenerator.verify(output, fixtures);

        assertFalse(verification.verified());
        assertEquals(2, verification.failures().size());
        assertFalse(verification.results().getFirst().valid());
    }

    private static void assertValidUtf8(byte[] bytes) throws CharacterCodingException {
        StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes));
    }
}
