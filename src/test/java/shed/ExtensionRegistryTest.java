package shed;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import shed.api.LanguageContribution;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class ExtensionRegistryTest {
    @Test
    void commandsAreOwnedQualifiedAndRemovedWithTheirExtension() throws Exception {
        ExtensionRegistry registry = new ExtensionRegistry();

        registry.registerCommand("sample", "hello", arguments -> "hello " + arguments);

        assertEquals("hello Shed", registry.executeCommand("sample.hello", "Shed"));
        registry.removeExtension("sample");
        assertNull(registry.executeCommand("sample.hello", "Shed"));
    }

    @Test
    void anExtensionCannotRegisterAnotherExtensionsCommandName() {
        ExtensionRegistry registry = new ExtensionRegistry();

        assertThrows(IllegalArgumentException.class,
            () -> registry.registerCommand("sample", "other.command", arguments -> "unexpected"));
    }

    @Test
    void languageContributionsResolveFileExtensionsDeterministically() {
        ExtensionRegistry registry = new ExtensionRegistry();
        registry.registerLanguage("zeta", new LanguageContribution("zeta-language", "Zeta", Set.of("zed"), List.of("zeta-lsp"), List.of()));
        registry.registerLanguage("alpha", new LanguageContribution("alpha-language", "Alpha", Set.of("zed"), List.of("alpha-lsp"), List.of()));

        ExtensionRegistry.Owned<LanguageContribution> selected = registry.languageForExtension(".zed");
        assertEquals("alpha", selected.extensionId());
        assertEquals("alpha-language", selected.value().id());
    }
}
