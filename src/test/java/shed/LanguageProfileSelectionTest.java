package shed;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import shed.api.LanguageProfile;

class LanguageProfileSelectionTest {
    @Test
    void selectsAndClearsOneBuffersLanguageProfile() {
        ExtensionRegistry registry = new ExtensionRegistry();
        registry.registerLanguageProfile("sample", new LanguageProfile("sample-language", "Sample", Set.of("sample"), Set.of(),
            Set.of(), List.of("//"), List.of(), List.of(), Set.of("let")));
        LanguageProfileSelection selection = new LanguageProfileSelection();
        FileBuffer buffer = FileBuffer.createScratch("[scratch]", "let value");

        assertNull(selection.profileFor(buffer, registry));
        assertEquals("Sample", selection.select(buffer, registry, "sample:sample-language").displayName());
        assertTrue(selection.isManual(buffer));
        assertEquals("Sample", selection.profileFor(buffer, registry).displayName());
        selection.automatic(buffer);
        assertFalse(selection.isManual(buffer));
        assertNull(selection.profileFor(buffer, registry));
    }

    @Test
    void rejectsAmbiguousUnqualifiedLanguageIds() {
        ExtensionRegistry registry = new ExtensionRegistry();
        LanguageProfile profile = new LanguageProfile("same", "Same", Set.of("same"), Set.of(), Set.of(), List.of(), List.of(), List.of(), Set.of());
        registry.registerLanguageProfile("first", profile);
        registry.registerLanguageProfile("second", profile);

        assertThrows(IllegalArgumentException.class,
            () -> new LanguageProfileSelection().select(FileBuffer.createScratch("[scratch]", ""), registry, "same"));
    }
}
