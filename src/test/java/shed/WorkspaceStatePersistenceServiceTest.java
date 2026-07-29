package shed;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

public class WorkspaceStatePersistenceServiceTest {
    @TempDir
    Path tempDir;

    @Test
    void savesValidatedStateOffEdtWithAtomicWriter() throws Exception {
        Path target = tempDir.resolve("state/workspace.json");
        WorkspaceState state = workspace("draft");
        CountDownLatch saved = new CountDownLatch(1);
        AtomicBoolean callbackOnEdt = new AtomicBoolean();
        WorkspaceStatePersistenceService service = new WorkspaceStatePersistenceService(target, new WorkspaceStatePersistenceService.Observer() {
            @Override
            public void onSaved(WorkspaceState savedState) {
                callbackOnEdt.set(SwingUtilities.isEventDispatchThread());
                saved.countDown();
            }

            @Override
            public void onFailure(WorkspaceState failedState, Exception error) {
            }
        });

        try {
            assertTrue(service.requestSave(state));

            assertTrue(saved.await(2, TimeUnit.SECONDS));
            assertFalse(callbackOnEdt.get());
            assertEquals(state, WorkspaceState.parse(Files.readString(target)));
        } finally {
            service.close();
        }
    }

    @Test
    void queuesOnlyLatestPendingStateWithoutBlockingEdtInput() throws Exception {
        Path target = tempDir.resolve("workspace.json");
        WorkspaceState first = workspace("first");
        WorkspaceState skipped = workspace("skipped");
        WorkspaceState latest = workspace("latest");
        CountDownLatch firstWriteStarted = new CountDownLatch(1);
        CountDownLatch releaseFirstWrite = new CountDownLatch(1);
        CountDownLatch writesFinished = new CountDownLatch(2);
        List<WorkspaceState> written = new ArrayList<>();
        WorkspaceStatePersistenceService service = new WorkspaceStatePersistenceService(target, (path, content) -> {
            WorkspaceState state = WorkspaceState.parse(new String(content, java.nio.charset.StandardCharsets.UTF_8));
            synchronized (written) {
                written.add(state);
                if (written.size() == 1) {
                    firstWriteStarted.countDown();
                }
            }
            if (state.equals(first)) {
                try {
                    if (!releaseFirstWrite.await(2, TimeUnit.SECONDS)) {
                        throw new IOException("first workspace write was not released");
                    }
                } catch (InterruptedException error) {
                    Thread.currentThread().interrupt();
                    throw new IOException("first workspace write was interrupted", error);
                }
            }
        }, new WorkspaceStatePersistenceService.Observer() {
            @Override
            public void onSaved(WorkspaceState state) {
                writesFinished.countDown();
            }

            @Override
            public void onFailure(WorkspaceState state, Exception error) {
            }
        });

        try {
            assertTrue(service.requestSave(first));
            assertTrue(firstWriteStarted.await(2, TimeUnit.SECONDS));

            SwingUtilities.invokeAndWait(() -> {
                assertTrue(service.requestSave(skipped));
                assertTrue(service.requestSave(latest));
            });
            releaseFirstWrite.countDown();

            assertTrue(writesFinished.await(2, TimeUnit.SECONDS));
            synchronized (written) {
                assertEquals(List.of(first, latest), written);
            }
        } finally {
            releaseFirstWrite.countDown();
            service.close();
        }
    }

    @Test
    void reportsWriterFailureWithoutAcceptingFurtherSavesAfterClose() throws Exception {
        AtomicReference<Exception> failure = new AtomicReference<>();
        CountDownLatch reported = new CountDownLatch(1);
        WorkspaceStatePersistenceService service = new WorkspaceStatePersistenceService(tempDir.resolve("workspace.json"),
            (path, content) -> { throw new IOException("disk unavailable"); }, new WorkspaceStatePersistenceService.Observer() {
                @Override
                public void onSaved(WorkspaceState state) {
                }

                @Override
                public void onFailure(WorkspaceState state, Exception error) {
                    failure.set(error);
                    reported.countDown();
                }
            });

        assertTrue(service.requestSave(workspace("draft")));
        assertTrue(reported.await(2, TimeUnit.SECONDS));
        assertEquals("disk unavailable", failure.get().getMessage());

        service.close();
        assertFalse(service.requestSave(workspace("later")));
    }

    private WorkspaceState workspace(String content) {
        Path root = tempDir.resolve("project").toAbsolutePath();
        Path file = root.resolve("notes.txt");
        return new WorkspaceState(
            List.of(root.toString()),
            List.of(new WorkspaceState.BufferState("file-1", WorkspaceState.BufferKind.FILE, file.toString(), null, true, content)),
            List.of(new WorkspaceState.PaneState("pane-1", "file-1", 0)),
            new WorkspaceState.ActiveSelection("pane-1", "file-1", 0),
            List.of(new WorkspaceState.ToolState("tree", Map.of("root", root.toString())))
        );
    }
}
