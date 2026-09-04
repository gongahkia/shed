package shed;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import shed.api.LanguageContribution;
import shed.api.LanguageProfile;
import shed.api.SnippetContribution;
import java.io.File;
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

    @Test
    void languageContributionsResolveTheirDeclaredLanguageId() {
        ExtensionRegistry registry = new ExtensionRegistry();
        registry.registerLanguage("sample", new LanguageContribution("example", "Example", Set.of("ex"), List.of("example-lsp"), List.of()));

        assertEquals("sample", registry.languageForId("example").extensionId());
        assertEquals("example", registry.languageForId("example").value().id());
    }

    @Test
    void languageContributionLookupCanBeQualifiedByExtensionOwner() {
        ExtensionRegistry registry = new ExtensionRegistry();
        registry.registerLanguage("alpha", new LanguageContribution("example", "Alpha", Set.of("aex"), List.of("alpha-lsp"), List.of()));
        registry.registerLanguage("zeta", new LanguageContribution("example", "Zeta", Set.of("zex"), List.of("zeta-lsp"), List.of()));

        assertEquals("zeta", registry.languageForId("zeta", "example").extensionId());
        assertNull(registry.languageForId("missing", "example"));
    }

    @Test
    void languageProfilesResolveFileNamesExtensionsAndFirstLinesDeterministically() {
        ExtensionRegistry registry = new ExtensionRegistry();
        LanguageProfile alpha = new LanguageProfile("alpha", "Alpha", Set.of("alp"), Set.of("Alphafile"),
            Set.of("#!/usr/bin/env alpha"), List.of("//"), List.of(), List.of(new LanguageProfile.StringDelimiter("\"", false)), Set.of("let"));
        LanguageProfile zeta = new LanguageProfile("zeta", "Zeta", Set.of("alp"), Set.of(), Set.of(), List.of(), List.of(), List.of(), Set.of());
        registry.registerLanguageProfile("zeta", zeta);
        registry.registerLanguageProfile("alpha", alpha);

        assertEquals("alpha", registry.languageProfileFor(new File("demo.alp"), "").extensionId());
        assertEquals("alpha", registry.languageProfileFor(new File("ALPHAFILE"), "").extensionId());
        assertEquals("alpha", registry.languageProfileFor(new File("script"), "#!/usr/bin/env alpha\nlet x").extensionId());
        registry.removeExtension("alpha");
        assertEquals("zeta", registry.languageProfileFor(new File("demo.alp"), "").extensionId());
    }

    @Test
    void languageProfilesKeepApiV1ConstructionAndValidateIndentationPreferences() {
        LanguageProfile legacy = new LanguageProfile("legacy", "Legacy", Set.of("legacy"), Set.of(), Set.of(),
            List.of(), List.of(), List.of(), Set.of());
        LanguageProfile configured = new LanguageProfile("configured", "Configured", Set.of("cfg"), Set.of(), Set.of(),
            List.of(), List.of(), List.of(), Set.of(), 2, false);

        assertNull(legacy.tabSize());
        assertNull(legacy.insertSpaces());
        assertEquals(2, configured.tabSize());
        assertEquals(false, configured.insertSpaces());
        assertThrows(IllegalArgumentException.class, () -> new LanguageProfile("invalid", "Invalid", Set.of("bad"), Set.of(), Set.of(),
            List.of(), List.of(), List.of(), Set.of(), 17, true));
    }

    @Test
    void snippetsAreOwnedAndRemovedWithTheirExtension() {
        ExtensionRegistry registry = new ExtensionRegistry();
        registry.registerSnippet("sample", new SnippetContribution("log", "example", "log", "print(${1:value})", "log value"));

        assertEquals(1, registry.snippets().size());
        assertEquals("sample", registry.snippets().getFirst().extensionId());
        registry.removeExtension("sample");
        assertEquals(List.of(), registry.snippets());
    }
}
