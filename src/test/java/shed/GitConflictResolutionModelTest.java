package shed;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

public class GitConflictResolutionModelTest {
    @Test
    void preservesEveryKnownSideAndLabelsAbsentStages() {
        GitConflictResolutionModel.Conflict conflict = new GitConflictResolutionModel.Conflict("src/App.java", "digest", "working",
            new GitConflictResolutionModel.Side(true, "base"), new GitConflictResolutionModel.Side(true, "ours"),
            new GitConflictResolutionModel.Side(false, ""));

        assertEquals("base", conflict.base().display());
        assertEquals("ours", conflict.ours().display());
        assertTrue(conflict.theirs().display().contains("no content"));
    }

    @Test
    void rejectsEveryUnresolvedMarkerBeforeAnyApplyPath() {
        assertNull(GitConflictResolutionModel.validateResult("resolved\n"));
        assertTrue(GitConflictResolutionModel.validateResult("<<<<<<< ours\n").contains("<<<<<<<"));
        assertTrue(GitConflictResolutionModel.validateResult("=======\n").contains("======="));
        assertTrue(GitConflictResolutionModel.validateResult(">>>>>>> theirs\n").contains(">>>>>>>"));
    }
}
