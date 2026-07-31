package shed;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.util.List;
import org.junit.jupiter.api.Test;

public class GitGraphModelTest {
    @Test
    void parsesCommitParentsAndDecorations() {
        String output = "merge\0first second\0HEAD -> main\0Merge topic\0Ada\0" + "2 weeks ago\0";

        GitGraphModel.Snapshot graph = GitGraphModel.fromCommand(new File("/repo"), new CommandResult(0, output, ""));

        assertTrue(graph.available());
        assertEquals("merge", graph.rows().getFirst().commit().hash());
        assertEquals(List.of("first", "second"), graph.rows().getFirst().commit().parents());
        assertEquals("HEAD -> main", graph.rows().getFirst().commit().decorations());
        assertEquals("2 weeks ago", graph.rows().getFirst().commit().timestamp());
        assertEquals(2, graph.rows().getFirst().afterLanes().size());
    }

    @Test
    void maintainsSeparateLanesAcrossMergeTopology() {
        List<GitGraphModel.Commit> commits = List.of(
            new GitGraphModel.Commit("merge", List.of("main", "topic"), "", "merge", "", ""),
            new GitGraphModel.Commit("main", List.of("root"), "", "main", "", ""),
            new GitGraphModel.Commit("topic", List.of("root"), "", "topic", "", ""),
            new GitGraphModel.Commit("root", List.of(), "", "root", "", "")
        );

        List<GitGraphModel.Row> rows = GitGraphModel.graphRows(commits);

        assertEquals(2, rows.getFirst().afterLanes().size());
        assertEquals(0, rows.get(1).lane());
        assertEquals(1, rows.get(2).lane());
        assertEquals(List.of("root"), rows.getLast().beforeLanes());
        assertTrue(rows.getLast().afterLanes().isEmpty());
    }
}
