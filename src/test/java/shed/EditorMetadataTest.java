package shed;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** disposable canonical documents and production pane/surface capture; never constructs a window. */
class EditorMetadataTest {
    @TempDir Path temporary;
    static <T> T edt(Callable<T> operation) throws Exception {
        var task = new FutureTask<T>(operation); SwingUtilities.invokeAndWait(task); return task.get();
    }
    static final class Fixture implements AutoCloseable {
        final Path root;
        final ApplicationStatus application = new ApplicationStatus(true);
        final WorkspaceRoots roots = new WorkspaceRoots();
        final List<FileBuffer> buffers = new ArrayList<>();
        final List<EditorPane> panes = new ArrayList<>();
        final EditorWorkspaceDocuments documents;
        final EditorMetadata metadata;
        EditorPane active;
        boolean closing;
        Fixture(Path parent) throws Exception {
            root = Files.createDirectory(parent.resolve("approved")).toRealPath();
            Path file = Files.writeString(root.resolve("private-name.java"), "a😀é\nbody\n");
            documents = new EditorWorkspaceDocuments(() -> List.copyOf(buffers), roots::all, roots::active, root);
            edt(() -> { roots.add(root); add(file); return null; });
            metadata = new EditorMetadata(application, new EditorMetadata.Approval(root, roots.generation()), this::capture);
        }
        EditorPane add(Path file) throws Exception {
            FileBuffer buffer = new FileBuffer(file.toFile()); buffers.add(buffer); return split(buffer);
        }
        EditorPane split(FileBuffer buffer) {
            JTextArea area = new JTextArea(); area.setDocument(buffer.getDocument());
            var pane = new EditorPane(area, null, new JScrollPane(area), new SearchManager(area));
            pane.setBuffer(buffer); panes.add(pane); active = pane; return pane;
        }
        EditorMetadata.Context capture() {
            if (closing) throw EditorMetadata.failure(WorkspaceApiException.Code.APP_SHUTTING_DOWN);
            if (active != null && !panes.contains(active)) throw EditorMetadata.failure(WorkspaceApiException.Code.PANE_UNAVAILABLE);
            if (active != null && active.getBuffer() != null && !buffers.contains(active.getBuffer()))
                throw EditorMetadata.failure(WorkspaceApiException.Code.CONTEXT_CHANGED);
            return EditorMetadata.capture(active, documents, roots.active(), roots.all(), roots.generation());
        }
        Map<String, Object> params() { return Map.of("schemaVersion", 1L, "projectId", metadata.projectId); }
        Map<String, Object> observe() { return metadata.observe(params()); }
        @Override public void close() throws Exception {
            metadata.close();
            edt(() -> { closing = true; for (var pane : panes) pane.disposeSourceView(); panes.clear(); buffers.clear(); active = null; documents.close(); return null; });
        }
    }
    static void fails(String expected, Runnable operation) {
        assertEquals(expected, assertThrows(WorkspaceApiException.class, operation::run).code().name());
    }
    static String request(Map<String, Object> params) { return WorkspaceRpcTest.request("metadata", "editor/metadata", params); }

    @Test void schemaIsBoundedOpaqueAndMatchesAuthoritativeUtf16State() throws Exception {
        try (var f = new Fixture(temporary)) {
            edt(() -> { f.active.surface().select(4, 1); f.active.getBuffer().setModified(true); return null; });
            var value = f.observe();
            assertEquals(java.util.Set.of("schemaVersion", "protocolVersion", "instanceId", "windowId", "capabilities", "projectId",
                "paneId", "documentId", "documentLength", "documentRevision", "dirty", "caretOffset", "selectionStart", "selectionEnd",
                "coordinateUnit", "available", "valid"), value.keySet());
            var state = edt(f::capture);
            assertEquals(state.length(), value.get("documentLength")); assertEquals(state.revision(), value.get("documentRevision"));
            assertEquals(4, value.get("caretOffset")); assertEquals(1, value.get("selectionStart")); assertEquals(4, value.get("selectionEnd"));
            assertEquals("utf-16", value.get("coordinateUnit")); assertEquals(true, value.get("dirty"));
            String json = MiniJson.stringify(value); assertTrue(json.length() < 1536);
            for (String excluded : List.of(f.root.toString(), "private-name", "body", "😀", "text", "path", "environment")) assertFalse(json.contains(excluded));
            edt(() -> { int n = f.active.getBuffer().getDocument().getLength(); f.active.surface().select(n, n); return null; });
            assertEquals(f.observe().get("documentLength"), f.observe().get("caretOffset"));
        }
    }

    @Test void observationPreservesTextRevisionDirtyHistorySelectionViewportFocusAndListeners() throws Exception {
        try (var f = new Fixture(temporary)) {
            var before = edt(() -> {
                var buffer = f.active.getBuffer(); buffer.getDocument().insertString(0, "human ", null);
                buffer.getDocument().insertString(0, "draft ", null); buffer.getUndoManager().undo(); buffer.setModified(true);
                f.active.surface().select(3, 1);
                assertTrue(buffer.getUndoManager().canUndo()); assertTrue(buffer.getUndoManager().canRedo());
                return List.of(buffer.getContent(), buffer.documentRevision(), buffer.isModified(), buffer.getUndoManager(), buffer.getUndoManager().canUndo(), buffer.getUndoManager().canRedo(),
                    f.active.surface().selection(), f.active.getScrollPane().getViewport().getViewPosition(), buffer.getDocument().getDocumentListeners().length,
                    buffer.textSnapshot());
            });
            var focus = java.awt.KeyboardFocusManager.getCurrentKeyboardFocusManager().getFocusOwner();
            for (int i = 0; i < 80; i++) f.observe();
            var after = edt(() -> {
                var buffer = f.active.getBuffer();
                return List.of(buffer.getContent(), buffer.documentRevision(), buffer.isModified(), buffer.getUndoManager(), buffer.getUndoManager().canUndo(), buffer.getUndoManager().canRedo(),
                    f.active.surface().selection(), f.active.getScrollPane().getViewport().getViewPosition(), buffer.getDocument().getDocumentListeners().length,
                    buffer.textSnapshot());
            });
            assertEquals(before, after); assertSame(focus, java.awt.KeyboardFocusManager.getCurrentKeyboardFocusManager().getFocusOwner());
            assertEquals("a😀é\nbody\n", Files.readString(f.root.resolve("private-name.java")));
        }
    }

    @Test void negativeImpliedEofReversedAndNonEndpointCaretAreInvalid() {
        for (int[] input : List.of(new int[]{430, -1, 0, 0}, new int[]{430, 431, 431, 431}, new int[]{430, 1, -1, 1},
            new int[]{430, 1, 2, 1}, new int[]{430, 2, 1, 3}))
            fails("INVALID_CONTEXT", () -> EditorMetadata.validateCoordinates(input[0], 0, input[1], input[2], input[3]));
        EditorMetadata.validateCoordinates(0, 0, 0, 0, 0); EditorMetadata.validateCoordinates(430, 2, 430, 0, 430);
        fails("INVALID_CONTEXT", () -> EditorMetadata.validateCoordinates(430, -1, 0, 0, 0));
    }

    @Test void statusSessionCannotObserveOrElevateAndSchemasFailClosed() throws Exception {
        try (var f = new Fixture(temporary)) {
            var status = new WorkspaceRpc(f.application); var rpc = new WorkspaceRpc(f.application, f.metadata);
            assertEquals("PERMISSION_DENIED", WorkspaceStatusContractTest.code(status.handle(request(f.params()))));
            assertEquals("INVALID_REQUEST", WorkspaceStatusContractTest.code(status.handle(WorkspaceRpcTest.request("status", "editor/status",
                Map.of("schemaVersion", 1, "capabilities", List.of("editor-metadata"))))));
            for (var params : List.of(Map.<String,Object>of("schemaVersion", 1L), Map.<String,Object>of("schemaVersion", 1L,"projectId",f.metadata.projectId,"offset",0),
                Map.<String,Object>of("schemaVersion",1L,"projectId",f.metadata.projectId,"expectedPaneId",-1)))
                assertEquals("INVALID_REQUEST", WorkspaceStatusContractTest.code(rpc.handle(request(params))));
            assertEquals("UNSUPPORTED_PROTOCOL", WorkspaceStatusContractTest.code(rpc.handle(request(Map.of("schemaVersion",2,"projectId",f.metadata.projectId)))));
            assertEquals("PROJECT_UNAUTHORIZED", WorkspaceStatusContractTest.code(rpc.handle(request(Map.of("schemaVersion",1,"projectId","project-00000000-0000-0000-0000-000000000000")))));
            for (String method : List.of("documents/read", "documents/list", "editor/cursor/set", "editor/edit", "editor/save", "editor/capture", "commands/execute"))
                assertEquals("UNSUPPORTED", WorkspaceStatusContractTest.code(rpc.handle(WorkspaceStatusContractTest.request(method))));
        }
    }

    @Test void readinessNoEditorDisposedPaneAndShutdownReturnExplicitErrors() throws Exception {
        try (var f = new Fixture(temporary)) {
            var starting = new ApplicationStatus(false);
            try (var m = new EditorMetadata(starting, new EditorMetadata.Approval(f.root, f.roots.generation()), f::capture)) {
                fails("APP_NOT_READY", () -> m.observe(Map.of("schemaVersion",1L,"projectId",m.projectId)));
            }
            var pane = f.active;
            edt(() -> { f.active = null; return null; }); fails("NO_ACTIVE_EDITOR", f::observe);
            edt(() -> { f.active = pane; pane.disposeSourceView(); return null; }); fails("PANE_UNAVAILABLE", f::observe);
            f.application.stopping(); fails("APP_SHUTTING_DOWN", f::observe);
        }
    }

    @Test void projectChangesRevokeAndUnrelatedOrNestedProjectsAreDenied() throws Exception {
        try (var f = new Fixture(temporary)) {
            Path outside = Files.writeString(temporary.resolve("unapproved.java"), "secret unrelated source");
            edt(() -> { f.add(outside); return null; }); fails("PROJECT_UNAUTHORIZED", f::observe);
            edt(() -> { f.active = f.panes.getFirst(); f.roots.activate(f.root); f.roots.remove(f.root); f.roots.add(f.root); return null; });
            fails("AUTHORIZATION_REVOKED", f::observe);
        }
        Path parent = Files.createDirectory(temporary.resolve("nested-test"));
        try (var f = new Fixture(parent)) {
            Path nested = Files.createDirectory(f.root.resolve("nested")); Path child = Files.writeString(nested.resolve("code"), "other project");
            edt(() -> { f.roots.add(nested); f.add(child); return null; });
            try (var m = new EditorMetadata(f.application, new EditorMetadata.Approval(f.root,f.roots.generation()),f::capture)) {
                fails("PROJECT_UNAUTHORIZED", () -> m.observe(Map.of("schemaVersion",1L,"projectId",m.projectId)));
            }
        }
    }

    @Test void rootReplacementAndSymlinkDocumentAreRejected() throws Exception {
        try (var f = new Fixture(temporary)) {
            Path original = f.root.resolveSibling("original"); Files.move(f.root, original); Files.createDirectory(f.root);
            fails("AUTHORIZATION_REVOKED", f::observe);
        }
        Path parent = Files.createDirectory(temporary.resolve("symlink-test"));
        try (var f = new Fixture(parent)) {
            Path file = f.root.resolve("private-name.java"); Files.delete(file);
            Files.createSymbolicLink(file, Files.writeString(parent.resolve("outside"), "outside"));
            fails("PROJECT_UNAUTHORIZED", f::observe);
        }
    }

    @Test void environmentGitAndRecoveryBuffersAreOutsideObservationScope() throws Exception {
        try (var f=new Fixture(temporary)) {
            for (String name:List.of(".env","production.env",".env.production",".git/config",".shed-edit-private/source")) {
                Path file=f.root.resolve(name);Files.createDirectories(file.getParent());Files.writeString(file,"synthetic private contents");
                edt(()->{f.add(file);return null;});fails("PROJECT_UNAUTHORIZED",f::observe);
            }
        }
    }

    @Test void splitsAndDocumentSwitchesHaveLiveOpaqueLifetimeIdentities() throws Exception {
        try (var f = new Fixture(temporary)) {
            var first = f.observe(); EditorPane left = f.active;
            edt(() -> { f.split(left.getBuffer()); f.active.surface().select(1,1); return null; });
            var second = f.observe(); assertEquals(first.get("documentId"), second.get("documentId")); assertNotEquals(first.get("paneId"),second.get("paneId"));
            fails("PANE_UNAVAILABLE", () -> f.metadata.observe(Map.of("schemaVersion",1L,"projectId",f.metadata.projectId,"expectedPaneId", first.get("paneId"))));
            edt(() -> { var right=f.active; f.active=left; f.panes.remove(right); right.disposeSourceView(); return null; });
            assertEquals(first.get("paneId"),f.observe().get("paneId"));
            Path other = Files.writeString(f.root.resolve("other"), "second");
            edt(() -> { f.add(other); return null; });
            fails("CONTEXT_CHANGED", () -> f.metadata.observe(Map.of("schemaVersion",1L,"projectId",f.metadata.projectId,"expectedDocumentId",first.get("documentId"))));
            edt(() -> { left.disposeSourceView(); f.panes.remove(left); return null; }); assertTrue(f.observe().containsKey("paneId"));
            edt(() -> { f.buffers.remove(f.active.getBuffer()); return null; }); fails("CONTEXT_CHANGED", f::observe);
        }
    }

    @Test void scratchPreviewAlternateAndMismatchedDocumentAreNotSourceEditors() throws Exception {
        try (var f = new Fixture(temporary)) {
            EditorPane source = f.active;
            edt(() -> { source.setMarkdownPreviewComponent(new javax.swing.JPanel()); return null; }); fails("NO_ACTIVE_EDITOR", f::observe);
            edt(() -> { source.clearMarkdownPreviewComponent(); source.setAlternateComponent(new javax.swing.JPanel()); return null; }); fails("NO_ACTIVE_EDITOR",f::observe);
            edt(() -> { source.clearAlternateComponent(); source.getTextArea().setDocument(new javax.swing.text.PlainDocument()); return null; }); fails("CONTEXT_CHANGED",f::observe);
            edt(() -> { f.split(FileBuffer.createScratch("private draft","secret draft")); return null; }); fails("CONTEXT_CHANGED",f::observe);
        }
    }

    @Test void humanChangesBetweenCapturesNeverMixStates() throws Exception {
        try (var f = new Fixture(temporary)) {
            var count = new AtomicInteger();
            try (var m = new EditorMetadata(f.application,new EditorMetadata.Approval(f.root,f.roots.generation()),() -> {
                var snapshot=f.capture();
                if (count.incrementAndGet()==1) SwingUtilities.invokeLater(() -> f.active.surface().select(1,1));
                return snapshot;
            })) {
                fails("CONTEXT_CHANGED", () -> m.observe(Map.of("schemaVersion",1L,"projectId",m.projectId)));
                assertEquals(1, edt(() -> f.active.getTextArea().getCaretPosition()));
            }
        }
    }

    @Test void sameLengthHumanEditAndShutdownBetweenCapturesAreRejected() throws Exception {
        try (var f = new Fixture(temporary)) {
            var count = new AtomicInteger();
            try (var m = new EditorMetadata(f.application,new EditorMetadata.Approval(f.root,f.roots.generation()),() -> {
                var snapshot=f.capture();
                if (count.incrementAndGet()==1) SwingUtilities.invokeLater(() -> {
                    try { var document=f.active.getBuffer().getDocument();document.remove(0,1);document.insertString(0,"b",null); }
                    catch(javax.swing.text.BadLocationException e) { throw new AssertionError(e); }
                });
                return snapshot;
            })) { fails("CONTEXT_CHANGED", () -> m.observe(Map.of("schemaVersion",1L,"projectId",m.projectId))); }
            count.set(0);
            try (var m = new EditorMetadata(f.application,new EditorMetadata.Approval(f.root,f.roots.generation()),() -> {
                var snapshot=f.capture(); if(count.incrementAndGet()==1)f.application.stopping();return snapshot;
            })) { fails("APP_SHUTTING_DOWN", () -> m.observe(Map.of("schemaVersion",1L,"projectId",m.projectId))); }
        }
    }

    @Test void closedAndReopenedDocumentGetsANewIdentityAndNoProjectIsDisclosed() throws Exception {
        try (var f = new Fixture(temporary)) {
            var first=f.observe();Path file=f.root.resolve("private-name.java");
            edt(() -> {var pane=f.active;pane.disposeSourceView();f.panes.remove(pane);f.buffers.clear();f.active=null;f.add(file);return null;});
            assertNotEquals(first.get("documentId"),f.observe().get("documentId"));
            edt(() -> {f.roots.remove(f.root);return null;});fails("AUTHORIZATION_REVOKED",f::observe);
        }
    }

    @Test void blockedEdtTimesOutCancelsCaptureAndBoundsConcurrentAdmission() throws Exception {
        try (var f = new Fixture(temporary)) {
            CountDownLatch occupied=new CountDownLatch(1),release=new CountDownLatch(1);
            var captures=new AtomicInteger();
            try (var m=new EditorMetadata(f.application,new EditorMetadata.Approval(f.root,f.roots.generation()),() -> { captures.incrementAndGet();return f.capture(); })) {
                SwingUtilities.invokeLater(() -> { occupied.countDown(); try { assertTrue(release.await(3,TimeUnit.SECONDS)); } catch(InterruptedException e) { throw new AssertionError(e); } });
                assertTrue(occupied.await(1,TimeUnit.SECONDS));
                try (var worker=java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
                    var pending=worker.submit(() -> { fails("TIMEOUT",()->m.observe(Map.of("schemaVersion",1L,"projectId",m.projectId)));return true; });
                    Thread.sleep(30);
                    fails("BUSY",()->m.observe(Map.of("schemaVersion",1L,"projectId",m.projectId)));
                    assertTrue(pending.get(1,TimeUnit.SECONDS));
                    for (int i=0;i<20;i++) fails("BUSY",()->m.observe(Map.of("schemaVersion",1L,"projectId",m.projectId)));
                } finally { release.countDown(); }
                edt(()->null);assertEquals(0,captures.get());
                assertTrue(m.observe(Map.of("schemaVersion",1L,"projectId",m.projectId)).containsKey("paneId"));
                m.close(); fails("AUTHORIZATION_REVOKED",()->m.observe(Map.of("schemaVersion",1L,"projectId",m.projectId)));
            } finally { release.countDown(); }
        }
    }
}
