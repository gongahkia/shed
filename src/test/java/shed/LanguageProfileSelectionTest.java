package shed;

import static org.junit.jupiter.api.Assertions.assertEquals;

import shed.api.LanguageProfile;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LanguageProfileSelectionTest {
    @Test
    void retainsTheProfileOwnerForAutomaticAndManualSelection(@TempDir Path temporaryDirectory) throws Exception {
        Path file = temporaryDirectory.resolve("demo.ex");
        Files.writeString(file, "module demo\n");
        FileBuffer buffer = new FileBuffer(file.toFile());
        ExtensionRegistry registry = new ExtensionRegistry();
        registry.registerLanguageProfile("sample", new LanguageProfile("example", "Example", Set.of("ex"), Set.of(), Set.of(),
            List.of("//"), List.of(), List.of(), Set.of("module")));
        LanguageProfileSelection selection = new LanguageProfileSelection();

        assertEquals("sample", selection.ownedProfileFor(buffer, registry).extensionId());
        selection.select(buffer, registry, "sample:example");
        assertEquals("example", selection.ownedProfileFor(buffer, registry).value().languageId());
    }
}
