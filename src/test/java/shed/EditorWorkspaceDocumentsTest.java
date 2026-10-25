package shed;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class EditorWorkspaceDocumentsTest {
    @TempDir Path root;

    @Test void anEarlierAsyncSaveCompletionCannotReplaceARestoredDiskBinding() throws Exception {
        Path file = Files.writeString(root.resolve("source"), "old\r\n"); FileBuffer buffer = new FileBuffer(file.toFile());
        try (var source = new EditorWorkspaceDocuments(() -> List.of(buffer), () -> List.of(root), () -> root, root)) {
            SwingUtilities.invokeAndWait(() -> buffer.replaceContentUndoably("saved first\n"));
            var pending = buffer.captureSaveSnapshot(null); buffer.writeSaveSnapshot(pending);
            var before = source.snapshots().getFirst();
            Files.writeString(file, "restored\r\n");
            source.applyTexts(List.of(new WorkspaceDocumentEditor.TextChange(before, "restored\n", WorkspaceDiskBinding.capture(before.workspaceId(), file))));
            SwingUtilities.invokeAndWait(() -> buffer.completeSaveSnapshot(pending));
            assertEquals("restored\n", buffer.getSavedContent()); assertFalse(buffer.isModified());
            assertEquals(FileBuffer.ExternalFileState.UNCHANGED, buffer.getExternalFileState());
            SwingUtilities.invokeAndWait(() -> buffer.replaceContentUndoably("human next\n")); buffer.save();
            assertEquals("human next\r\n", Files.readString(file));
        }
    }

    @Test void diskBindingAdoptionKeepsRestoredBuffersSaveableAndRetargetsWithoutLosingIdentity() throws Exception {
        Path file = Files.writeString(root.resolve("source"), "agent\r\n");
        FileBuffer buffer = new FileBuffer(file.toFile());
        try (var source = new EditorWorkspaceDocuments(() -> List.of(buffer), () -> List.of(root), () -> root, root)) {
            var before = source.snapshots().getFirst();
            Files.writeString(file, "original\r\n");
            var binding = WorkspaceDiskBinding.capture(before.workspaceId(), file);
            var restored = source.applyTexts(List.of(new WorkspaceDocumentEditor.TextChange(before, "original\n", binding))).getFirst();
            assertFalse(restored.dirty()); assertEquals("original\n", buffer.getSavedContent());
            assertEquals(FileBuffer.ExternalFileState.UNCHANGED, buffer.getExternalFileState());
            SwingUtilities.invokeAndWait(() -> buffer.replaceContentUndoably("human\n")); buffer.save();
            assertEquals("human\r\n", Files.readString(file));
            var current = source.snapshots().getFirst(); Path renamed = root.resolve("renamed"); Files.move(file, renamed);
            var moved = source.applyTexts(List.of(new WorkspaceDocumentEditor.TextChange(current, current.content(),
                WorkspaceDiskBinding.capture(current.workspaceId(), renamed)))).getFirst();
            assertEquals(before.documentId(), moved.documentId()); assertEquals(renamed.toUri().toString(), moved.uri());
            assertEquals(FileBuffer.ExternalFileState.UNCHANGED, buffer.getExternalFileState());
            SwingUtilities.invokeAndWait(() -> buffer.replaceContentUndoably("next\n")); buffer.save();
            assertEquals("next\r\n", Files.readString(renamed)); assertFalse(Files.exists(file));
        }
    }

    @Test void aFailedBatchRestoresBindingsAsWellAsTextAndPreservesHumanFormatChoices() throws Exception {
        Path original = Files.writeString(root.resolve("original"), "old\n"), target = Files.writeString(root.resolve("target"), "new\n");
        FileBuffer first = new FileBuffer(original.toFile()), second = FileBuffer.createScratch("Other", "two");
        try (var source = new EditorWorkspaceDocuments(() -> List.of(first, second), () -> List.of(root), () -> root, root)) {
            var before = source.snapshots();
            SwingUtilities.invokeAndWait(() -> second.getDocument().setDocumentFilter(new javax.swing.text.DocumentFilter() {
                @Override public void insertString(FilterBypass bypass, int offset, String text, javax.swing.text.AttributeSet attributes)
                    throws javax.swing.text.BadLocationException { throw new javax.swing.text.BadLocationException("fixture failure", offset); }
            }));
            assertThrows(WorkspaceApiException.class, () -> source.applyTexts(List.of(
                new WorkspaceDocumentEditor.TextChange(before.get(0), "new\n", WorkspaceDiskBinding.capture(before.get(0).workspaceId(), target)),
                new WorkspaceDocumentEditor.TextChange(before.get(1), "changed"))));
            assertEquals(original.toFile(), first.getFile()); assertEquals("old\n", first.getSavedContent());
            assertEquals("old\n", first.getContent()); assertFalse(first.isModified());
            assertEquals(FileBuffer.ExternalFileState.UNCHANGED, first.getExternalFileState());
            SwingUtilities.invokeAndWait(() -> first.setLineEnding("\r\n"));
            var current = source.snapshots().getFirst();
            source.applyTexts(List.of(new WorkspaceDocumentEditor.TextChange(current, current.content(), WorkspaceDiskBinding.capture(current.workspaceId(), original))));
            assertTrue(first.isModified(), "Adoption must not clear an unsaved human newline preference");
        }
    }

    @Test void capturesActualUnsavedFileBuffersOnEdtAndKeepsVersionsStableUntilStateChanges() throws Exception {
        Path file = Files.writeString(root.resolve("source.txt"), "saved\r\n");
        AtomicReference<FileBuffer> buffer = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> {
            try { buffer.set(new FileBuffer(file.toFile())); buffer.get().setContent("human draft\n"); }
            catch (Exception error) { throw new RuntimeException(error); }
        });
        try (EditorWorkspaceDocuments source = new EditorWorkspaceDocuments(() -> {
            assertTrue(SwingUtilities.isEventDispatchThread()); return List.of(buffer.get());
        }, () -> List.of(root), () -> root, root)) {
            var service = new WorkspaceQueryService(root, AgentPolicy.readOnly(), source);
            var first = source.snapshots().getFirst(); var second = source.snapshots().getFirst();
            assertEquals(first.documentId(), second.documentId()); assertEquals(first.version(), second.version());
            assertEquals("CRLF", first.lineEnding()); assertTrue(first.dirty());
            assertEquals("human draft\n", service.read(service.workspaceId(), file.toString(), first.version(), 0, 100).get("content"));
            assertEquals("saved\r\n", Files.readString(file));
            SwingUtilities.invokeAndWait(() -> buffer.get().replaceContentUndoably("newer edit\n"));
            var changed = source.snapshots().getFirst();
            assertEquals(first.documentId(), changed.documentId()); assertNotEquals(first.version(), changed.version());
            assertEquals(WorkspaceApiException.Code.STALE_VERSION,
                assertThrows(WorkspaceApiException.class, () -> service.read(service.workspaceId(), first.documentId(), first.version(), 0, 100)).code());
        }
    }

    @Test void scratchOwnershipDoesNotFollowLaterTabOrWorkspaceSelection() throws Exception {
        Path other = Files.createDirectory(root.resolve("other"));
        AtomicReference<Path> selected = new AtomicReference<>(root);
        List<FileBuffer> buffers = new ArrayList<>();
        SwingUtilities.invokeAndWait(() -> buffers.add(FileBuffer.createScratch("Notes", "draft")));
        var source = new EditorWorkspaceDocuments(() -> buffers, () -> List.of(root, other), selected::get, root);
        var first = source.snapshots().getFirst(); selected.set(other);
        assertEquals(first.workspaceId(), source.snapshots().getFirst().workspaceId());
        SwingUtilities.invokeAndWait(() -> buffers.add(FileBuffer.createScratch("Other notes", "other draft")));
        var all = source.snapshots(); assertNotEquals(all.get(0).workspaceId(), all.get(1).workspaceId());
        source.close(); assertFalse(source.connected());
        assertEquals(WorkspaceApiException.Code.NOT_CONNECTED, assertThrows(WorkspaceApiException.class, source::snapshots).code());
    }

    @Test void capturesInsideEdtDoNotDeadlockAndFormatChangesAdvanceVersions() throws Exception {
        List<FileBuffer> buffers = new ArrayList<>();
        var source = new EditorWorkspaceDocuments(() -> buffers, () -> List.of(root), () -> root, root);
        SwingUtilities.invokeAndWait(() -> {
            FileBuffer buffer = FileBuffer.createScratch("Notes", "text"); buffers.add(buffer);
            var before = source.snapshots().getFirst();
            buffer.setEncoding("UTF-16");
            var after = source.snapshots().getFirst();
            assertNotEquals(before.version(), after.version()); assertTrue(after.byteOrderMark());
        });
        source.close();
    }

    @Test void batchesKeepSelectionAndNativeUndoWhileTargetingTheNamedDocument() throws Exception {
        FileBuffer buffer = FileBuffer.createScratch("Notes", "first\nmiddle\nlast\n");
        var area = new javax.swing.JTextArea();
        var scroll = new javax.swing.JScrollPane(area);
        SwingUtilities.invokeAndWait(() -> { area.setDocument(buffer.getDocument()); area.select(13, 17); });
        try (var source = new EditorWorkspaceDocuments(() -> List.of(buffer), () -> List.of(root), () -> root, root, action -> {
            var view = BufferViewState.capture(area, scroll);
            String original = area.getText();
            try { action.run(); } finally { view.restoreSelectionAfterChanges(area, original, area.getText()); view.restoreViewport(scroll); }
        })) {
            var before = source.snapshots().getFirst();
            var after = source.applyTexts(List.of(new WorkspaceDocumentEditor.TextChange(before, "FIRST longer\nmiddle\nLAST\n"))).getFirst();
            assertEquals("FIRST longer\nmiddle\nLAST\n", after.content()); assertTrue(after.dirty());
            SwingUtilities.invokeAndWait(() -> {
                assertEquals(20, area.getSelectionStart()); assertEquals(24, area.getSelectionEnd());
                buffer.getUndoManager().undo();
                assertEquals("first\nmiddle\nlast\n", buffer.getContent());
            });
        }
    }

    @Test void rawDocumentEditsAndUndoneChangesStillInvalidateOpaqueVersions() throws Exception {
        FileBuffer buffer = FileBuffer.createScratch("Notes", "abc");
        try (var source = new EditorWorkspaceDocuments(() -> List.of(buffer), () -> List.of(root), () -> root, root)) {
            var before = source.snapshots().getFirst();
            SwingUtilities.invokeAndWait(() -> {
                try { buffer.getDocument().remove(1, 1); buffer.getDocument().insertString(1, "Z", null); }
                catch (Exception failure) { throw new RuntimeException(failure); }
            });
            var changed = source.snapshots().getFirst();
            assertEquals("aZc", changed.content()); assertNotEquals(before.version(), changed.version());
            SwingUtilities.invokeAndWait(() -> {
                try { buffer.getDocument().remove(1, 1); buffer.getDocument().insertString(1, "b", null); }
                catch (Exception failure) { throw new RuntimeException(failure); }
            });
            assertEquals("abc", source.snapshots().getFirst().content());
            assertNotEquals(before.version(), source.snapshots().getFirst().version());
            assertEquals(WorkspaceApiException.Code.STALE_VERSION, assertThrows(WorkspaceApiException.class,
                () -> source.applyTexts(List.of(new WorkspaceDocumentEditor.TextChange(before, "agent")))).code());
        }
    }

    @Test void staleBatchHasNoEffectsAndFailedBatchPreservesEarlierHumanUndoHistory() throws Exception {
        FileBuffer first = FileBuffer.createScratch("First", "first"), second = FileBuffer.createScratch("Second", "second");
        SwingUtilities.invokeAndWait(() -> first.replaceContentUndoably("human first"));
        try (var source = new EditorWorkspaceDocuments(() -> List.of(first, second), () -> List.of(root), () -> root, root)) {
            var before = source.snapshots();
            SwingUtilities.invokeAndWait(() -> second.replaceContentUndoably("human second"));
            assertEquals(WorkspaceApiException.Code.STALE_VERSION, assertThrows(WorkspaceApiException.class, () -> source.applyTexts(List.of(
                new WorkspaceDocumentEditor.TextChange(before.get(0), "agent first"),
                new WorkspaceDocumentEditor.TextChange(before.get(1), "agent second")))).code());
            assertEquals("human first", first.getContent());
            var current = source.snapshots();
            SwingUtilities.invokeAndWait(() -> second.getDocument().setDocumentFilter(new javax.swing.text.DocumentFilter() {
                @Override public void insertString(FilterBypass bypass, int offset, String text, javax.swing.text.AttributeSet attributes)
                    throws javax.swing.text.BadLocationException { throw new javax.swing.text.BadLocationException("fixture failure", offset); }
            }));
            assertThrows(WorkspaceApiException.class, () -> source.applyTexts(List.of(
                new WorkspaceDocumentEditor.TextChange(current.get(0), "agent first"),
                new WorkspaceDocumentEditor.TextChange(current.get(1), "agent second"))));
            assertEquals("human first", first.getContent()); assertEquals("human second", second.getContent());
            SwingUtilities.invokeAndWait(() -> {
                first.getUndoManager().undo(); assertEquals("first", first.getContent());
                first.getUndoManager().redo(); assertEquals("human first", first.getContent());
            });
        }
    }

    @Test void cancellationOfAQueuedBatchCannotMutateLaterWhenTheEventThreadBecomesAvailable() throws Exception {
        FileBuffer buffer = FileBuffer.createScratch("Notes", "before");
        var blocked = new java.util.concurrent.CountDownLatch(1);
        var release = new java.util.concurrent.CountDownLatch(1);
        try (var source = new EditorWorkspaceDocuments(() -> List.of(buffer), () -> List.of(root), () -> root, root)) {
            var before = source.snapshots().getFirst();
            SwingUtilities.invokeLater(() -> {
                blocked.countDown();
                try { release.await(5, java.util.concurrent.TimeUnit.SECONDS); }
                catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
            });
            assertTrue(blocked.await(2, java.util.concurrent.TimeUnit.SECONDS));
            var outcome = new java.util.concurrent.CompletableFuture<WorkspaceApiException.Code>();
            Thread caller = Thread.ofVirtual().start(() -> {
                try { source.applyTexts(List.of(new WorkspaceDocumentEditor.TextChange(before, "late edit"))); outcome.complete(null); }
                catch (WorkspaceApiException failure) { outcome.complete(failure.code()); }
            });
            try {
                caller.interrupt();
                assertEquals(WorkspaceApiException.Code.CANCELLED, outcome.get(2, java.util.concurrent.TimeUnit.SECONDS));
            } finally { release.countDown(); }
            SwingUtilities.invokeAndWait(() -> { });
            assertEquals("before", buffer.getContent());
        } finally { release.countDown(); }
    }

    @Test void anExecutingBatchReturnsItsActualOutcomeAndRestoresTheCallersInterruptStatus() throws Exception {
        FileBuffer buffer = FileBuffer.createScratch("Notes", "before");
        var started = new java.util.concurrent.CountDownLatch(1);
        var release = new java.util.concurrent.CountDownLatch(1);
        try (var source = new EditorWorkspaceDocuments(() -> List.of(buffer), () -> List.of(root), () -> root, root, action -> {
            started.countDown();
            try { release.await(5, java.util.concurrent.TimeUnit.SECONDS); }
            catch (InterruptedException interrupted) { throw new RuntimeException(interrupted); }
            action.run();
        })) {
            var before = source.snapshots().getFirst();
            var outcome = new java.util.concurrent.CompletableFuture<Boolean>();
            Thread caller = Thread.ofVirtual().start(() -> {
                try {
                    source.applyTexts(List.of(new WorkspaceDocumentEditor.TextChange(before, "accepted")));
                    outcome.complete(Thread.currentThread().isInterrupted());
                } catch (Throwable failure) { outcome.completeExceptionally(failure); }
            });
            try {
                assertTrue(started.await(2, java.util.concurrent.TimeUnit.SECONDS)); caller.interrupt();
            } finally { release.countDown(); }
            assertTrue(outcome.get(2, java.util.concurrent.TimeUnit.SECONDS));
            assertEquals("accepted", buffer.getContent());
        } finally { release.countDown(); }
    }
}
