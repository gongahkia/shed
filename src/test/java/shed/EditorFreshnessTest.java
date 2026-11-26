package shed;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class EditorFreshnessTest {
    @TempDir Path temporary;
    private record Fixture(WorkspaceQueryService workspace, ProjectBoundFileAcquisition.RootPin root, String hash) { }
    private Fixture fixture(String disk, java.util.function.Supplier<WorkspaceDocument> documents) throws Exception {
        Path root = temporary.toRealPath(); Files.writeString(root.resolve("source.txt"), disk);
        return new Fixture(new WorkspaceQueryService(root, AgentPolicy.readOnly(), () -> List.of(documents.get())),
            ProjectBoundFileAcquisition.pinAuthorizedRoot(root), WorkspacePaths.hash(disk.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    }
    private WorkspaceDocument document(String text, boolean dirty) throws Exception {
        Path root = temporary.toRealPath();
        return new WorkspaceDocument(WorkspacePaths.id(root), "document", root.resolve("source.txt").toUri().toString(),
            "revision-2", text, dirty, false, true, "UTF-8", "LF", false, WorkspaceDocument.Source.EDITOR);
    }
    @Test void acknowledgesOnlyOnceEditorHasCurrentContent() throws Exception {
        var old = document("old", false); var current = document("new", false); AtomicInteger calls = new AtomicInteger();
        var f = fixture("new", () -> calls.incrementAndGet() < 3 ? old : current);
        var result = EditorFreshness.await(f.workspace(), f.root(), "source.txt", f.hash(), 500, () -> true);
        assertEquals(true, result.get("synced")); assertEquals("revision-2", result.get("version")); assertTrue(calls.get() >= 3);
    }
    @Test void doesNotReturnStaleTextOnTimeoutOrOverwriteDirtySource() throws Exception {
        var old = document("old", false); var f = fixture("new", () -> old);
        assertEquals(WorkspaceApiException.Code.TIMEOUT, assertThrows(WorkspaceApiException.class,
            () -> EditorFreshness.await(f.workspace(), f.root(), "source.txt", f.hash(), 50, () -> true)).code());
        var dirty = document("human", true); var g = fixture("new", () -> dirty);
        assertEquals(WorkspaceApiException.Code.CONFLICT, assertThrows(WorkspaceApiException.class,
            () -> EditorFreshness.await(g.workspace(), g.root(), "source.txt", g.hash(), 100, () -> true)).code());
        assertEquals("human", dirty.content()); assertEquals("new", Files.readString(temporary.resolve("source.txt")));
    }
    @Test void rejectsWrongHashTraversalAndCancellation() throws Exception {
        var current = document("new", false); var f = fixture("new", () -> current);
        assertEquals(WorkspaceApiException.Code.STALE_VERSION, assertThrows(WorkspaceApiException.class,
            () -> EditorFreshness.await(f.workspace(), f.root(), "source.txt", "0".repeat(64), 100, () -> true)).code());
        assertThrows(WorkspaceApiException.class, () -> EditorFreshness.await(f.workspace(), f.root(), "../outside", f.hash(), 100, () -> true));
        assertThrows(WorkspaceApiException.class, () -> EditorFreshness.await(f.workspace(), f.root(), "source.txt", f.hash(), 100, () -> false));
    }
    @Test void rejectsDiskChangesDuringAcknowledgement() throws Exception {
        var current = document("new", false); var f = fixture("new", () -> {
            try { Files.writeString(temporary.resolve("source.txt"), "changed again"); } catch (Exception e) { throw new RuntimeException(e); }
            return current;
        });
        assertEquals(WorkspaceApiException.Code.STALE_VERSION, assertThrows(WorkspaceApiException.class,
            () -> EditorFreshness.await(f.workspace(), f.root(), "source.txt", f.hash(), 100, () -> true)).code());
    }
    @Test void refusesEdtWaitsAndMalformedLimits() throws Exception {
        var current = document("new", false); var f = fixture("new", () -> current); AtomicReference<Throwable> error = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> { try { EditorFreshness.await(f.workspace(), f.root(), "source.txt", f.hash(), 100, () -> true); }
            catch (Throwable failure) { error.set(failure); } });
        assertInstanceOf(IllegalStateException.class, error.get());
        assertThrows(WorkspaceApiException.class, () -> EditorFreshness.await(f.workspace(), f.root(), "source.txt", "bad", 100, () -> true));
        assertThrows(WorkspaceApiException.class, () -> EditorFreshness.await(f.workspace(), f.root(), "source.txt", f.hash(), 4000, () -> true));
    }
    @Test void authenticatedCliAcknowledgesActualOpenDocumentWithoutChangingIt() throws Exception {
        try (var f = new EditorControlTest.Fixture(temporary)) {
            byte[] before = Files.readAllBytes(f.root.resolve("One.java"));
            var output = new java.io.ByteArrayOutputStream();
            int result = ShedControlCli.run(new String[]{"ctl","--connect",f.server.descriptor().toString(),"sync","One.java",
                "--sha256",WorkspacePaths.hash(before)}, java.io.InputStream.nullInputStream(), output, java.io.OutputStream.nullOutputStream());
            assertEquals(0,result,output.toString());
            var response = ShedControlCli.result(output.toString());
            assertEquals(true,MiniJson.asObject(response.get("observation")).get("synced"));
            assertArrayEquals(before,Files.readAllBytes(f.root.resolve("One.java")));
            assertFalse(EditorControlTest.edt(() -> f.pane.getBuffer().isModified()));
        }
    }
}
