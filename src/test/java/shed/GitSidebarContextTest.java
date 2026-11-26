package shed;

import static org.junit.jupiter.api.Assertions.*;
import java.util.List;
import org.junit.jupiter.api.Test;

/** No repository access: validate history admission before any Git operation can be requested. */
class GitSidebarContextTest {
    @Test void historyActionsRequireARealCapturedRootAndCurrentWorkspaceGeneration(){
        var ready=new GitHistoryModel.Snapshot(GitHistoryModel.State.READY,"/disposable-fixture",List.of(),List.of(),"");
        assertTrue(GitChangesToolPanel.usableHistory(ready,4,4));
        assertFalse(GitChangesToolPanel.usableHistory(null,4,4));
        assertFalse(GitChangesToolPanel.usableHistory(GitHistoryModel.unavailable("No repository"),4,4));
        assertFalse(GitChangesToolPanel.usableHistory(new GitHistoryModel.Snapshot(GitHistoryModel.State.READY,"",List.of(),List.of(),""),4,4));
        assertFalse(GitChangesToolPanel.usableHistory(ready,4,5));
        assertFalse(GitChangesToolPanel.usableHistory(ready,4,6),"Switching away and back must not revive an old action context");
    }
}
