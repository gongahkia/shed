package shed;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.File;
import org.junit.jupiter.api.Test;

class FileFinderIconsTest {
    @Test
    void mapsRecognizedFileNamesAndTypesToNerdFontGlyphs() {
        assertEquals("\ue674", FileFinderIcons.iconFor("pom.xml"));
        assertEquals("\ue673", FileFinderIcons.iconFor("Makefile"));
        assertEquals("\ue738", FileFinderIcons.iconFor("src/Main.java"));
        assertEquals("\ue74e", FileFinderIcons.iconFor("web/app.js"));
        assertEquals("\ue628", FileFinderIcons.iconFor("web/app.tsx"));
        assertEquals("\ue73e", FileFinderIcons.iconFor("README.md"));
        assertEquals("\ue64e", FileFinderIcons.iconFor("notes.unknown"));
    }

    @Test
    void mapsFoldersToNerdFontGlyphs() {
        File folder = new File("src");

        assertEquals("\uf07b", FileFinderIcons.iconForDirectory(folder, false));
        assertEquals("\uf115", FileFinderIcons.iconForDirectory(folder, true));
        assertEquals("\ue70c", FileFinderIcons.iconForDirectory(new File(".vscode"), false));
    }
}
