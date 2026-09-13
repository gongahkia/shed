package shed;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class ScmContributionServiceTest {
    @Test
    void exposesNativeMercurialAndSubversionProvidersWhenTheirMarkersExist() throws Exception {
        Path mercurial = Files.createTempDirectory("shed-hg-");
        Path subversion = Files.createTempDirectory("shed-svn-");
        Files.createDirectory(mercurial.resolve(".hg"));
        Files.createDirectory(subversion.resolve(".svn"));
        ScmContributionService service = new ScmContributionService();

        assertTrue(service.handle(mercurial, "list").document().contains("mercurial"));
        assertTrue(service.handle(subversion, "list").document().contains("subversion"));
        assertEquals("Action is not declared by mercurial: remove", service.handle(mercurial, "mercurial remove").message());
    }
}
