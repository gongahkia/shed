package shed;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

class DocumentLabelsTest {
    private DocumentWorkspace.Document document(int id, String name, String parent) {
        return new DocumentWorkspace.Document(new DocumentWorkspace.Id(id), name,
            parent == null ? null : Path.of(parent, name).toAbsolutePath().toString(), false, false, 0);
    }

    @Test void duplicateNamesKeepTheirNameAndShortestUnambiguousParent() {
        var a = document(1, "index.java", "project/client/src");
        var b = document(2, "index.java", "project/server/src");
        var c = document(3, "notes.md", "project/docs");
        var labels = DocumentLabels.forDocuments(List.of(a, b, c));
        assertEquals("index.java — " + Path.of("client", "src"), labels.get(a.id()));
        assertEquals("index.java — " + Path.of("server", "src"), labels.get(b.id()));
        assertEquals("notes.md", labels.get(c.id()));
        assertEquals(labels, DocumentLabels.forDocuments(List.of(c, b, a)), "File disambiguation must survive tab order changes");
    }

    @Test void untitledAndRootDocumentsRemainIdentifiable() {
        var a = document(1, "untitled", null);
        var b = document(2, "untitled", null);
        var labels = DocumentLabels.forDocuments(List.of(a, b));
        assertNotEquals(labels.get(a.id()), labels.get(b.id()));
        assertTrue(labels.get(a.id()).startsWith("untitled"));
    }
}
