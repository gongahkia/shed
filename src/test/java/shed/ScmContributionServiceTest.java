package shed;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;
import shed.api.ScmContribution;
import org.junit.jupiter.api.Test;

class ScmContributionServiceTest {
    @Test
    void listsAndExecutesOnlyDeclaredProviderActions() {
        ExtensionRegistry registry = new ExtensionRegistry();
        registry.registerScm("sample", new ScmContribution() {
            @Override public String id() { return "fossil"; }
            @Override public String displayName() { return "Fossil"; }
            @Override public boolean supports(Path workspaceRoot) { return true; }
            @Override public String status(Path workspaceRoot) { return "checkout: clean"; }
            @Override public List<String> actions() { return List.of("sync", "timeline"); }
            @Override public String execute(Path workspaceRoot, String action, String arguments) { return action + ":" + arguments; }
        });
        ScmContributionService service = new ScmContributionService(registry);
        Path workspace = Path.of(".").toAbsolutePath().normalize();

        ScmContributionService.Result list = service.handle(workspace, "list");
        assertTrue(list.document().contains("sample:fossil"));
        assertEquals("sample:fossil sync complete", service.handle(workspace, "sample:fossil sync origin").message());
        assertTrue(service.handle(workspace, "sample:fossil sync origin").document().contains("sync:origin"));
        assertEquals("Action is not declared by sample:fossil: delete", service.handle(workspace, "sample:fossil delete").message());
    }
}
