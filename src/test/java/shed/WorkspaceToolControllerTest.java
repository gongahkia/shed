package shed;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import shed.api.WorkspaceToolAction;
import shed.api.WorkspaceToolContribution;
import shed.api.WorkspaceToolKind;

class WorkspaceToolControllerTest {
    @Test
    void registryRetainsADeclaredWorkspaceToolContribution() {
        ExtensionRegistry registry = new ExtensionRegistry();
        registry.registerWorkspaceTool("sample", new WorkspaceToolContribution() {
            @Override public String id() { return "database"; }
            @Override public String displayName() { return "Sample database"; }
            @Override public WorkspaceToolKind kind() { return WorkspaceToolKind.DATABASE; }
            @Override public boolean supports(Path workspaceRoot) { return true; }
            @Override public List<WorkspaceToolAction> actions() { return List.of(new WorkspaceToolAction("query", "Run query")); }
            @Override public String execute(Path workspaceRoot, String action, String arguments) { return action + ":" + arguments; }
        });

        ExtensionRegistry.Owned<WorkspaceToolContribution> tool = registry.workspaceTools().getFirst();
        assertEquals("sample", tool.extensionId());
        assertEquals(WorkspaceToolKind.DATABASE, tool.value().kind());
        assertTrue(tool.value().actions().stream().anyMatch(action -> action.id().equals("query")));
    }
}
