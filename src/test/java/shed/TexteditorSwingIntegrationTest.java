package shed;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

import java.awt.Component;
import java.awt.GraphicsEnvironment;
import java.awt.event.FocusEvent;
import java.awt.event.FocusListener;
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

            assertEquals("Saving…", result);
            assertTrue(awaitBufferSaved(editor, file, "new\ncontent\n"));
            assertEquals("new\ncontent\n", Files.readString(file, StandardCharsets.UTF_8));
            assertFalse(onEdt(() -> editor.getCurrentBuffer().isModified()));
        } finally {
            disposeEditor(editor);
        }
    }

    @Test
    void vimLineMotionRetainsCaretAndCurrentLineHighlightAcrossLargeBuffer() throws Exception {
        assumeSwingAvailable();
        Path home = tempDir.resolve("home-motion");
        Path file = tempDir.resolve("motion.txt");
        Files.createDirectories(home);
        StringBuilder content = new StringBuilder();
        for (int line = 0; line < 300; line++) content.append("line ").append(line).append('\n');
        Files.writeString(file, content, StandardCharsets.UTF_8);

        Texteditor editor = createEditor(home, file);
        try {
            onEdt(() -> {
                for (int index = 0; index < 220; index++) editor.moveDown();
                return null;
            });
            assertEquals(221, onEdt(() -> editor.writingArea.getLineOfOffset(editor.writingArea.getCaretPosition()) + 1));
            assertNotNull(onEdt(() -> editor.currentLineHighlightTag));

            onEdt(() -> {
                for (int index = 0; index < 220; index++) editor.moveUp();
                return null;
            });
            assertEquals(1, onEdt(() -> editor.writingArea.getLineOfOffset(editor.writingArea.getCaretPosition()) + 1));
            assertNotNull(onEdt(() -> editor.currentLineHighlightTag));
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
    void markdownPreviewUsesNativeSplitAndRefreshesFromSourceBuffer() throws Exception {
        assumeSwingAvailable();
        Path home = tempDir.resolve("home-markdown-preview");
        Path file = tempDir.resolve("preview.md");
        Files.createDirectories(home);
        Files.writeString(file, "# Initial\n\n- [ ] Draft\n", StandardCharsets.UTF_8);

        Texteditor editor = createEditor(home, file);
        try {
            EditorPane source = onEdt(editor::getActivePane);
            FileBuffer buffer = onEdt(editor::getCurrentBuffer);
            assertEquals("Markdown preview opened", onEdt(() -> editor.commandHandler.execute("markdown preview")));
            assertEquals(2, onEdt(() -> editor.editorPanes.size()));
            assertSame(source, onEdt(editor::getActivePane));

            EditorPane preview = onEdt(() -> editor.editorPanes.stream().filter(EditorPane::isMarkdownPreview).findFirst().orElse(null));
            assertNotNull(preview);
            assertSame(buffer, onEdt(preview::getBuffer));
            MarkdownPreviewPane previewComponent = onEdt(() -> (MarkdownPreviewPane) preview.getComponent());
            assertTrue(onEdt(() -> previewComponent.getHtml().contains("Initial")));

            onEdt(() -> {
                editor.writingArea.append("\n## Updated\n");
                return null;
            });
            assertEquals("Markdown preview refreshed", onEdt(() -> editor.commandHandler.execute("markdown refresh")));
            assertTrue(onEdt(() -> previewComponent.getHtml().contains("id=\"updated\"")));
            assertEquals("Markdown preview already open", onEdt(() -> editor.commandHandler.execute("mdpreview")));
            assertEquals("Markdown preview closed", onEdt(() -> editor.commandHandler.execute("markdown close")));
            assertEquals(1, onEdt(() -> editor.editorPanes.size()));
            assertSame(source, onEdt(editor::getActivePane));
        } finally {
            disposeEditor(editor);
        }
    }

    @Test
    void markdownPreviewTracksSourceScrollAndCaretUnlessDisabled() throws Exception {
        assumeSwingAvailable();
        Path home = tempDir.resolve("home-markdown-preview-scroll-sync");
        Path file = tempDir.resolve("preview-scroll-sync.md");
        Files.createDirectories(home);
        StringBuilder markdown = new StringBuilder();
        for (int index = 1; index <= 180; index++) {
            markdown.append("## Section ").append(index).append("\n\nContent ").append(index).append("\n\n");
        }
        Files.writeString(file, markdown, StandardCharsets.UTF_8);

        Texteditor editor = createEditor(home, file);
        try {
            EditorPane source = onEdt(editor::getActivePane);
            assertEquals("Markdown preview opened", onEdt(() -> editor.commandHandler.execute("markdown preview")));
            MarkdownPreviewPane preview = onEdt(() -> (MarkdownPreviewPane) editor.editorPanes.stream()
                .filter(EditorPane::isMarkdownPreview).findFirst().orElseThrow().getComponent());

            onEdt(() -> {
                javax.swing.JScrollBar sourceBar = source.getScrollPane().getVerticalScrollBar();
                sourceBar.setValue(sourceBar.getMaximum() - sourceBar.getVisibleAmount());
                return null;
            });
            flushEdt();
            int sourceScrollPosition = onEdt(preview::getVerticalScrollPosition);
            assertTrue(sourceScrollPosition > 0);

            onEdt(() -> {
                source.getTextArea().setCaretPosition(source.getTextArea().getDocument().getLength());
                return null;
            });
            flushEdt();
            assertTrue(onEdt(preview::getVerticalScrollPosition) >= sourceScrollPosition);
            int caretScrollPosition = onEdt(preview::getVerticalScrollPosition);

            onEdt(() -> {
                editor.configManager.set("markdown.preview.scroll.sync", "false");
                source.getScrollPane().getVerticalScrollBar().setValue(0);
                return null;
            });
            flushEdt();
            assertEquals(caretScrollPosition, onEdt(preview::getVerticalScrollPosition));
        } finally {
            disposeEditor(editor);
        }
    }

    @Test
    void connectedDevContainerRoutesNormalTaskDryRunsWithoutStartingAnotherCliProcess() throws Exception {
        assumeSwingAvailable();
        Path home = tempDir.resolve("home-connected-dev-container-task");
        Path workspace = Files.createDirectories(tempDir.resolve("connected-dev-container-project"));
        Path source = workspace.resolve("Main.java");
        Files.createDirectories(workspace.resolve(".devcontainer"));
        Files.createDirectories(home);
        Files.writeString(source, "class Main {}\n", StandardCharsets.UTF_8);
        Files.writeString(workspace.resolve(".devcontainer/devcontainer.json"), "{}\n", StandardCharsets.UTF_8);
        Files.writeString(workspace.resolve(".shedtasks"), """
            schema_version = 1
            [task.check]
            command = "printf check"
            shell = "direct"
            """, StandardCharsets.UTF_8);

        Texteditor editor = createEditor(home, source);
        try {
            String result = onEdt(() -> {
                editor.devContainerSessions.connect(workspace, "/workspaces/project");
                return editor.handleTaskCommand("dry-run check");
            });

            assertEquals("Dev Container task dry run shown (not started)", result);
            String output = onEdt(() -> editor.getCurrentBuffer().getContent());
            assertTrue(output.contains("Connected Dev Container task dry run: check"));
            assertTrue(output.contains("container workspace: /workspaces/project"));
            assertTrue(output.contains("this dry run starts nothing"));
        } finally {
            disposeEditor(editor);
        }
    }

    @Test
    void localTaskDependenciesRunInDeclaredOrderAsOneJob() throws Exception {
        assumeSwingAvailable();
        Path home = tempDir.resolve("home-task-dependencies");
        Path workspace = tempDir.resolve("task-dependency-workspace");
        Path source = workspace.resolve("src/Main.java");
        Path marker = workspace.resolve("task-order.txt");
        Files.createDirectories(home);
        Files.createDirectories(source.getParent());
        Files.writeString(workspace.resolve("pom.xml"), "<project/>\n", StandardCharsets.UTF_8);
        Files.writeString(source, "class Main {}\n", StandardCharsets.UTF_8);
        Files.writeString(workspace.resolve(".shedtasks"), """
            schema_version = 1

            [task.prepare]
            command = "printf prepare > task-order.txt"

            [task.verify]
            command = "grep -qx prepare task-order.txt && printf -- -verify >> task-order.txt"
            depends_on = ["prepare"]
            """, StandardCharsets.UTF_8);

        Texteditor editor = createEditor(home, source);
        try {
            String requested = onEdt(() -> editor.handleTaskCommand("run verify"));
            int jobId = Integer.parseInt(requested.replaceAll("Task job ([0-9]+) started.*", "$1"));

            assertTrue(awaitJobCompletion(editor, jobId));
            assertEquals("prepare-verify", Files.readString(marker, StandardCharsets.UTF_8));
            assertTrue(onEdt(() -> editor.jobQuickfixController.taskOutputForPanel(jobId).contains("==> task prepare")));
            assertTrue(onEdt(() -> editor.jobQuickfixController.taskOutputForPanel(jobId).contains("==> task verify")));
        } finally {
            disposeEditor(editor);
        }
    }

    @Test
    void vscodeProcessTaskDryRunIsSessionOnlyAndUsesDirectArguments() throws Exception {
        assumeSwingAvailable();
        Path home = tempDir.resolve("home-vscode-task");
        Path workspace = Files.createDirectories(tempDir.resolve("vscode-task-project"));
        Path source = workspace.resolve("Main.java");
        Files.createDirectories(home);
        Files.writeString(source, "class Main {}\n", StandardCharsets.UTF_8);
        Files.createDirectories(workspace.resolve(".vscode"));
        Files.writeString(workspace.resolve(".vscode/tasks.json"), """
            {
              "version": "2.0.0",
              "tasks": [
                {"label":"Check source","type":"process","command":"printf","args":["%s", "${file}"],"problemMatcher":[]}
              ]
            }
            """, StandardCharsets.UTF_8);

        Texteditor editor = createEditor(home, source);
        try {
            String result = onEdt(() -> editor.handleTaskCommand("dry-run vscode-check-source"));

            assertEquals("Task dry run shown (not started)", result);
            String output = onEdt(() -> editor.getCurrentBuffer().getContent());
            assertTrue(output.contains("Task dry run: vscode-check-source"));
            assertTrue(output.contains("shell: direct"));
            assertTrue(output.contains(source.toString()));
            assertFalse(Files.exists(workspace.resolve(".shedtasks")));
        } finally {
            disposeEditor(editor);
        }
    }

    @Test
    void activeRemoteSessionRoutesNormalTaskDryRunsWithoutStartingAProcess() throws Exception {
        assumeSwingAvailable();
        Path home = tempDir.resolve("home-active-remote-task");
        Path workspace = Files.createDirectories(tempDir.resolve("active-remote-project"));
        Path source = workspace.resolve("Main.java");
        Files.createDirectories(home);
        Files.writeString(source, "class Main {}\n", StandardCharsets.UTF_8);
        Files.writeString(workspace.resolve(".shedtasks"), """
            schema_version = 1
            [task.check]
            command = "printf check"
            shell = "direct"
            """, StandardCharsets.UTF_8);

        Texteditor editor = createEditor(home, source);
        try {
            String result = onEdt(() -> {
                editor.remoteWorkspaceSessions.activate("ssh-project", new shed.api.RemoteWorkspace() {
                    @Override public String displayName() { return "test SSH workspace"; }
                    @Override public Path localRoot() { return workspace; }
                    @Override public String executionRoot() { return "/srv/project"; }
                    @Override public void synchronize() { }
                    @Override public void close() { }
                });
                return editor.handleTaskCommand("dry-run check");
            });

            assertEquals("Remote task dry run shown (not started)", result);
            String output = onEdt(() -> editor.getCurrentBuffer().getContent());
            assertTrue(output.contains("Remote task dry run: check"));
            assertTrue(output.contains("connection: ssh-project"));
            assertTrue(output.contains("This dry run starts nothing."));
        } finally {
            disposeEditor(editor);
        }
    }

    @Test
    void activeRemoteSessionTakesPrecedenceOverOverlappingDevContainerTaskRouting() throws Exception {
        assumeSwingAvailable();
        Path home = tempDir.resolve("home-remote-session-precedence");
        Path workspace = Files.createDirectories(tempDir.resolve("remote-session-precedence-project"));
        Path source = workspace.resolve("Main.java");
        Files.createDirectories(home);
        Files.createDirectories(workspace.resolve(".devcontainer"));
        Files.writeString(source, "class Main {}\n", StandardCharsets.UTF_8);
        Files.writeString(workspace.resolve(".devcontainer/devcontainer.json"), "{}\n", StandardCharsets.UTF_8);
        Files.writeString(workspace.resolve(".shedtasks"), """
            schema_version = 1
            [task.check]
            command = "printf check"
            shell = "direct"
            """, StandardCharsets.UTF_8);

        Texteditor editor = createEditor(home, source);
        try {
            String result = onEdt(() -> {
                editor.devContainerSessions.connect(workspace, "/workspaces/project");
                editor.remoteWorkspaceSessions.activate("ssh-project", new shed.api.RemoteWorkspace() {
                    @Override public String displayName() { return "test SSH workspace"; }
                    @Override public Path localRoot() { return workspace; }
                    @Override public String executionRoot() { return "/srv/project"; }
                    @Override public void synchronize() { }
                    @Override public void close() { }
                });
                return editor.handleTaskCommand("dry-run check");
            });

            assertEquals("Remote task dry run shown (not started)", result);
            String output = onEdt(() -> editor.getCurrentBuffer().getContent());
            assertTrue(output.contains("connection: ssh-project"));
            assertFalse(output.contains("Connected Dev Container task dry run"));
        } finally {
            disposeEditor(editor);
        }
    }

    @Test
    void terminalProfilesReportActiveRemoteExecutionSession() throws Exception {
        assumeSwingAvailable();
        Path home = tempDir.resolve("home-terminal-remote-session");
        Path workspace = Files.createDirectories(tempDir.resolve("terminal-remote-session-project"));
        Path source = workspace.resolve("Main.java");
        Files.createDirectories(home);
        Files.writeString(source, "class Main {}\n", StandardCharsets.UTF_8);

        Texteditor editor = createEditor(home, source);
        try {
            onEdt(() -> {
                editor.remoteWorkspaceSessions.activate("ssh-project", new shed.api.RemoteWorkspace() {
                    @Override public String displayName() { return "test SSH workspace"; }
                    @Override public Path localRoot() { return workspace; }
                    @Override public String executionRoot() { return "/srv/project"; }
                    @Override public void synchronize() { }
                    @Override public void close() { }
                });
                return null;
            });

            assertEquals("Showing terminal profiles", onEdt(() -> editor.commandHandler.execute("terminal list")));
            assertTrue(onEdt(() -> editor.getCurrentBuffer().getContent().contains("New terminals for this file use remote session: ssh-project.")));
        } finally {
            disposeEditor(editor);
        }
    }

    @Test
    void commandCompletionUsesTheRegisteredCommandSurface() throws Exception {
        assumeSwingAvailable();
        Path home = tempDir.resolve("home-command-completion");
        Path file = tempDir.resolve("command-completion.txt");
        Path directory = Files.createDirectories(tempDir.resolve("command-completion-path"));
        Path candidate = directory.resolve("candidate.txt");
        Files.createDirectories(home);
        Files.writeString(file, "local\n", StandardCharsets.UTF_8);
        Files.writeString(candidate, "candidate\n", StandardCharsets.UTF_8);

        Texteditor editor = createEditor(home, file);
        try {
            assertEquals(":remote", onEdt(() -> editor.completeCommand(":rem")));
            assertEquals(":container", onEdt(() -> editor.completeCommand(":cont")));
            assertEquals(":notebook", onEdt(() -> editor.completeCommand(":noteb")));
            assertEquals(":extension", onEdt(() -> editor.completeCommand(":extens")));
            assertEquals(":edit " + candidate, onEdt(() -> editor.completeCommand(":edit " + directory.resolve("cand"))));
            assertEquals(":write " + candidate, onEdt(() -> editor.completeCommand(":write " + directory.resolve("cand"))));
        } finally {
            disposeEditor(editor);
        }
    }

    @Test
    void remoteForwardListIsExplicitAndProcessFree() throws Exception {
        assumeSwingAvailable();
        Path home = tempDir.resolve("home-remote-forward-list");
        Path file = tempDir.resolve("remote-forward-list.txt");
        Files.createDirectories(home);
        Files.writeString(file, "local\n", StandardCharsets.UTF_8);

        Texteditor editor = createEditor(home, file);
        try {
            assertEquals("Showing remote workspaces", onEdt(() -> editor.commandHandler.execute("remote forward list")));
            String output = onEdt(() -> editor.getCurrentBuffer().getContent());
            assertTrue(output.contains("SSH loopback forwards"));
            assertTrue(output.contains("No SSH forwards active."));
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
    void terminalInputSurfaceTransfersFocusWithoutDuplicatingOwnership() throws Exception {
        assumeSwingAvailable();
        Path home = tempDir.resolve("home-terminal-focus");
        Path file = tempDir.resolve("terminal-focus.txt");
        Files.createDirectories(home);
        Files.writeString(file, "term\n", StandardCharsets.UTF_8);

        Texteditor editor = createEditor(home, file);
        try {
            EditorPane editorPane = onEdt(editor::getActivePane);
            assertEquals("Terminal opened", onEdt(() -> editor.commandHandler.execute("term")));
            EditorPane terminalEditorPane = onEdt(editor::getActivePane);
            PtyTerminalPane terminalPane = onEdt(terminalEditorPane::getTerminalPane);
            FileBuffer terminalBuffer = onEdt(editor::getCurrentBuffer);

            onEdt(() -> {
                editor.activateEditorPane(editorPane);
                fireFocusGained(terminalPane.getInputComponent());
                return null;
            });
            assertSame(terminalEditorPane, onEdt(editor::getActivePane));
            assertSame(terminalBuffer, onEdt(editor::getCurrentBuffer));

            onEdt(() -> {
                fireFocusGained(terminalPane.getInputComponent());
                fireFocusGained(editorPane.getTextArea());
                return null;
            });
            assertSame(editorPane, onEdt(editor::getActivePane));
            assertFalse(onEdt(() -> editor.getCurrentBuffer() == terminalBuffer));
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
    void debugStatusCommandShowsVisibleLifecycleWithoutStartingAnAdapter() throws Exception {
        assumeSwingAvailable();
        Path home = tempDir.resolve("home-debug-status");
        Path file = tempDir.resolve("debug-status.java");
        Files.createDirectories(home);
        Files.writeString(file, "class DebugStatus {}\n", StandardCharsets.UTF_8);

        Texteditor editor = createEditor(home, file);
        try {
            assertEquals("Showing debug status", onEdt(() -> editor.commandHandler.execute("debug status")));
            assertTrue(onEdt(() -> editor.getCurrentBuffer().getContent()).contains("Debug Lifecycle"));
            assertTrue(onEdt(() -> editor.getCurrentBuffer().getContent()).contains("State: IDLE"));
        } finally {
            disposeEditor(editor);
        }
    }

    @Test
    void debugBreakpointCommandsConfigureTheActiveSourceBreakpoint() throws Exception {
        assumeSwingAvailable();
        Path home = tempDir.resolve("home-debug-breakpoints");
        Path file = tempDir.resolve("debug-breakpoints.py");
        Files.createDirectories(home);
        Files.writeString(file, "value = 1\nprint(value)\n", StandardCharsets.UTF_8);

        Texteditor editor = createEditor(home, file);
        try {
            onEdt(() -> {
                editor.debugSessionController.toggleBreakpoint(editor.getCurrentBuffer(), 0);
                editor.debugSessionController = new DebugSessionController(editor);
                return null;
            });
            assertEquals("Source breakpoint updated.", onEdt(() -> editor.commandHandler.execute("debug breakpoint condition 1 value > 0")));
            assertEquals("Source breakpoint updated.", onEdt(() -> editor.commandHandler.execute("debug breakpoint hit 1 3")));
            assertEquals("Source breakpoint updated.", onEdt(() -> editor.commandHandler.execute("debug breakpoint log 1 value={value}")));
            assertEquals("Source breakpoint updated.", onEdt(() -> editor.commandHandler.execute("debug breakpoint disable 1")));

            BreakpointStore.Breakpoint breakpoint = onEdt(() -> editor.debugSessionController.breakpointsForPanel().getFirst());
            assertFalse(breakpoint.enabled());
            assertEquals("value > 0", breakpoint.condition());
            assertEquals("3", breakpoint.hitCondition());
            assertEquals("value={value}", breakpoint.logMessage());
        } finally {
            disposeEditor(editor);
        }
    }

    @Test
    void debugPreLaunchTaskRunsBeforeAnAdapterProcessIsOpened() throws Exception {
        assumeSwingAvailable();
        Path home = tempDir.resolve("home-debug-prelaunch");
        Path workspace = tempDir.resolve("debug-prelaunch-workspace");
        Path file = workspace.resolve("Main.java");
        Path marker = workspace.resolve("prelaunch.marker");
        Path adapter = tempDir.resolve("closed-debug-adapter.sh");
        Files.createDirectories(home.resolve(".shed"));
        Files.createDirectories(workspace);
        Files.writeString(file, "class Main {}\n", StandardCharsets.UTF_8);
        Files.writeString(adapter, "#!/bin/sh\nexit 0\n", StandardCharsets.UTF_8);
        assertTrue(adapter.toFile().setExecutable(true));
        Files.writeString(home.resolve(".shed/config.toml"), """
            schema_version = 1
            "debug.enabled" = true
            "debug.adapter.closed.command" = "%s"
            "debug.adapter.closed.capabilities" = "launch"
            "debug.configuration.main.adapter" = "closed"
            "debug.configuration.main.request" = "launch"
            "debug.configuration.main.scope" = "workspace"
            "debug.configuration.main.program" = "${file}"
            "debug.configuration.main.cwd" = "${workspaceFolder}"
            "debug.configuration.main.prelaunch_task" = "prepare"
            "process.timeout.ms" = 1000
            """.formatted(adapter));
        Files.writeString(workspace.resolve(".shedtasks"), """
            schema_version = 1

            [task.prepare]
            command = "touch prelaunch.marker"
            shell = "direct"
            problem_matcher = "none"
            presentation = "never"
            """, StandardCharsets.UTF_8);

        Texteditor editor = createEditor(home, file);
        try {
            String requested = onEdt(() -> editor.commandHandler.execute("debug start main"));
            int jobId = Integer.parseInt(requested.replaceAll(".*\\(job ([0-9]+)\\)\\.", "$1"));
            assertTrue(awaitJobCompletion(editor, jobId));
            assertTrue(Files.isRegularFile(marker));
            assertEquals(DebugSessionService.Lifecycle.FAILED, onEdt(() -> editor.debugSessionController.snapshotForPanel().lifecycle()));
        } finally {
            disposeEditor(editor);
        }
    }

    @Test
    void vscodeLaunchProfileCanRunOnlyItsAcceptedProcessTaskLabelBeforeOpeningAnAdapter() throws Exception {
        assumeSwingAvailable();
        Path home = tempDir.resolve("home-vscode-debug-prelaunch");
        Path workspace = tempDir.resolve("vscode-debug-prelaunch-workspace");
        Path file = workspace.resolve("Main.java");
        Path marker = workspace.resolve("prelaunch.marker");
        Path adapter = tempDir.resolve("closed-vscode-debug-adapter.sh");
        Files.createDirectories(home.resolve(".shed"));
        Files.createDirectories(workspace.resolve(".vscode"));
        Files.writeString(file, "class Main {}\n", StandardCharsets.UTF_8);
        Files.writeString(adapter, "#!/bin/sh\nexit 0\n", StandardCharsets.UTF_8);
        assertTrue(adapter.toFile().setExecutable(true));
        Files.writeString(home.resolve(".shed/config.toml"), """
            schema_version = 1
            "debug.enabled" = true
            "debug.adapter.closed.command" = "%s"
            "debug.adapter.closed.capabilities" = "launch"
            "process.timeout.ms" = 1000
            """.formatted(adapter));
        Files.writeString(workspace.resolve(".vscode/tasks.json"), """
            {"version":"2.0.0","tasks":[
              {"label":"Prepare source","type":"process","command":"touch","args":["prelaunch.marker"],"problemMatcher":[]}
            ]}
            """, StandardCharsets.UTF_8);
        Files.writeString(workspace.resolve(".vscode/launch.json"), """
            {"configurations":[
              {"name":"Run source","type":"closed","request":"launch","program":"${file}","preLaunchTask":"Prepare source"}
            ]}
            """, StandardCharsets.UTF_8);

        Texteditor editor = createEditor(home, file);
        try {
            String requested = onEdt(() -> editor.handleDebugCommand("start vscode:Run source"));
            int jobId = Integer.parseInt(requested.replaceAll(".*\\(job ([0-9]+)\\)\\.", "$1"));

            assertTrue(awaitJobCompletion(editor, jobId));
            assertTrue(Files.isRegularFile(marker));
            assertEquals(DebugSessionService.Lifecycle.FAILED, onEdt(() -> editor.debugSessionController.snapshotForPanel().lifecycle()));
            assertFalse(Files.exists(workspace.resolve(".shedtasks")));
        } finally {
            disposeEditor(editor);
        }
    }

    @Test
    void importedCodeWorkspaceCanRunItsAcceptedTaskBeforeItsAcceptedLaunchProfile() throws Exception {
        assumeSwingAvailable();
        Path home = tempDir.resolve("home-workspace-launch-prelaunch");
        Path workspace = tempDir.resolve("workspace-launch-project");
        Path file = workspace.resolve("Main.java");
        Path marker = workspace.resolve("workspace-prelaunch.marker");
        Path manifest = tempDir.resolve("team.code-workspace");
        Path adapter = tempDir.resolve("closed-workspace-debug-adapter.sh");
        Files.createDirectories(home.resolve(".shed"));
        Files.createDirectories(workspace);
        Files.writeString(file, "class Main {}\n", StandardCharsets.UTF_8);
        Files.writeString(adapter, "#!/bin/sh\nexit 0\n", StandardCharsets.UTF_8);
        assertTrue(adapter.toFile().setExecutable(true));
        Files.writeString(home.resolve(".shed/config.toml"), """
            schema_version = 1
            "debug.enabled" = true
            "debug.adapter.closed.command" = "%s"
            "debug.adapter.closed.capabilities" = "launch"
            "process.timeout.ms" = 1000
            """.formatted(adapter));
        Files.writeString(manifest, """
            {
              // The settings remain inert; only the bounded task and launch subsets are considered.
              "folders": [{"path": "workspace-launch-project"}],
              "settings": {"editor.tabSize": 2},
              "tasks": {"version": "2.0.0", "tasks": [
                {"label": "Prepare workspace", "type": "process", "command": "touch", "args": ["workspace-prelaunch.marker"], "problemMatcher": []}
              ]},
              "launch": {"configurations": [
                {"name": "Run workspace", "type": "closed", "request": "launch", "program": "${file}", "preLaunchTask": "Prepare workspace"}
              ]}
            }
            """, StandardCharsets.UTF_8);

        Texteditor editor = createEditor(home, file);
        try {
            assertEquals("Imported 1 workspace folder", onEdt(() -> editor.handleWorkspaceProfileCommand("import " + manifest)));
            assertEquals(2, onEdt(() -> editor.getTextArea().getTabSize()));
            String requested = onEdt(() -> editor.handleDebugCommand("start vscode:Run workspace"));
            int jobId = Integer.parseInt(requested.replaceAll(".*\\(job ([0-9]+)\\)\\.", "$1"));

            assertTrue(awaitJobCompletion(editor, jobId));
            assertTrue(Files.isRegularFile(marker));
            assertEquals(DebugSessionService.Lifecycle.FAILED, onEdt(() -> editor.debugSessionController.snapshotForPanel().lifecycle()));
            assertFalse(Files.exists(workspace.resolve(".shedtasks")));
            assertFalse(Files.exists(workspace.resolve(".vscode")));
        } finally {
            disposeEditor(editor);
        }
    }

    @Test
    void testDebugCanUseAnExplicitImportedVsCodeProfileWithTestPlaceholders() throws Exception {
        assumeSwingAvailable();
        Path home = tempDir.resolve("home-vscode-test-debug");
        Path workspace = tempDir.resolve("vscode-test-debug-workspace");
        Path source = workspace.resolve("src/test/java/demo/SampleTest.java");
        Path adapter = tempDir.resolve("closed-vscode-test-debug-adapter.sh");
        Files.createDirectories(home.resolve(".shed"));
        Files.createDirectories(source.getParent());
        Files.createDirectories(workspace.resolve(".vscode"));
        Files.writeString(workspace.resolve("pom.xml"), "<project/>\n", StandardCharsets.UTF_8);
        Files.writeString(source, """
            package demo;
            class SampleTest {
                @Test
                void works() {}
            }
            """, StandardCharsets.UTF_8);
        Files.writeString(adapter, "#!/bin/sh\nexit 0\n", StandardCharsets.UTF_8);
        assertTrue(adapter.toFile().setExecutable(true));
        Files.writeString(home.resolve(".shed/config.toml"), """
            schema_version = 1
            "debug.enabled" = true
            "debug.adapter.closed.command" = "%s"
            "debug.adapter.closed.capabilities" = "launch"
            "process.timeout.ms" = 1000
            """.formatted(adapter));
        Files.writeString(workspace.resolve(".shedtests"), """
            schema_version = 1

            [[adapter]]
            id = "maven"
            debug_configuration = "vscode:Debug selected test"
            """);
        Files.writeString(workspace.resolve(".vscode/launch.json"), """
            {"configurations":[
              {"name":"Debug selected test","type":"closed","request":"launch","program":"${testFile}","args":["${testId}"]}
            ]}
            """, StandardCharsets.UTF_8);

        Texteditor editor = createEditor(home, source);
        try {
            onEdt(() -> {
                editor.testController.selectRoot(workspace);
                return editor.handleTestCommand("refresh");
            });
            assertTrue(awaitTestDiscovery(editor, workspace, "demo.SampleTest#works"));

            String requested = onEdt(() -> editor.handleTestCommand("debug demo.SampleTest#works"));
            int jobId = Integer.parseInt(requested.replaceAll(".*\\(job ([0-9]+)\\)\\.", "$1"));
            assertTrue(awaitJobCompletion(editor, jobId));
            assertEquals(DebugSessionService.Lifecycle.FAILED, onEdt(() -> editor.debugSessionController.snapshotForPanel().lifecycle()));
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
            assertEquals("Showing managed LSP support", onEdt(() -> editor.handleLspCommand("manage status")));
            assertTrue(onEdt(() -> editor.getCurrentBuffer().getContent()).contains("Managed LSP Support"));
            assertTrue(onEdt(() -> editor.getCurrentBuffer().getContent()).contains(":lsp manage detect <ext>"));

            assertEquals("Opened language services for install", onEdt(() -> editor.handleLspCommand("manage install py")));

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

    private static void fireFocusGained(Component component) {
        FocusEvent event = new FocusEvent(component, FocusEvent.FOCUS_GAINED);
        for (FocusListener listener : component.getFocusListeners()) {
            listener.focusGained(event);
        }
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

    private static boolean awaitTestDiscovery(Texteditor editor, Path root, String testId) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline) {
            boolean found = onEdt(() -> editor.testController.snapshot(root).tests().stream().anyMatch(test -> testId.equals(test.id())));
            if (found) return true;
            Thread.sleep(20);
        }
        return false;
    }

    private static boolean awaitJobCompletion(Texteditor editor, int jobId) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline) {
            AsyncJobService.JobSnapshot snapshot = onEdt(() -> editor.asyncJobService.get(jobId));
            if (snapshot != null && snapshot.getStatus() != AsyncJobService.Status.RUNNING) return true;
            Thread.sleep(20);
        }
        return false;
    }

    private static boolean awaitBufferSaved(Texteditor editor, Path file, String expected) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline) {
            if (Files.exists(file) && expected.equals(Files.readString(file, StandardCharsets.UTF_8))
                && !onEdt(() -> editor.getCurrentBuffer().isModified())) return true;
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
                editor.shutdownSyntaxUi();
                editor.shutdownLspScheduling();
                editor.asyncJobService.shutdownNow();
            }
            if (editor.backupScheduler != null) {
                editor.backupScheduler.close();
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

    private static void flushEdt() throws Exception {
        onEdt(() -> null);
    }

    private static final class EdtResult<T> {
        private T value;
        private Throwable error;
    }
}
