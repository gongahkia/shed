package shed;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

import java.awt.GraphicsEnvironment;
import java.lang.reflect.InvocationTargetException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.Callable;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

public class TexteditorSwingIntegrationTest {
    @TempDir
    Path tempDir;

    @Test
    void openEditWriteRoundTrip() throws Exception {
        assumeSwingAvailable();
        Path home = tempDir.resolve("home-open-write");
        Path file = tempDir.resolve("note.txt");
        Files.createDirectories(home);
        Files.writeString(file, "old\n", StandardCharsets.UTF_8);

        Texteditor editor = createEditor(home, file);
        try {
            String result = onEdt(() -> {
                editor.writingArea.setText("new\ncontent\n");
                return editor.commandHandler.execute("w");
            });

            assertTrue(result.contains("written"), result);
            assertEquals("new\ncontent\n", Files.readString(file, StandardCharsets.UTF_8));
            assertFalse(onEdt(() -> editor.getCurrentBuffer().isModified()));
        } finally {
            disposeEditor(editor);
        }
    }

    @Test
    void splitCloseFocusPreservesActiveBuffer() throws Exception {
        assumeSwingAvailable();
        Path home = tempDir.resolve("home-split");
        Path file = tempDir.resolve("split.txt");
        Files.createDirectories(home);
        Files.writeString(file, "one\ntwo\n", StandardCharsets.UTF_8);

        Texteditor editor = createEditor(home, file);
        try {
            FileBuffer initial = onEdt(editor::getCurrentBuffer);
            String split = onEdt(() -> editor.commandHandler.execute("vsplit"));
            assertEquals("Vertical split created", split);
            assertEquals(2, onEdt(() -> editor.editorPanes.size()));
            assertSame(initial, onEdt(editor::getCurrentBuffer));

            String close = onEdt(() -> editor.commandHandler.execute("close"));
            assertEquals("Window closed", close);
            assertEquals(1, onEdt(() -> editor.editorPanes.size()));
            assertSame(initial, onEdt(editor::getCurrentBuffer));
        } finally {
            disposeEditor(editor);
        }
    }

    @Test
    void quickfixJumpOpensTargetAndMovesCaret() throws Exception {
        assumeSwingAvailable();
        Path home = tempDir.resolve("home-quickfix");
        Path start = tempDir.resolve("start.txt");
        Path target = tempDir.resolve("target.txt");
        Files.createDirectories(home);
        Files.writeString(start, "start\n", StandardCharsets.UTF_8);
        Files.writeString(target, "alpha\nbravo\ncharlie\n", StandardCharsets.UTF_8);

        Texteditor editor = createEditor(home, start);
        try {
            onEdt(() -> {
                editor.updateQuickfixEntries("test", List.of(new QuickfixService.Entry(target.toString(), 2, 3, "jump", "test")));
                assertEquals("Quickfix opened", editor.commandHandler.execute("copen"));
                return null;
            });

            String result = onEdt(() -> editor.commandHandler.execute("cc 1"));
            assertEquals("Quickfix 1/1", result);
            assertEquals(target.toFile().getAbsolutePath(), onEdt(() -> editor.getCurrentBuffer().getFilePath()));
            assertEquals(8, onEdt(() -> editor.writingArea.getCaretPosition()));
        } finally {
            disposeEditor(editor);
        }
    }

    @Test
    void terminalOpenExitRemovesDirectPtyPane() throws Exception {
        assumeSwingAvailable();
        Path home = tempDir.resolve("home-terminal");
        Path file = tempDir.resolve("term.txt");
        Files.createDirectories(home);
        Files.writeString(file, "term\n", StandardCharsets.UTF_8);

        Texteditor editor = createEditor(home, file);
        try {
            String result = onEdt(() -> editor.commandHandler.execute("term"));
            assertEquals("Terminal opened", result);
            assertEquals(1, onEdt(() -> editor.ptyTerminalPanes.size()));
            assertNotNull(onEdt(() -> editor.getActivePane().getTerminalPane()));
            assertTrue(onEdt(() -> editor.getActivePane().getComponent() == editor.getActivePane().getTerminalPane().getComponent()));

            FileBuffer terminalBuffer = onEdt(editor::getCurrentBuffer);
            onEdt(() -> {
                editor.closeExitedTerminal(terminalBuffer);
                return null;
            });
            assertTrue(onEdt(() -> editor.ptyTerminalPanes.isEmpty()));
            assertEquals(1, onEdt(() -> editor.editorPanes.size()));
            assertFalse(onEdt(() -> editor.buffers.contains(terminalBuffer)));
        } finally {
            disposeEditor(editor);
        }
    }

    @Test
    void externalReloadUpdatesUnmodifiedBuffer() throws Exception {
        assumeSwingAvailable();
        Path home = tempDir.resolve("home-reload");
        Path file = tempDir.resolve("reload.txt");
        Files.createDirectories(home);
        Files.writeString(file, "before\n", StandardCharsets.UTF_8);

        Texteditor editor = createEditor(home, file);
        try {
            Thread.sleep(20);
            Files.writeString(file, "after\n", StandardCharsets.UTF_8);
            Files.setLastModifiedTime(file, FileTime.fromMillis(System.currentTimeMillis() + 3000));

            onEdt(() -> {
                editor.checkForExternalChanges();
                return null;
            });

            assertEquals("after\n", onEdt(() -> editor.getCurrentBuffer().getContent()));
            assertEquals("after\n", onEdt(() -> editor.writingArea.getText()));
            assertEquals("Auto-reloaded 1 externally changed buffer", onEdt(() -> editor.lastMessage));
        } finally {
            disposeEditor(editor);
        }
    }

    @Test
    void conflictSaveAsPreservesExternalVersion() throws Exception {
        assumeSwingAvailable();
        Path home = tempDir.resolve("home-conflict-save-as");
        Path source = tempDir.resolve("source.txt");
        Path target = tempDir.resolve("mine.txt");
        Files.createDirectories(home);
        Files.writeString(source, "before\n", StandardCharsets.UTF_8);

        Texteditor editor = createEditor(home, source);
        try {
            onEdt(() -> {
                editor.writingArea.setText("mine\n");
                return null;
            });
            Files.writeString(source, "theirs\n", StandardCharsets.UTF_8);

            onEdt(() -> {
                editor.recoveryController.saveConflictAs(editor.getCurrentBuffer(), target.toFile());
                return null;
            });

            assertEquals("theirs\n", Files.readString(source, StandardCharsets.UTF_8));
            assertEquals("mine\n", Files.readString(target, StandardCharsets.UTF_8));
            assertEquals(target.toFile().getAbsolutePath(), onEdt(() -> editor.getCurrentBuffer().getFilePath()));
            assertFalse(onEdt(() -> editor.getCurrentBuffer().isModified()));
            assertTrue(onEdt(() -> editor.lastMessage).startsWith("Saved conflict copy as "));
        } finally {
            disposeEditor(editor);
        }
    }

    @Test
    void lspStatusSmokeForUnconfiguredFile() throws Exception {
        assumeSwingAvailable();
        Path home = tempDir.resolve("home-lsp");
        Path file = tempDir.resolve("smoke.md");
        Files.createDirectories(home);
        Files.writeString(file, "# title\n", StandardCharsets.UTF_8);

        Texteditor editor = createEditor(home, file);
        try {
            String result = onEdt(() -> editor.handleLspCommand("status"));
            assertEquals("Showing LSP status", result);
            assertTrue(onEdt(() -> editor.getCurrentBuffer().getContent()).contains("LSP Server Status"));
        } finally {
            disposeEditor(editor);
        }
    }

    @Test
    void managedLspCommandsExposeInertStatusAndManualRemediation() throws Exception {
        assumeSwingAvailable();
        Path home = tempDir.resolve("home-managed-lsp");
        Path file = tempDir.resolve("managed.py");
        Files.createDirectories(home);
        Files.writeString(file, "print('shed')\n", StandardCharsets.UTF_8);

        Texteditor editor = createEditor(home, file);
        try {
            assertEquals("Showing managed LSP support", onEdt(() -> editor.handleLspCommand("manage")));
            assertTrue(onEdt(() -> editor.getCurrentBuffer().getContent()).contains("Managed LSP Support"));
            assertTrue(onEdt(() -> editor.getCurrentBuffer().getContent()).contains(":lsp manage detect <ext>"));

            assertEquals("Showing managed LSP install guidance", onEdt(() -> editor.handleLspCommand("manage install py")));
            assertTrue(onEdt(() -> editor.getCurrentBuffer().getContent()).contains("No download or update was started."));

            assertEquals("Showing manual LSP setup", onEdt(() -> editor.handleLspCommand("manage manual py")));
            assertTrue(onEdt(() -> editor.getCurrentBuffer().getContent()).contains("\"lsp.py.command\""));
        } finally {
            disposeEditor(editor);
        }
    }

    @Test
    void workspaceIndexControlsPersistOnlyExplicitPreference() throws Exception {
        assumeSwingAvailable();
        Path home = tempDir.resolve("home-workspace-index");
        Path file = tempDir.resolve("workspace-index.txt");
        Files.createDirectories(home);
        Files.writeString(file, "index\n", StandardCharsets.UTF_8);

        Texteditor editor = createEditor(home, file);
        try {
            String result = onEdt(() -> editor.commandHandler.execute("workspace index enable"));

            assertEquals("Persistent workspace indexing enabled", result);
            assertTrue(onEdt(() -> editor.configManager.getWorkspaceIndexEnabled()));
            assertTrue(onEdt(() -> editor.getCurrentBuffer().getContent()).contains("Current search source: persistent workspace index"));
            assertTrue(onEdt(() -> editor.getCurrentBuffer().getContent()).contains("Persistent-index preference: enabled"));
            assertFalse(Files.exists(home.resolve(".shed/workspace-index")));
        } finally {
            disposeEditor(editor);
        }
    }

    @Test
    void workspaceSearchAddsExactQuickfixLocationWithoutPersistentIndex() throws Exception {
        assumeSwingAvailable();
        Path home = tempDir.resolve("home-workspace-search");
        Path file = tempDir.resolve("workspace-search.txt");
        Files.createDirectories(home);
        Files.writeString(file, "alpha\nprefix needle\n", StandardCharsets.UTF_8);
        initializeGit(tempDir);

        Texteditor editor = createEditor(home, file);
        try {
            String result = onEdt(() -> editor.commandHandler.execute("grep needle"));
            int jobId = Integer.parseInt(result.substring("Started workspace search job ".length()));
            assertTrue(awaitSearchCompletion(editor, jobId));

            QuickfixService.Entry entry = onEdt(() -> editor.quickfixService.entries().getFirst());
            assertEquals(file.toString(), entry.getFilePath());
            assertEquals(2, entry.getLine());
            assertEquals(8, entry.getColumn());
            assertFalse(Files.exists(home.resolve(".shed/workspace-index")));
        } finally {
            disposeEditor(editor);
        }
    }

    private static void assumeSwingAvailable() {
        assumeFalse(GraphicsEnvironment.isHeadless(), "Swing display unavailable");
    }

    private static boolean awaitSearchCompletion(Texteditor editor, int jobId) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline) {
            AsyncJobService.JobSnapshot snapshot = onEdt(() -> editor.asyncJobService.get(jobId));
            if (snapshot != null && snapshot.getStatus() == AsyncJobService.Status.SUCCEEDED
                && onEdt(() -> editor.quickfixService.hasEntries())) {
                return true;
            }
            if (snapshot != null && snapshot.getStatus() != AsyncJobService.Status.RUNNING
                && snapshot.getStatus() != AsyncJobService.Status.SUCCEEDED) {
                return false;
            }
            Thread.sleep(20);
        }
        return false;
    }

    private static void initializeGit(Path root) throws Exception {
        Process process = new ProcessBuilder("git", "init", "--quiet", root.toString()).start();
        assertEquals(0, process.waitFor());
    }

    private static Texteditor createEditor(Path home, Path file) throws Exception {
        String previousHome = System.getProperty("user.home");
        return onEdt(() -> {
            System.setProperty("user.home", home.toString());
            try {
                return new Texteditor(new String[] {file.toString()});
            } finally {
                if (previousHome == null) {
                    System.clearProperty("user.home");
                } else {
                    System.setProperty("user.home", previousHome);
                }
            }
        });
    }

    private static void disposeEditor(Texteditor editor) throws Exception {
        if (editor == null) {
            return;
        }
        onEdt(() -> {
            stopTimer(editor.messageResetTimer);
            stopTimer(editor.externalChangeTimer);
            stopTimer(editor.recoverySnapshotTimer);
            stopTimer(editor.diagnosticRefreshTimer);
            if (editor.fileWatcherService != null) {
                editor.fileWatcherService.stop();
            }
            if (editor.ptyTerminalPanes != null) {
                for (PtyTerminalPane terminalPane : new ArrayList<>(editor.ptyTerminalPanes.values())) {
                    terminalPane.close();
                }
                editor.ptyTerminalPanes.clear();
            }
            if (editor.lspClients != null) {
                for (LspClient client : new ArrayList<>(editor.lspClients.values())) {
                    client.stop();
                }
                editor.lspClients.clear();
            }
            if (editor.asyncJobService != null) {
                editor.asyncJobService.shutdownNow();
            }
            editor.dispose();
            return null;
        });
    }

    private static void stopTimer(javax.swing.Timer timer) {
        if (timer != null) {
            timer.stop();
        }
    }

    private static <T> T onEdt(Callable<T> action) throws Exception {
        if (SwingUtilities.isEventDispatchThread()) {
            return action.call();
        }
        EdtResult<T> result = new EdtResult<>();
        SwingUtilities.invokeAndWait(() -> {
            try {
                result.value = action.call();
            } catch (Throwable t) {
                result.error = t;
            }
        });
        if (result.error != null) {
            if (result.error instanceof Exception) {
                throw (Exception) result.error;
            }
            if (result.error instanceof Error) {
                throw (Error) result.error;
            }
            throw new InvocationTargetException(result.error);
        }
        return result.value;
    }

    private static final class EdtResult<T> {
        private T value;
        private Throwable error;
    }
}
