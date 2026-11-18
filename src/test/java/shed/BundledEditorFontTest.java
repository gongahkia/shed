package shed;

import static org.junit.jupiter.api.Assertions.*;
import java.awt.Font;
import org.junit.jupiter.api.Test;

class BundledEditorFontTest {
    @Test void bundledFaceLoadsWithoutAUserFontInstallationAndDerivesEditorSizes() {
        assertTrue(BundledEditorFont.available());
        Font regular = BundledEditorFont.atSize(16);
        assertNotNull(regular); assertEquals("JetBrains Mono", regular.getFamily()); assertEquals(16, regular.getSize());
        assertEquals(24, BundledEditorFont.atSize(24).getSize());
        assertEquals(regular.getFamily(), regular.deriveFont(Font.BOLD).getFamily());
        assertTrue(regular.canDisplay('a')); assertTrue(regular.canDisplay('é'));
    }

    @Test void canonicalFamilyAndAliasValidateWhileOtherConfiguredFontsKeepTheirIdentity() {
        assertEquals("JetBrains Mono", FontFamilyCatalog.resolve("JetBrains Mono"));
        assertEquals("JetBrains Mono", FontFamilyCatalog.resolve("jetbrainsmono"));
        assertEquals(new FontFamilyCatalog.Resolution("JetBrains Mono", true), FontFamilyCatalog.resolveRepair("jetbrainsmono"));
        assertEquals("Monospaced", FontFamilyCatalog.resolve("Monospaced"));
        assertFalse(BundledEditorFont.matches("JetBrains Mono Nerd Font"));
    }
}
