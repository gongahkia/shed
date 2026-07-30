package shed;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

public class ReleaseDocumentationTest {
    @Test
    void installationGuideMatchesShippedArtifactsAndCommands() throws IOException {
        String guide = Files.readString(Path.of("docs/INSTALL.md"));
        for (String value : new String[] {
            "Shed-<version>-macos-arm64.dmg", "Shed-<version>-windows-x64.msi", "Shed-<version>-linux-x64.deb",
            "bash scripts/package-macos.sh", ".\\scripts\\package-windows.ps1", "bash scripts/package-linux.sh",
            "shasum -a 256 -c", "Get-FileHash", "sha256sum -c", ":update consent", ":perf diagnostics"
        }) {
            assertTrue(guide.contains(value), "installation guide missing " + value);
        }
    }

    @Test
    void readmeKeepsItsUsageHeadingsAndRemovesObsoleteArtifactClaims() throws IOException {
        String readme = Files.readString(Path.of("README.md"));
        for (String heading : new String[] { "## Features", "## Usage", "#### Linux", "#### OSX", "#### Windows", "### Building `Shed` yourself" }) {
            assertTrue(readme.contains(heading), "README heading missing " + heading);
        }
        assertFalse(readme.contains("build/Shed.jar"));
        assertFalse(readme.contains("Tiny ~1MB executable"));
        assertTrue(readme.contains("docs/INSTALL.md"));
    }
}
