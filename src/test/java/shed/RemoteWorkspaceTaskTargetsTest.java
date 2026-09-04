package shed;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import shed.api.RemoteWorkspace;

class RemoteWorkspaceTaskTargetsTest {
    @TempDir Path root;

    @Test
    void exposesOnlyTheConnectionContainingTheRequestedLocalProject() {
        RemoteWorkspaceTaskTargets targets = new RemoteWorkspaceTaskTargets();
        RemoteWorkspace workspace = new RemoteWorkspace() {
            @Override public String displayName() { return "test"; }
            @Override public Path localRoot() { return root; }
            @Override public void synchronize() { }
            @Override public void synchronizeToRemote() { }
            @Override public void close() { }
        };

        targets.register("SSH-Example", workspace);

        assertEquals("ssh-example", targets.targetFor("ssh-example", root.resolve("project")).id());
        assertNull(targets.targetFor("ssh-example", root.getParent()));
        targets.unregister("SSH-Example");
        assertNull(targets.targetFor("ssh-example", root));
    }
}
