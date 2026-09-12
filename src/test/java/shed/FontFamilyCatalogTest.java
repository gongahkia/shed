package shed;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class FontFamilyCatalogTest {
    @Test
    void repairsUnambiguousNerdFontAliasesToTheJavaFamilyName() {
        FontFamilyCatalog.Resolution resolution = FontFamilyCatalog.resolveRepair("JetBrains Nerd Font Mono",
            List.of("JetBrainsMono NFM"));

        assertEquals("JetBrainsMono NFM", resolution.family());
        assertTrue(resolution.repaired());
    }

    @Test
    void refusesAnAliasThatCouldReferToMoreThanOneInstalledFamily() {
        FontFamilyCatalog.Resolution resolution = FontFamilyCatalog.resolveRepair("JetBrains Nerd Font Mono",
            List.of("JetBrainsMono NFM", "JetBrainsMono Nerd Font Mono"));

        assertNull(resolution);
    }
}
