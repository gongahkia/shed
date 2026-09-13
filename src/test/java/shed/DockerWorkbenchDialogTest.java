package shed;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import org.junit.jupiter.api.Test;

class DockerWorkbenchDialogTest {
    @Test
    void parsesFormattedDockerRowsWithoutDroppingEmptyColumns() {
        List<String[]> rows = DockerWorkbenchDialog.parseRows("a1\tapi\tnginx\trunning\t\nb2\tworker\tbusybox\texited\t8080/tcp\n", 5);

        assertEquals(2, rows.size());
        assertArrayEquals(new String[] {"a1", "api", "nginx", "running", ""}, rows.getFirst());
        assertArrayEquals(new String[] {"b2", "worker", "busybox", "exited", "8080/tcp"}, rows.get(1));
    }

    @Test
    void padsShortRowsAndIgnoresBlankOutput() {
        assertEquals(List.of(), DockerWorkbenchDialog.parseRows("\n", 3));
        assertArrayEquals(new String[] {"named", "local", ""}, DockerWorkbenchDialog.parseRows("named\tlocal\n", 3).getFirst());
    }
}
