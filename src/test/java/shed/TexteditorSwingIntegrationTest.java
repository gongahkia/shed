package shed;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeFalse;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.awt.Component;
import java.awt.Color;
import java.awt.GraphicsEnvironment;
import java.awt.Insets;
import java.awt.Rectangle;
import java.awt.Window;
import java.awt.event.ActionEvent;
import java.awt.event.FocusEvent;
import java.awt.event.FocusListener;
import java.awt.event.KeyEvent;
import java.io.File;
import java.lang.reflect.InvocationTargetException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.Callable;
import javax.swing.SwingUtilities;
import javax.swing.JMenuItem;
import javax.swing.JPopupMenu;
import javax.swing.JTextArea;
import javax.swing.UIManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

public class TexteditorSwingIntegrationTest {
    @TempDir
    Path tempDir;

    @Test
    void searchPanelRetainsSourceSharesOptionsAndOpensLocations() throws Exception {
        assumeSwingAvailable();
        Path home = Files.createDirectories(tempDir.resolve("home-shared-search"));
        Path root = Files.createDirectories(tempDir.resolve("search-root"));
        Path source = Files.writeString(root.resolve("source.txt"), "source");
        Path target = Files.writeString(root.resolve("target.txt"), "first\nCAT catapult cat\n");
        Texteditor editor = createEditor(home, source);
        try {
            assertTrue(awaitUiState(() -> !editor.stagedStartupPending));
            FileBuffer original = onEdt(editor::getCurrentBuffer);
            onEdt(() -> {
                editor.inlineFindBar.showFind(new SearchPattern("cat", false, false, true));
                descendants(editor.inlineFindBar, javax.swing.JButton.class).stream().filter(b -> b.getText().equals("In Files")).findFirst().orElseThrow().doClick();
                var panel = editor.toolWindowHost.searchPanel().component();
                assertFalse(descendants(panel, javax.swing.JCheckBox.class).stream().filter(b -> b.getText().equals("Match case")).findFirst().orElseThrow().isSelected());
                assertTrue(descendants(panel, javax.swing.JCheckBox.class).stream().filter(b -> b.getText().equals("Whole word")).findFirst().orElseThrow().isSelected());
                descendants(panel, javax.swing.JButton.class).stream().filter(b -> b.getText().equals("Search")).findFirst().orElseThrow().doClick();
                return null;
            });
            assertTrue(awaitUiState(() -> !editor.paletteController.workspaceSearch().snapshot().running()
                && editor.paletteController.workspaceSearch().snapshot().matches().size() == 2));
            assertSame(original, onEdt(editor::getCurrentBuffer));
            onEdt(() -> {
                var list = descendants(editor.toolWindowHost.searchPanel().component(), javax.swing.JList.class).getFirst();
                list.setSelectedIndex(1); list.getActionMap().get("open").actionPerformed(new ActionEvent(list, 0, "open"));
                return null;
            });
            assertTrue(awaitCurrentFile(editor, target));
            assertEquals(19, onEdt(() -> editor.writingArea.getCaretPosition()));
            assertEquals(2, onEdt(() -> editor.paletteController.workspaceSearch().snapshot().matches().size()));
            onEdt(() -> {
                var fields = descendants(editor.toolWindowHost.searchPanel().component(), javax.swing.JTextField.class);
                fields.getFirst().setText("");
                assertTrue(editor.paletteController.workspaceSearch().snapshot().matches().isEmpty());
                assertEquals("", fields.getFirst().getText());
                return null;
            });
        } finally { disposeEditor(editor); }
    }

    @Test
    void inlineRegexReplacementSupportsGroupsAndSingleUndo() throws Exception {
        assumeSwingAvailable();
        Path home = Files.createDirectories(tempDir.resolve("home-regex-replace"));
        Path file = Files.writeString(tempDir.resolve("groups.txt"), "a=1 bb=22");
        Texteditor editor = createEditor(home, file);
        try {
            onEdt(() -> {
                editor.configManager.set("keymap.profile", "plain");
                editor.sessionConfigController.applyRuntimeConfigFromSettings();
                editor.inlineFindBar.showFind(new SearchPattern("([a-z]+)=(\\d+)", true, true, false));
                editor.inlineFindBar.showReplace("([a-z]+)=(\\d+)");
                descendants(editor.inlineFindBar, javax.swing.JTextField.class).get(1).setText("$2:$1");
                return null;
            });
            assertTrue(awaitUiState(() -> descendants(editor.inlineFindBar, javax.swing.JButton.class).stream()
                .anyMatch(b -> b.getText().equals("Replace All (2)") && b.isEnabled())));
            onEdt(() -> { descendants(editor.inlineFindBar, javax.swing.JButton.class).stream().filter(b -> b.getText().startsWith("Replace All")).findFirst().orElseThrow().doClick(); return null; });
            assertTrue(awaitUiState(() -> editor.getCurrentBuffer().getContent().equals("1:a 22:bb")));
            onEdt(() -> { editor.undoManager.undo(); return null; });
            assertEquals("a=1 bb=22", onEdt(() -> editor.getCurrentBuffer().getContent()));
        } finally { disposeEditor(editor); }
    }

    private static boolean awaitUiState(Callable<Boolean> condition) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        while (System.nanoTime() < deadline) {
            if (onEdt(condition)) return true;
            Thread.sleep(30);
        }
        return false;
    }

    @Test
    void customActionsWorkInEachProfileWithoutInterceptingOtherFieldsOrComposition() throws Exception {
        assumeSwingAvailable();
        Path home = Files.createDirectories(tempDir.resolve("home-keymap-actions"));
        Path file = Files.writeString(tempDir.resolve("bindings.txt"), "original");
        Texteditor editor = createEditor(home, file);
        try {
            onEdt(() -> {
                for (String profile : List.of("plain", "emacs", "vim")) {
                    editor.configManager.set("keymap.profile", profile);
                    editor.configManager.set("keybind." + profile + ".<f2>", "action:select-all");
                    editor.writingArea.setCaretPosition(0);
                    KeyEvent event = new KeyEvent(editor.writingArea, KeyEvent.KEY_PRESSED, 0, 0, KeyEvent.VK_F2, KeyEvent.CHAR_UNDEFINED);
                    editor.keyPressed(event);
                    assertTrue(event.isConsumed());
                    assertEquals("original", editor.writingArea.getSelectedText());
                }
                editor.configManager.set("keymap.profile", "plain");
                editor.writingArea.setCaretPosition(0);
                javax.swing.JTextField other = new javax.swing.JTextField();
                KeyEvent otherEvent = new KeyEvent(other, KeyEvent.KEY_PRESSED, 0, 0, KeyEvent.VK_F2, KeyEvent.CHAR_UNDEFINED);
                editor.keyPressed(otherEvent);
                assertFalse(otherEvent.isConsumed());
                java.awt.event.InputMethodEvent composing = new java.awt.event.InputMethodEvent(editor.writingArea,
                    java.awt.event.InputMethodEvent.INPUT_METHOD_TEXT_CHANGED, new java.text.AttributedString("あ").getIterator(), 0, null, null);
                for (java.awt.event.InputMethodListener listener : editor.writingArea.getInputMethodListeners()) listener.inputMethodTextChanged(composing);
                KeyEvent ignored = new KeyEvent(editor.writingArea, KeyEvent.KEY_PRESSED, 0, 0, KeyEvent.VK_F2, KeyEvent.CHAR_UNDEFINED);
                editor.keyPressed(ignored);
                assertFalse(ignored.isConsumed());
                assertEquals(0, editor.writingArea.getSelectionEnd());
                return null;
            });
            assertTrue(onEdt(() -> editor.setKeybindingPersistent("plain", "<mod-s>", "action:find")).startsWith("Set"));
            String previousHome = System.getProperty("user.home");
            try {
                System.setProperty("user.home", home.toString());
                assertTrue(new ConfigManager().effectiveKeybindingsText("").contains("action:find"));
            } finally { System.setProperty("user.home", previousHome); }
        } finally { disposeEditor(editor); }
    }

    @Test
    void quickOpenNavigatesToLocationAndRecentPickerPreservesTheSourceOnCancel() throws Exception {
        assumeSwingAvailable();
        Path home = Files.createDirectories(tempDir.resolve("home-quick-open"));
        Path source = tempDir.resolve("picker-source.txt");
        Path target = tempDir.resolve("picker-target.txt");
        Files.writeString(source, "one\ntwo\nthree");
        Files.writeString(target, "alpha\nbeta\ngamma");
        Texteditor editor = createEditor(home, source);
        try {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
            while (onEdt(() -> editor.stagedStartupPending) && System.nanoTime() < deadline) Thread.sleep(40);
            assertFalse(onEdt(() -> editor.stagedStartupPending));
            onEdt(() -> {
                editor.writingArea.append(" unsaved");
                editor.writingArea.setCaretPosition(5);
                return drivePicker(editor, "Find File", dialog -> {
                    var field = descendants(dialog, javax.swing.JTextField.class).getFirst();
                    field.setText("picker-target.txt:2:999");
                    // Let the normal debounce finish, then accept from the results list.
                    javax.swing.Timer accept = new javax.swing.Timer(50, event -> {
                        if (!dialog.isDisplayable()) { ((javax.swing.Timer) event.getSource()).stop(); return; }
                        var list = descendants(dialog, javax.swing.JList.class).getFirst();
                        if (list.getSelectedValue() == null) return;
                        ((javax.swing.Timer) event.getSource()).stop();
                        list.requestFocusInWindow();
                        Object key = list.getInputMap().get(javax.swing.KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0));
                        list.getActionMap().get(key).actionPerformed(new ActionEvent(list, 0, "enter"));
                    });
                    accept.start();
                }, () -> editor.paletteController.showFilePicker("Find File", java.util.Map.of(
                    "picker-source.txt", source.toFile(), "picker-target.txt", target.toFile())));
            });
            assertTrue(awaitCurrentFile(editor, target));
            assertEquals(10, onEdt(() -> editor.writingArea.getCaretPosition()));
            onEdt(() -> {
                editor.commandHandler.execute("back");
                assertEquals(source.toFile(), editor.getCurrentBuffer().getFile());
                assertEquals(5, editor.writingArea.getCaretPosition());
                FileBuffer original = editor.getCurrentBuffer();
                int count = editor.buffers.size();
                assertEquals("Recent Files cancelled", drivePicker(editor, "Recent Files", Window::dispose, editor::showRecentFiles));
                assertSame(original, editor.getCurrentBuffer());
                assertEquals(count, editor.buffers.size());
                assertTrue(editor.writingArea.getText().endsWith(" unsaved"));
                Path missing = tempDir.resolve("deleted-recent.txt");
                editor.recentFiles.addFirst(missing.toString());
                String result = drivePicker(editor, "Recent Files", dialog -> {
                    var field = descendants(dialog, javax.swing.JTextField.class).getFirst();
                    field.setText("deleted-recent.txt");
                    field.postActionEvent();
                }, editor::showRecentFiles);
                assertTrue(result.startsWith("File is no longer available:"), result);
                assertSame(original, editor.getCurrentBuffer());
                assertEquals(count, editor.buffers.size());
                assertFalse(Files.exists(missing));
                return null;
            });
        } finally { disposeEditor(editor); }
    }

    private static String drivePicker(Texteditor editor, String title,
                                      java.util.function.Consumer<javax.swing.JDialog> action,
                                      Callable<String> open) throws Exception {
        boolean[] timedOut = {false};
        javax.swing.Timer driver = new javax.swing.Timer(100, event -> {
            for (Window window : editor.getOwnedWindows()) {
                if (window instanceof javax.swing.JDialog dialog && dialog.isShowing() && title.equals(dialog.getTitle())) {
                    ((javax.swing.Timer) event.getSource()).stop();
                    action.accept(dialog);
                }
            }
        });
        javax.swing.Timer timeout = new javax.swing.Timer(10000, event -> {
            timedOut[0] = true;
            for (Window window : editor.getOwnedWindows()) {
                if (window instanceof javax.swing.JDialog dialog && title.equals(dialog.getTitle())) dialog.dispose();
            }
        });
        timeout.setRepeats(false);
        driver.start(); timeout.start();
        try {
            String result = open.call();
            assertFalse(timedOut[0], "Picker interaction timed out: " + title);
            return result;
        } finally { driver.stop(); timeout.stop(); }
    }

    @Test
    void paletteAcceptsLatestQueryAndCanDismissFromResultsFocus() throws Exception {
        assumeSwingAvailable();
        Texteditor editor = createEmptyEditor(Files.createDirectories(tempDir.resolve("home-picker")));
        try {
            long startupDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
            while (onEdt(() -> editor.stagedStartupPending) && System.nanoTime() < startupDeadline) Thread.sleep(40);
            assertFalse(onEdt(() -> editor.stagedStartupPending));
            String selected = onEdt(() -> {
                javax.swing.Timer enter = new javax.swing.Timer(100, event -> {
                    for (Window window : editor.getOwnedWindows()) {
                        if (window instanceof javax.swing.JDialog dialog && dialog.isShowing()
                            && dialog.getTitle().equals("Picker regression")) {
                            ((javax.swing.Timer) event.getSource()).stop();
                            var field = descendants(dialog, javax.swing.JTextField.class).getFirst();
                            field.setText("beta");
                            field.postActionEvent();
                        }
                    }
                });
                javax.swing.Timer timeout = new javax.swing.Timer(10000, event -> {
                    for (Window window : editor.getOwnedWindows()) if (window.isShowing()) {
                        window.dispose();
                    }
                });
                timeout.setRepeats(false);
                enter.start(); timeout.start();
                try { return editor.showPaletteDialog("Picker regression", List.of("alpha", "beta")); }
                finally { enter.stop(); timeout.stop(); }
            });
            assertEquals("beta", selected);
            String dismissed = onEdt(() -> {
                javax.swing.Timer escape = new javax.swing.Timer(100, event -> {
                    for (Window window : editor.getOwnedWindows()) {
                        if (window instanceof javax.swing.JDialog dialog && dialog.isShowing()
                            && dialog.getTitle().equals("Picker escape")) {
                            ((javax.swing.Timer) event.getSource()).stop();
                            descendants(dialog, javax.swing.JList.class).getFirst().requestFocusInWindow();
                            var root = dialog.getRootPane();
                            Object key = root.getInputMap(javax.swing.JComponent.WHEN_IN_FOCUSED_WINDOW)
                                .get(javax.swing.KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0));
                            if (key == null) { dialog.dispose(); throw new AssertionError("No dialog-wide Escape binding"); }
                            root.getActionMap().get(key).actionPerformed(new ActionEvent(root, 0, "escape"));
                        }
                    }
                });
                escape.start();
                try { return editor.showPaletteDialog("Picker escape", List.of("alpha")); }
                finally { escape.stop(); }
            });
            assertEquals(null, dismissed);
        } finally { disposeEditor(editor); }
    }

    @Test
    void navigationHistoryReturnsAcrossDocumentsAndBranchesAfterNewJumps() throws Exception {
        assumeSwingAvailable();
        Path home = Files.createDirectories(tempDir.resolve("home-navigation"));
        Path first = tempDir.resolve("navigation-first.txt");
        Path second = tempDir.resolve("navigation-second.txt");
        Files.writeString(first, "one\ntwo\nthree");
        Files.writeString(second, "alpha\nbeta\ngamma");
        Texteditor editor = createEditor(home, first);
        try {
            onEdt(() -> {
                editor.writingArea.setCaretPosition(5);
                editor.openFileAtAsync(second.toFile(), 2, 1000, "Opened target");
                return null;
            });
            assertTrue(awaitCurrentFile(editor, second));
            assertEquals(10, onEdt(() -> editor.writingArea.getCaretPosition()));
            onEdt(() -> {
                assertTrue(editor.commandHandler.execute("back").startsWith("Back:"));
                assertEquals(first.toFile(), editor.getCurrentBuffer().getFile());
                assertEquals(5, editor.writingArea.getCaretPosition());
                assertTrue(editor.commandHandler.execute("forward").startsWith("Forward:"));
                assertEquals(second.toFile(), editor.getCurrentBuffer().getFile());
                assertEquals(10, editor.writingArea.getCaretPosition());
                editor.commandHandler.execute("back");
                editor.gotoLine(3);
                assertEquals("At newest jump", editor.commandHandler.execute("forward"));
                editor.commandHandler.execute("back");
                assertEquals(5, editor.writingArea.getCaretPosition());
                return null;
            });
        } finally { disposeEditor(editor); }
    }

    @Test
    void bufferSwitchRestoresSelectionAndViewportWithoutLosingEdits() throws Exception {
        assumeSwingAvailable();
        Path home = Files.createDirectories(tempDir.resolve("home-buffer-view"));
        Path first = tempDir.resolve("first.txt");
        Path second = tempDir.resolve("second.txt");
        Files.writeString(first, "first line\n".repeat(500));
        Files.writeString(second, "other file");
        Texteditor editor = createEditor(home, first);
        try {
            FileBuffer source = onEdt(editor::getCurrentBuffer);
            onEdt(() -> {
                editor.writingArea.append("unsaved");
                editor.writingArea.setCaretPosition(1200);
                editor.writingArea.moveCaretPosition(1150);
                return null;
            });
            java.awt.Point position = onEdt(() -> {
                var viewport = editor.getActivePane().getScrollPane().getViewport();
                viewport.setViewPosition(new java.awt.Point(0, 900));
                var saved = viewport.getViewPosition();
                editor.openFile(second.toFile());
                editor.switchToBuffer(editor.buffers.indexOf(source));
                assertEquals(1150, editor.writingArea.getCaret().getDot());
                assertEquals(1200, editor.writingArea.getCaret().getMark());
                return saved;
            });
            assertEquals(position, onEdt(() -> editor.getActivePane().getScrollPane().getViewport().getViewPosition()));
            assertTrue(onEdt(() -> source.isModified() && editor.writingArea.getText().endsWith("unsaved")));
        } finally { disposeEditor(editor); }
    }

    @Test
    void bufferViewsAreIndependentAcrossSplitsAndExplicitJumpsWin() throws Exception {
        assumeSwingAvailable();
        Path home = Files.createDirectories(tempDir.resolve("home-split-view"));
        Path first = tempDir.resolve("split-first.txt");
        Path second = tempDir.resolve("split-second.txt");
        Files.writeString(first, "one\ntwo\nthree\nfour\n");
        Files.writeString(second, "other");
        Texteditor editor = createEditor(home, first);
        try {
            onEdt(() -> {
                FileBuffer source = editor.getCurrentBuffer();
                EditorPane left = editor.getActivePane();
                editor.writingArea.setCaretPosition(5);
                editor.splitWindow(true);
                EditorPane right = editor.getActivePane();
                editor.writingArea.setCaretPosition(10);
                editor.openFile(second.toFile());
                editor.switchToBuffer(editor.buffers.indexOf(source));
                assertEquals(10, right.getTextArea().getCaretPosition());
                assertEquals(5, left.getTextArea().getCaretPosition());
                editor.gotoLine(4);
                assertEquals(14, right.getTextArea().getCaretPosition());
                return null;
            });
            assertEquals(14, onEdt(() -> editor.writingArea.getCaretPosition()));
        } finally { disposeEditor(editor); }
    }

    @Test
    void paletteInformationAndMarkdownOutlineOpenWindowsWithoutReplacingTheDocument() throws Exception {
        assumeSwingAvailable();
        Path home = tempDir.resolve("home-information-pages");
        Files.createDirectories(home);
        Path markdown = tempDir.resolve("outline.md");
        Files.writeString(markdown, "# First\nSome text\n## Second\n", StandardCharsets.UTF_8);
        Texteditor editor = createEditor(home, markdown);
        try {
            FileBuffer source = onEdt(editor::getCurrentBuffer);
            int bufferCount = onEdt(() -> editor.buffers.size());
            onEdt(() -> {
                editor.showPerfReport();
                editor.showConfigLoadStatus();
                editor.showTableOfContents();
                return null;
            });
            assertSame(source, onEdt(editor::getCurrentBuffer));
            assertEquals(bufferCount, onEdt(() -> editor.buffers.size()));
            assertTrue(onEdt(() -> Arrays.stream(editor.getOwnedWindows())
                .anyMatch(window -> window instanceof ShedInformationDialog dialog && dialog.isDisplayable())));
            assertTrue(onEdt(() -> Arrays.stream(editor.getOwnedWindows())
                .anyMatch(window -> window instanceof MarkdownOutlineDialog dialog && dialog.isDisplayable())));
        } finally {
            disposeEditor(editor);
        }
    }

    @Test
    void gitStatusAndBranchesOpenWindowsWithoutReplacingTheDocument() throws Exception {
        assumeSwingAvailable();
        Path home = tempDir.resolve("home-git-pages");
        Path repository = tempDir.resolve("git-pages");
        Files.createDirectories(home);
        Files.createDirectories(repository);
        initializeGit(repository);
        Path file = repository.resolve("notes.txt");
        Files.writeString(file, "notes\n", StandardCharsets.UTF_8);
        Texteditor editor = createEditor(home, file);
        try {
            FileBuffer source = onEdt(editor::getCurrentBuffer);
            int bufferCount = onEdt(() -> editor.buffers.size());
            onEdt(() -> {
                editor.handleGitCommand("status");
                editor.handleGitCommand("branches");
                return null;
            });
            assertSame(source, onEdt(editor::getCurrentBuffer));
            assertEquals(bufferCount, onEdt(() -> editor.buffers.size()));
            assertTrue(onEdt(() -> Arrays.stream(editor.getOwnedWindows())
                .anyMatch(window -> window instanceof ShedInformationDialog dialog && dialog.isDisplayable())));
            assertTrue(onEdt(() -> Arrays.stream(editor.getOwnedWindows())
                .anyMatch(window -> window instanceof GitBranchesDialog dialog && dialog.isDisplayable())));
        } finally {
            disposeEditor(editor);
        }
    }

    @Test
    void stagedStartupKeepsTypingWhenFullUiAppears() throws Exception {
        assumeSwingAvailable();
        assumeTrue(System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("mac"));
        assumeTrue(Files.isReadable(Path.of("/System/Library/Fonts/SFNSMono.ttf")));
        Path home = tempDir.resolve("home-staged-startup");
        Files.createDirectories(home);

        Texteditor editor = createEmptyEditor(home);
        try {
            assumeTrue(editor.stagedStartupFont != null);
            JTextArea area = onEdt(() -> editor.writingArea);
            FileBuffer buffer = onEdt(editor::getCurrentBuffer);
            assertTrue(onEdt(area::isEditable));
            boolean fontsStillLoading = onEdt(() -> editor.stagedStartupPending);
            if (fontsStillLoading) {
                assertTrue(onEdt(() -> editor.getJMenuBar() == null));
                assertTrue(onEdt(() -> editor.footerPanel.getParent() == null));
                assertTrue(onEdt(() -> editor.toolWindowHost == null));
            }
            long inputStartedAt = System.nanoTime();
            onEdt(() -> { area.replaceSelection("typed during startup"); return null; });
            if (fontsStillLoading) {
                assertTrue(System.nanoTime() - inputStartedAt < TimeUnit.SECONDS.toNanos(2),
                    "the editor event thread waited for system fonts");
            }
            onEdt(() -> { editor.showToolWindow(ToolWindowHost.Tab.PROBLEMS); return null; });

            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
            while (onEdt(() -> editor.stagedStartupPending) && System.nanoTime() < deadline) {
                Thread.sleep(40);
            }

            assertFalse(onEdt(() -> editor.stagedStartupPending), "full UI did not attach");
            assertSame(area, onEdt(() -> editor.writingArea));
            assertSame(buffer, onEdt(editor::getCurrentBuffer));
            assertEquals("typed during startup", onEdt(area::getText));
            assertEquals("Monospaced", onEdt(() -> area.getFont().getName()));
            assertNotNull(onEdt(editor::getJMenuBar));
            assertSame(editor.getContentPane(), onEdt(() -> editor.footerPanel.getParent()));
            assertNotNull(onEdt(() -> editor.toolWindowHost));
            assertTrue(onEdt(() -> editor.toolWindowHost.isSelected(ToolWindowHost.Tab.PROBLEMS)));
        } finally {
            disposeEditor(editor);
        }
    }

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
    void ordinarySavePreservesUndoHistory() throws Exception {
        assumeSwingAvailable();
        Path home = tempDir.resolve("home-save-undo");
        Path file = tempDir.resolve("save-undo.txt");
        Files.createDirectories(home);
        Files.writeString(file, "original");
        Texteditor editor = createEditor(home, file);
        try {
            assertTrue(onEdt(() -> {
                editor.writingArea.setCaretPosition(editor.writingArea.getDocument().getLength());
                editor.writingArea.replaceSelection(" edited");
                return editor.getCurrentBuffer().getUndoManager().canUndo();
            }));
            assertEquals("Saving…", onEdt(() -> editor.commandHandler.execute("w")));
            assertTrue(awaitBufferSaved(editor, file, "original edited"));
            assertTrue(onEdt(() -> editor.getCurrentBuffer().getUndoManager().canUndo()));
        } finally {
            disposeEditor(editor);
        }
    }

    @Test
    void asynchronousOpenInstallsTheLoadedBuffer() throws Exception {
        assumeSwingAvailable();
        Path home = tempDir.resolve("home-async-open");
        Path file = tempDir.resolve("async-open.txt");
        Files.createDirectories(home);
        Files.writeString(file, "loaded\n".repeat(20_000));
        Texteditor editor = createEmptyEditor(home);
        try {
            onEdt(() -> { editor.openFileAsync(file.toFile()); return null; });
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            boolean opened = false;
            while (System.nanoTime() < deadline) {
                opened = onEdt(() -> file.toAbsolutePath().toString().equals(editor.getCurrentBuffer().getFilePath()));
                if (opened) break;
                Thread.sleep(20);
            }
            assertTrue(opened);
            assertEquals("loaded\n".repeat(20_000), onEdt(() -> editor.writingArea.getText()));
        } finally {
            disposeEditor(editor);
        }
    }

    @Test
    void commandLineFileLoadsAfterTheWindowIsShown() throws Exception {
        assumeSwingAvailable();
        Path home = tempDir.resolve("home-startup-file");
        Path file = tempDir.resolve("startup-file.txt");
        Files.createDirectories(home);
        Files.writeString(file, "startup content\n".repeat(10_000), StandardCharsets.UTF_8);
        String previousHome = System.getProperty("user.home");
        record StartedEditor(Texteditor editor, boolean loadedInsideConstructor) { }
        StartedEditor started = onEdt(() -> {
            System.setProperty("user.home", home.toString());
            try {
                Texteditor editor = new Texteditor(new String[] { file.toString() });
                FileBuffer current = editor.getCurrentBuffer();
                return new StartedEditor(editor, current != null && file.toString().equals(current.getFilePath()));
            } finally {
                if (previousHome == null) System.clearProperty("user.home");
                else System.setProperty("user.home", previousHome);
            }
        });
        try {
            assertFalse(started.loadedInsideConstructor());
            assertTrue(awaitCurrentFile(started.editor(), file));
            String timings = onEdt(() -> started.editor().perfService.report());
            assertTrue(timings.contains("startup.files-installed"), timings);
            assertTrue(timings.contains("file.open.load"), timings);
            assertTrue(timings.contains("file.open.install"), timings);
        } finally {
            disposeEditor(started.editor());
        }
    }

    @Test
    void openingAFileDoesNotWaitForLanguageServerInitialization() throws Exception {
        assumeSwingAvailable();
        Path home = tempDir.resolve("home-async-lsp");
        Path file = tempDir.resolve("AsyncLsp.java");
        Path secondFile = tempDir.resolve("AnotherLsp.java");
        Files.createDirectories(home);
        Files.writeString(file, "class AsyncLsp {}\n", StandardCharsets.UTF_8);
        Files.writeString(secondFile, "class AnotherLsp {}\n", StandardCharsets.UTF_8);
        Path javaExecutablePath = Path.of(System.getProperty("java.home"), "bin",
            System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("win") ? "java.exe" : "java");
        assumeTrue(Files.isExecutable(javaExecutablePath));
        Texteditor editor = createEmptyEditor(home);
        try {
            long openMillis = onEdt(() -> {
                editor.configManager.set("lsp.java.command", javaExecutablePath.toString());
                String classPath = System.getProperty("java.class.path").replace("\"", "\\\"");
                editor.configManager.set("lsp.java.args", "-cp \"" + classPath + "\" "
                    + ReferenceDocumentHighlightLanguageServer.class.getName() + " --slow-init");
                long started = System.nanoTime();
                editor.openFile(file.toFile());
                editor.openFile(secondFile.toFile());
                return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
            });
            assertTrue(openMillis < 1200L, "file open waited for LSP initialization: " + openMillis + "ms");
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(6);
            boolean connected = false;
            while (System.nanoTime() < deadline) {
                connected = onEdt(() -> editor.existingLspClient(editor.getCurrentBuffer()) != null
                    && editor.lspDocumentVersions.containsKey(file.toUri().toString())
                    && editor.lspDocumentVersions.containsKey(secondFile.toUri().toString()));
                if (connected) break;
                Thread.sleep(20L);
            }
            assertTrue(connected, "language server did not finish its background startup");
            assertTrue(onEdt(() -> editor.perfService.report().contains("lsp.startup")));
        } finally {
            disposeEditor(editor);
        }
    }

    @Test
    void perfReportsEditorInputThroughSwingPaint() throws Exception {
        assumeSwingAvailable();
        Path home = tempDir.resolve("home-edit-paint-perf");
        Path file = tempDir.resolve("paint-perf.txt");
        Files.createDirectories(home);
        Files.writeString(file, "before\n", StandardCharsets.UTF_8);
        Texteditor editor = createEditor(home, file);
        try {
            String report = onEdt(() -> {
                editor.keyPressed(new KeyEvent(editor.writingArea, KeyEvent.KEY_PRESSED,
                    System.currentTimeMillis(), 0, KeyEvent.VK_X, 'x'));
                editor.writingArea.replaceSelection("x");
                editor.writingArea.paintImmediately(editor.writingArea.getVisibleRect());
                return editor.perfService.report();
            });
            assertTrue(report.contains("edit.input-to-document"), report);
            assertTrue(report.contains("edit.input-to-paint"), report);
            assertTrue(report.contains("edit.document-to-paint-start"), report);
            assertTrue(report.contains("edit.paint-duration"), report);
            assertTrue(report.contains("diff.gutter.incremental"), report);
            assertTrue(report.contains(" p95="), report);
        } finally {
            disposeEditor(editor);
        }
    }

    @Test
    void notebookPanelSaveWritesOnBackgroundJob() throws Exception {
        assumeSwingAvailable();
        Path home = tempDir.resolve("home-notebook-save");
        Path file = tempDir.resolve("notebook-save.ipynb");
        Files.createDirectories(home);
        NotebookDocument notebook = NotebookDocument.empty().withCells(List.of(
            new NotebookDocument.Cell("code", "before", java.util.Map.of())));
        Files.writeString(file, notebook.serialize());
        Texteditor editor = createEditor(home, file);
        try {
            onEdt(() -> {
                NotebookPanel panel = (NotebookPanel) editor.getActivePane().getAlternateComponent();
                descendants(panel, javax.swing.JTextArea.class).getFirst().setText("after");
                descendants(panel, javax.swing.JButton.class).stream()
                    .filter(button -> "Save".equals(button.getText())).findFirst().orElseThrow().doClick();
                return null;
            });
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            boolean saved = false;
            while (System.nanoTime() < deadline) {
                saved = "after".equals(NotebookDocument.parse(Files.readString(file)).cells().getFirst().source())
                    && !onEdt(() -> editor.getCurrentBuffer().isModified());
                if (saved) break;
                Thread.sleep(20);
            }
            assertTrue(saved);
        } finally {
            disposeEditor(editor);
        }
    }

    @Test
    void defaultStartupOpensEmptyBufferAndOpeningAFileReplacesIt() throws Exception {
        assumeSwingAvailable();
        Path home = tempDir.resolve("home-native-welcome");
        Path file = tempDir.resolve("welcome-target.txt");
        Files.createDirectories(home);
        Files.writeString(file, "opened\n", StandardCharsets.UTF_8);

        Texteditor editor = createEmptyEditor(home);
        try {
            assertSame(onEdt(() -> editor.getActivePane().getScrollPane()), onEdt(() -> editor.getActivePane().getComponent()));
            assertSame(onEdt(() -> editor.getActivePane().getComponent()), onEdt(() -> editor.renderedLayoutComponent));
            assertEquals("[No Name]", onEdt(() -> editor.getCurrentBuffer().getDisplayName()));
            assertEquals("", onEdt(() -> editor.getCurrentBuffer().getContent()));
            assertFalse(onEdt(() -> editor.getCurrentBuffer().isScratch()));
            assertFalse(Files.exists(home.resolve(".shed/landing.md")));

            onEdt(() -> {
                editor.openFile(file.toFile());
                return null;
            });

            assertEquals(file.toAbsolutePath().toString(), onEdt(() -> editor.getCurrentBuffer().getFilePath()));
            assertSame(onEdt(() -> editor.getActivePane().getScrollPane()), onEdt(() -> editor.renderedLayoutComponent));
            assertEquals(1, onEdt(() -> editor.buffers.size()));
        } finally {
            disposeEditor(editor);
        }
    }

    @Test
    void helpOpensSearchableWindowWithoutReplacingTheEditorBuffer() throws Exception {
        assumeSwingAvailable();
        Path home = tempDir.resolve("home-help-window");
        Files.createDirectories(home);
        Texteditor editor = createEmptyEditor(home);
        try {
            onEdt(() -> {
                editor.showHelp("lsp");
                return null;
            });
            ShedHelpDialog help = onEdt(() -> java.util.Arrays.stream(editor.getOwnedWindows())
                .filter(window -> window instanceof ShedHelpDialog)
                .map(window -> (ShedHelpDialog) window).findFirst().orElseThrow());
            assertTrue(onEdt(help::isVisible));
            assertTrue(onEdt(() -> descendants(help, javax.swing.JEditorPane.class).getFirst().getText()
                .contains("Language services")));
            assertEquals("[No Name]", onEdt(() -> editor.getCurrentBuffer().getDisplayName()));
            assertEquals(1, onEdt(() -> editor.buffers.size()));

            onEdt(() -> {
                descendants(help, javax.swing.JTextField.class).getFirst().setText("docker");
                return null;
            });
            assertTrue(onEdt(() -> descendants(help, javax.swing.JList.class).getFirst().getModel().getSize() > 0));
            assertTrue(onEdt(() -> descendants(help, javax.swing.JEditorPane.class).getFirst().getText()
                .contains("Docker")));
        } finally {
            disposeEditor(editor);
        }
    }

    @Test
    void startupRestoresThePreviousWindowSizeAndPosition() throws Exception {
        assumeSwingAvailable();
        Path home = tempDir.resolve("home-window-placement");
        Files.createDirectories(home);

        Texteditor editor = createEmptyEditor(home);
        Rectangle saved;
        try {
            assertEquals(javax.swing.JFrame.NORMAL,
                onEdt(() -> editor.getExtendedState() & javax.swing.JFrame.MAXIMIZED_BOTH));
            saved = onEdt(() -> {
                Rectangle current = editor.getBounds();
                Rectangle next = new Rectangle(current.x + 8, current.y + 8,
                    Math.max(400, current.width - 16), Math.max(300, current.height - 16));
                editor.setBounds(next);
                editor.windowPlacementStore.save(editor);
                return next;
            });
        } finally {
            disposeEditor(editor);
        }
        Texteditor restored = createEmptyEditor(home);
        try {
            assertEquals(saved, onEdt(restored::getBounds));
        } finally {
            disposeEditor(restored);
        }
    }

    @Test
    void repeatedCompletionUpdatesReuseOneOverlayInsideTheEditor() throws Exception {
        assumeSwingAvailable();
        Path home = tempDir.resolve("home-completion-overlay");
        Path file = tempDir.resolve("completion-overlay.txt");
        Files.createDirectories(home);
        Files.writeString(file, "sasdadd sas\nsasdadd sas", StandardCharsets.UTF_8);

        Texteditor editor = createEditor(home, file);
        try {
            onEdt(() -> {
                for (int i = 0; i < 5; i++) {
                    editor.writingArea.setCaretPosition(i % 2 == 0 ? 11 : editor.writingArea.getDocument().getLength());
                    editor.showInlineCompletion();
                    assertTrue(editor.isCompletionPopupVisible());
                    assertTrue(editor.completionPopup.getWidth() <= UiZoom.scale(360, editor.configManager.getUiZoom()));
                    assertTrue(editor.completionList.isFocusable());
                    assertEquals("Completion suggestions", editor.completionList.getAccessibleContext().getAccessibleName());
                    assertSame(editor.getLayeredPane(), editor.completionPopup.getParent());
                    assertTrue(editor.completionPopup.getX() >= 0);
                    assertTrue(editor.completionPopup.getY() >= 0);
                    assertTrue(editor.completionPopup.getX() + editor.completionPopup.getWidth()
                        <= editor.getLayeredPane().getWidth());
                    assertTrue(editor.completionPopup.getY() + editor.completionPopup.getHeight()
                        <= editor.getLayeredPane().getHeight());
                    editor.writingArea.replaceSelection("d");
                    editor.showInlineCompletion();
                    assertTrue(editor.isCompletionPopupVisible());
                    editor.writingArea.getDocument().remove(editor.writingArea.getCaretPosition() - 1, 1);
                    editor.showInlineCompletion();
                    assertTrue(editor.isCompletionPopupVisible());
                    editor.dismissCompletionPopup();
                }
                assertFalse(editor.isCompletionPopupVisible());
                assertEquals(1, Arrays.stream(editor.getLayeredPane().getComponents())
                    .filter(component -> component == editor.completionPopup).count());
                return null;
            });
        } finally {
            disposeEditor(editor);
        }
    }

    @Test
    void editorContextMenuOffersEditingActionsAndRespectsReadOnlyBuffers() throws Exception {
        assumeSwingAvailable();
        Path home = tempDir.resolve("home-editor-context-menu");
        Path file = tempDir.resolve("editor-context-menu.txt");
        Files.createDirectories(home);
        Files.writeString(file, "hello world", StandardCharsets.UTF_8);

        Texteditor editor = createEditor(home, file);
        try {
            onEdt(() -> {
                editor.writingArea.select(0, 5);
                JPopupMenu menu = editor.writingArea.getComponentPopupMenu();
                assertNotNull(menu);
                menu.show(editor.writingArea, 5, 5);
                JMenuItem cut = contextItem(menu, "Cut");
                JMenuItem copy = contextItem(menu, "Copy");
                JMenuItem paste = contextItem(menu, "Paste");
                assertTrue(cut.isEnabled());
                assertTrue(copy.isEnabled());
                copy.doClick();
                cut.doClick();
                assertEquals(" world", editor.writingArea.getText());

                menu.setVisible(false);
                editor.writingArea.setCaretPosition(0);
                menu.show(editor.writingArea, 5, 5);
                assertTrue(paste.isEnabled());
                paste.doClick();
                assertEquals("hello world", editor.writingArea.getText());

                menu.setVisible(false);
                editor.writingArea.setEditable(false);
                editor.writingArea.select(0, 5);
                menu.show(editor.writingArea, 5, 5);
                assertFalse(cut.isEnabled());
                assertTrue(copy.isEnabled());
                assertFalse(paste.isEnabled());
                assertFalse(contextItem(menu, "Undo").isEnabled());
                assertFalse(contextItem(menu, "Redo").isEnabled());
                menu.setVisible(false);
                return null;
            });
        } finally {
            disposeEditor(editor);
        }
    }

    private static JMenuItem contextItem(JPopupMenu menu, String label) {
        for (Component component : menu.getComponents()) {
            if (component instanceof JMenuItem item && label.equals(item.getText())) return item;
        }
        throw new AssertionError("Missing context menu item: " + label);
    }

    @Test
    void startupFolderCreatesAnActiveWorkspaceAndExplorer() throws Exception {
        assumeSwingAvailable();
        Path home = tempDir.resolve("home-startup-folder");
        Path workspace = tempDir.resolve("startup-workspace");
        Files.createDirectories(home);
        Files.createDirectories(workspace);
        Files.writeString(workspace.resolve("note.txt"), "opened from workspace\n", StandardCharsets.UTF_8);

        Texteditor editor = createEditor(home, workspace);
        try {
            assertEquals(workspace.toRealPath(), onEdt(() -> editor.workspaceController.activeRoot()));
            assertEquals(workspace.toRealPath().toFile(), onEdt(() -> editor.treeRoot));
            assertNotNull(onEdt(() -> editor.treePane));
            assertTrue(onEdt(() -> editor.treePane.getAlternateComponent() instanceof FileTreePanel));
        } finally {
            disposeEditor(editor);
        }
    }

    @Test
    void startupAcceptsAFolderAndMultipleFiles() throws Exception {
        assumeSwingAvailable();
        Path home = tempDir.resolve("home-startup-multiple-targets");
        Path workspace = tempDir.resolve("startup-multiple-workspace");
        Path first = workspace.resolve("first.txt");
        Path second = tempDir.resolve("second.txt");
        Files.createDirectories(home);
        Files.createDirectories(workspace);
        Files.writeString(first, "first\n", StandardCharsets.UTF_8);
        Files.writeString(second, "second\n", StandardCharsets.UTF_8);

        Texteditor editor = createEditor(home, workspace, first, second);
        try {
            assertEquals(workspace.toRealPath(), onEdt(() -> editor.workspaceController.activeRoot()));
            assertTrue(onEdt(() -> editor.treePane.getAlternateComponent() instanceof FileTreePanel));
            List<String> filePaths = onEdt(() -> editor.buffers.stream()
                .filter(FileBuffer::hasFilePath).map(FileBuffer::getFilePath).toList());
            assertTrue(filePaths.contains(first.toAbsolutePath().toString()));
            assertTrue(filePaths.contains(second.toAbsolutePath().toString()));
        } finally {
            disposeEditor(editor);
        }
    }

    @Test
    void globalUiZoomShortcutUpdatesTheVisibleInterfaceAndPersistsTheSetting() throws Exception {
        assumeSwingAvailable();
        Path home = tempDir.resolve("home-ui-zoom-shortcut");
        Files.createDirectories(home);

        Texteditor editor = createEmptyEditor(home);
        try {
            onEdt(() -> {
                Object binding = editor.getRootPane().getInputMap(javax.swing.JComponent.WHEN_IN_FOCUSED_WINDOW)
                    .get(javax.swing.KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_EQUALS, java.awt.event.InputEvent.CTRL_DOWN_MASK));
                assertEquals("shed.ui-zoom.in.ctrl.equals", binding);
                javax.swing.Action action = editor.getRootPane().getActionMap().get(binding);
                assertNotNull(action);
                action.actionPerformed(new ActionEvent(editor, ActionEvent.ACTION_PERFORMED, "zoom-in"));
                return null;
            });

            assertEquals(1.1, onEdt(() -> editor.configManager.getUiZoom()));
            assertTrue(Files.readString(home.resolve(".shed/config.toml")).contains("\"ui.zoom\" = 1.1"));

            assertEquals("UI zoom: 120%", onEdt(() -> editor.commandHandler.execute("zoom in default")));
            assertEquals(1.2, onEdt(() -> editor.configManager.getUiZoom()));
            assertEquals("UI zoom: 140%", onEdt(() -> editor.commandHandler.execute("zoom in 20")));
            assertEquals(1.4, onEdt(() -> editor.configManager.getUiZoom()));
            assertEquals("UI zoom: 120%", onEdt(() -> editor.commandHandler.execute("zoom out 20%")));
            assertEquals(1.2, onEdt(() -> editor.configManager.getUiZoom()));
            assertEquals("UI zoom: 110%", onEdt(() -> editor.commandHandler.execute("zoom out default")));
            assertEquals(1.1, onEdt(() -> editor.configManager.getUiZoom()));

            onEdt(() -> {
                Object binding = editor.getRootPane().getInputMap(javax.swing.JComponent.WHEN_IN_FOCUSED_WINDOW)
                    .get(javax.swing.KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_0, java.awt.event.InputEvent.CTRL_DOWN_MASK));
                assertEquals("shed.ui-zoom.reset.ctrl", binding);
                javax.swing.Action action = editor.getRootPane().getActionMap().get(binding);
                assertNotNull(action);
                action.actionPerformed(new ActionEvent(editor, ActionEvent.ACTION_PERFORMED, "zoom-reset"));
                return null;
            });

            assertEquals(UiZoom.DEFAULT, onEdt(() -> editor.configManager.getUiZoom()));
            assertTrue(Files.readString(home.resolve(".shed/config.toml")).contains("\"ui.zoom\" = 1.0"));
        } finally {
            disposeEditor(editor);
        }
    }

    @Test
    void legacyLandingFileDoesNotReplaceEmptyStartupBuffer() throws Exception {
        assumeSwingAvailable();
        Path home = tempDir.resolve("home-custom-landing");
        Path landing = home.resolve(".shed/landing.md");
        Files.createDirectories(landing.getParent());
        Files.writeString(landing, "# My start page\n", StandardCharsets.UTF_8);

        Texteditor editor = createEmptyEditor(home);
        try {
            assertEquals("[No Name]", onEdt(() -> editor.getCurrentBuffer().getDisplayName()));
            assertEquals("", onEdt(() -> editor.getCurrentBuffer().getContent()));
            assertEquals("# My start page\n", Files.readString(landing));
        } finally {
            disposeEditor(editor);
        }
    }

    @Test
    void settingsDialogUsesTheActiveShedPalette() throws Exception {
        assumeSwingAvailable();
        Path home = tempDir.resolve("home-themed-settings");
        Path file = tempDir.resolve("themed-settings.txt");
        Files.createDirectories(home);
        Files.writeString(file, "settings\n", StandardCharsets.UTF_8);

        Texteditor editor = createEditor(home, file);
        SettingsEditorDialog dialog = null;
        try {
            dialog = onEdt(() -> {
                SettingsEditorDialog.showFor(editor);
                return Arrays.stream(Window.getWindows()).filter(SettingsEditorDialog.class::isInstance)
                    .map(SettingsEditorDialog.class::cast).filter(Window::isDisplayable).findFirst().orElseThrow();
            });
            SettingsEditorDialog shown = dialog;

            assertEquals(editor.configManager.getNormalColor(), onEdt(() -> shown.getContentPane().getBackground()));
            List<javax.swing.JTextField> fields = onEdt(() -> descendants(shown, javax.swing.JTextField.class));
            assertFalse(fields.isEmpty());
            assertTrue(fields.stream().allMatch(field -> editor.configManager.getCommandBarBackground().equals(field.getBackground())));
            List<javax.swing.JButton> buttons = onEdt(() -> descendants(shown, javax.swing.JButton.class));
            assertFalse(buttons.isEmpty());
            assertTrue(buttons.stream().allMatch(button -> editor.configManager.getEditorForeground().equals(button.getForeground())));
        } finally {
            SettingsEditorDialog currentDialog = dialog;
            if (currentDialog != null) onEdt(() -> { currentDialog.dispose(); return null; });
            disposeEditor(editor);
        }
    }

    @Test
    void gitHistoryAndStandardSwingSurfacesFollowThemeChanges() throws Exception {
        assumeSwingAvailable();
        Path home = tempDir.resolve("home-themed-git-history");
        Path file = tempDir.resolve("themed-git-history.txt");
        Files.createDirectories(home);
        Files.writeString(file, "history\n", StandardCharsets.UTF_8);

        Texteditor editor = createEditor(home, file);
        GitHistoryRemoteDialog dialog = null;
        try {
            dialog = onEdt(() -> {
                GitHistoryRemoteDialog.showFor(editor, new GitHistoryRemoteDialog.Loader() {
                    @Override public GitHistoryModel.Snapshot load(AsyncJobService.JobToken token) {
                        return new GitHistoryModel.Snapshot(GitHistoryModel.State.READY, tempDir.toString(), List.of(), List.of("origin"), "Ready");
                    }

                    @Override public GitHistoryModel.RemoteResult run(GitHistoryModel.RemoteAction action, AsyncJobService.JobToken token) {
                        return new GitHistoryModel.RemoteResult(action, true, "Done");
                    }
                });
                return Arrays.stream(Window.getWindows()).filter(GitHistoryRemoteDialog.class::isInstance)
                    .map(GitHistoryRemoteDialog.class::cast).filter(Window::isDisplayable).findFirst().orElseThrow();
            });
            GitHistoryRemoteDialog shown = dialog;

            assertEquals(editor.configManager.getNormalColor(), onEdt(() -> shown.getContentPane().getBackground()));
            assertTrue(onEdt(() -> descendants(shown, javax.swing.JList.class).stream()
                .allMatch(list -> editor.configManager.getCommandBarBackground().equals(list.getBackground()))));
            assertTrue(onEdt(() -> descendants(shown, javax.swing.JTextArea.class).stream()
                .allMatch(area -> editor.configManager.getNormalColor().equals(area.getBackground()))));
            assertEquals(editor.configManager.getNormalColor(), onEdt(() -> UIManager.getColor("OptionPane.background")));
            assertEquals(editor.configManager.getNormalColor(), onEdt(() -> UIManager.getColor("FileChooser.background")));

            assertEquals("Theme set to dracula", onEdt(() -> editor.setThemeFromCommand("dracula")));
            assertEquals(editor.configManager.getNormalColor(), onEdt(() -> shown.getContentPane().getBackground()));
            assertTrue(onEdt(() -> descendants(shown, javax.swing.JButton.class).stream()
                .allMatch(button -> editor.configManager.getEditorForeground().equals(button.getForeground()))));
            assertFalse(onEdt(() -> Color.WHITE.equals(shown.getContentPane().getBackground())));
        } finally {
            GitHistoryRemoteDialog currentDialog = dialog;
            if (currentDialog != null) onEdt(() -> { currentDialog.dispose(); return null; });
            disposeEditor(editor);
        }
    }

    @Test
    void editorAndExplorerRefreshWhenTheThemeChanges() throws Exception {
        assumeSwingAvailable();
        Path home = tempDir.resolve("home-themed-start-surfaces");
        Path workspace = tempDir.resolve("themed-workspace");
        Files.createDirectories(home);
        Files.createDirectories(workspace);
        Files.writeString(workspace.resolve("note.txt"), "note\n", StandardCharsets.UTF_8);

        Texteditor editor = createEmptyEditor(home);
        try {
            javax.swing.JTextArea area = onEdt(() -> editor.getActivePane().getTextArea());
            assertEquals("Tree pane opened", onEdt(() -> editor.showFileTree(workspace.toString())));
            FileTreePanel explorer = onEdt(() -> (FileTreePanel) editor.treePane.getComponent());

            assertEquals("Theme set to dracula", onEdt(() -> editor.setThemeFromCommand("dracula")));
            assertEquals(onEdt(() -> editor.getModeBackground(editor.editorState.mode)), onEdt(area::getBackground));
            assertEquals(editor.configManager.getNormalColor(), onEdt(explorer::getBackground));
            assertTrue(onEdt(() -> descendants(explorer, javax.swing.JTree.class).stream()
                .allMatch(tree -> editor.configManager.getNormalColor().equals(tree.getBackground()))));
        } finally {
            disposeEditor(editor);
        }
    }

    @Test
    void themeGallerySavesAndRefreshesForLightThemes() throws Exception {
        assumeSwingAvailable();
        Path home = tempDir.resolve("home-themed-gallery");
        Path file = tempDir.resolve("gallery.txt");
        Files.createDirectories(home);
        Files.writeString(file, "gallery\n", StandardCharsets.UTF_8);

        Texteditor editor = createEditor(home, file);
        ThemeGalleryDialog dialog = null;
        try {
            dialog = onEdt(() -> {
                ThemeGalleryDialog.showFor(editor);
                return Arrays.stream(Window.getWindows()).filter(ThemeGalleryDialog.class::isInstance)
                    .map(ThemeGalleryDialog.class::cast).filter(Window::isDisplayable).findFirst().orElseThrow();
            });
            ThemeGalleryDialog shown = dialog;

            List<javax.swing.JButton> buttons = onEdt(() -> descendants(shown, javax.swing.JButton.class));
            javax.swing.JButton save = buttons.stream().filter(button -> "Apply and Save".equals(button.getText())).findFirst().orElseThrow();
            assertTrue(onEdt(save::isEnabled));
            assertFalse(buttons.stream().anyMatch(button -> "Apply for This Session".equals(button.getText())));

            assertEquals("Theme set to catppuccin-latte", onEdt(() -> editor.setThemeFromCommand("catppuccin-latte")));
            assertEquals(editor.configManager.getNormalColor(), onEdt(() -> shown.getContentPane().getBackground()));
            assertTrue(onEdt(() -> descendants(shown, javax.swing.JList.class).stream()
                .allMatch(list -> editor.configManager.getCommandBarBackground().equals(list.getBackground()))));
            assertTrue(onEdt(() -> descendants(shown, javax.swing.JButton.class).stream()
                .allMatch(button -> editor.configManager.getEditorForeground().equals(button.getForeground()))));
            assertTrue(onEdt(() -> descendants(shown, javax.swing.JScrollPane.class).stream()
                .allMatch(scroll -> editor.configManager.getNormalColor().equals(scroll.getViewport().getBackground()))));
        } finally {
            ThemeGalleryDialog currentDialog = dialog;
            if (currentDialog != null) onEdt(() -> { currentDialog.dispose(); return null; });
            disposeEditor(editor);
        }
    }

    @Test
    void uiZoomScalesTheFooterAndSettingsDialog() throws Exception {
        assumeSwingAvailable();
        Path home = tempDir.resolve("home-ui-zoom-settings");
        Path file = tempDir.resolve("ui-zoom-settings.txt");
        Path config = home.resolve(".shed/config.toml");
        Files.createDirectories(config.getParent());
        Files.writeString(config, "schema_version = 1\n\"ui.zoom\" = 1.5\n", StandardCharsets.UTF_8);
        Files.writeString(file, "settings\n", StandardCharsets.UTF_8);

        Texteditor editor = createEditor(home, file);
        SettingsEditorDialog dialog = null;
        try {
            assertEquals(1.5, editor.configManager.getUiZoom());
            assertEquals(onEdt(() -> editor.resolveUiFont().getSize()), onEdt(() -> editor.statusBar.getFont().getSize()));
            assertEquals(onEdt(() -> editor.resolveUiFont().getSize()), onEdt(() -> editor.commandBar.getFont().getSize()));

            dialog = onEdt(() -> {
                SettingsEditorDialog.showFor(editor);
                return Arrays.stream(Window.getWindows()).filter(SettingsEditorDialog.class::isInstance)
                    .map(SettingsEditorDialog.class::cast).filter(Window::isDisplayable).findFirst().orElseThrow();
            });
            SettingsEditorDialog shown = dialog;
            Rectangle usableScreen = onEdt(() -> shown.getGraphicsConfiguration().getBounds());
            Insets screenInsets = onEdt(() -> java.awt.Toolkit.getDefaultToolkit().getScreenInsets(shown.getGraphicsConfiguration()));
            Insets dialogInsets = onEdt(shown::getInsets);
            int availableWidth = usableScreen.width - screenInsets.left - screenInsets.right - 48
                - dialogInsets.left - dialogInsets.right;
            assertEquals(Math.min(UiZoom.scale(1040, 1.5), availableWidth),
                onEdt(() -> shown.getContentPane().getPreferredSize().width));
            List<javax.swing.JTextField> fields = onEdt(() -> descendants(shown, javax.swing.JTextField.class));
            assertFalse(fields.isEmpty());
            assertTrue(fields.stream().allMatch(field -> field.getFont().getSize() >= UiZoom.scale(12, 1.5)));

            onEdt(() -> {
                editor.configManager.set("ui.zoom", "1.6");
                editor.sessionConfigController.applyRuntimeConfigFromSettings();
                return null;
            });
            assertEquals(Math.min(UiZoom.scale(1040, 1.6), availableWidth),
                onEdt(() -> shown.getContentPane().getPreferredSize().width));
            assertEquals(onEdt(() -> editor.resolveUiFont().getSize()), onEdt(() -> editor.statusBar.getFont().getSize()));

            onEdt(() -> {
                editor.configManager.set("ui.zoom", "4.0");
                editor.sessionConfigController.applyRuntimeConfigFromSettings();
                return null;
            });
            Rectangle screen = onEdt(() -> shown.getGraphicsConfiguration().getBounds());
            Insets insets = onEdt(() -> java.awt.Toolkit.getDefaultToolkit().getScreenInsets(shown.getGraphicsConfiguration()));
            assertTrue(onEdt(() -> shown.getWidth() <= screen.width - insets.left - insets.right));
            assertTrue(onEdt(() -> shown.getHeight() <= screen.height - insets.top - insets.bottom));
        } finally {
            SettingsEditorDialog currentDialog = dialog;
            if (currentDialog != null) onEdt(() -> { currentDialog.dispose(); return null; });
            disposeEditor(editor);
        }
    }

    @Test
    void configuredLandingSourceDoesNotCreateAStartupFile() throws Exception {
        assumeSwingAvailable();
        Path home = tempDir.resolve("home-configured-landing");
        Path config = home.resolve(".shed/config.toml");
        Files.createDirectories(config.getParent());
        Files.writeString(config, """
            schema_version = 1
            "landing.source" = "pages/start.md"
            """, StandardCharsets.UTF_8);

        Texteditor editor = createEmptyEditor(home);
        try {
            Path landing = home.resolve("pages/start.md").toAbsolutePath();
            assertEquals("[No Name]", onEdt(() -> editor.getCurrentBuffer().getDisplayName()));
            assertEquals("", onEdt(() -> editor.getCurrentBuffer().getContent()));
            assertFalse(Files.exists(landing));
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
    void vimCountPrefixMovesSixHundredLines() throws Exception {
        assumeSwingAvailable();
        Path home = tempDir.resolve("home-counted-motion");
        Path file = tempDir.resolve("counted-motion.txt");
        Files.createDirectories(home);
        StringBuilder content = new StringBuilder();
        for (int line = 0; line < 1000; line++) content.append("line ").append(line).append('\n');
        Files.writeString(file, content, StandardCharsets.UTF_8);

        Texteditor editor = createEditor(home, file);
        try {
            onEdt(() -> {
                enableVim(editor);
                press(editor, '6');
                press(editor, '0');
                press(editor, '0');
                press(editor, 'j');
                return null;
            });

            assertEquals(601, onEdt(() -> editor.writingArea.getLineOfOffset(editor.writingArea.getCaretPosition()) + 1));
        } finally {
            disposeEditor(editor);
        }
    }

    @Test
    void vimMacrosRecordInsertKeysAndReplayCounted() throws Exception {
        assumeSwingAvailable();
        Path home = tempDir.resolve("home-macros");
        Path file = tempDir.resolve("macros.txt");
        Files.createDirectories(home);
        Files.writeString(file, "", StandardCharsets.UTF_8);

        Texteditor editor = createEditor(home, file);
        try {
            onEdt(() -> {
                enableVim(editor);
                press(editor, 'q');
                press(editor, 'a');
                press(editor, 'i');
                press(editor, 'X');
                pressEscape(editor);
                press(editor, 'q');

                RegisterContent macro = editor.registerManager.get('a');
                assertNotNull(macro);
                assertTrue(macro.isMacro());
                assertEquals(3, macro.getMacroKeys().size());

                press(editor, '2');
                press(editor, '@');
                press(editor, 'a');
                assertEquals("Executed macro @a 2 times", editor.lastMessage);
                assertEquals("XX", editor.writingArea.getText());
                assertEquals(EditorMode.NORMAL, editor.editorState.mode);
                assertEquals(Character.valueOf('a'), editor.lastMacroRegister);
                assertEquals(3, macro.getMacroKeys().size());

                press(editor, '@');
                press(editor, '@');
                assertEquals("XXX", editor.writingArea.getText());
                return null;
            });
        } finally {
            disposeEditor(editor);
        }
    }

    @Test
    void vimMacroUppercaseRegisterAppendsToItsLowercaseMacro() throws Exception {
        assumeSwingAvailable();
        Path home = tempDir.resolve("home-macro-append");
        Path file = tempDir.resolve("macro-append.txt");
        Files.createDirectories(home);
        Files.writeString(file, "one\ntwo\n", StandardCharsets.UTF_8);

        Texteditor editor = createEditor(home, file);
        try {
            onEdt(() -> {
                enableVim(editor);
                press(editor, 'q');
                press(editor, 'a');
                press(editor, 'j');
                press(editor, 'q');

                press(editor, 'q');
                press(editor, 'A');
                press(editor, 'k');
                press(editor, 'q');

                RegisterContent macro = editor.registerManager.get('a');
                assertNotNull(macro);
                assertTrue(macro.isMacro());
                assertEquals(2, macro.getMacroKeys().size());
                assertEquals('j', macro.getMacroKeys().get(0).toKeyEvent(editor.writingArea).getKeyChar());
                assertEquals('k', macro.getMacroKeys().get(1).toKeyEvent(editor.writingArea).getKeyChar());
                return null;
            });
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
            EditorPane originalPane = onEdt(editor::getActivePane);
            String split = onEdt(() -> editor.commandHandler.execute("vs"));
            assertEquals("Vertical split created", split);
            assertEquals(2, onEdt(() -> editor.editorPanes.size()));
            assertSame(initial, onEdt(editor::getCurrentBuffer));
            EditorPane second = onEdt(() -> editor.editorPanes.stream().filter(pane -> pane != originalPane).findFirst().orElseThrow());
            onEdt(() -> {
                editor.activateEditorPane(second);
                assertEquals("Window focus changed", editor.commandHandler.execute("window next"));
                assertEquals("Window focus changed", editor.commandHandler.execute("window previous"));
                assertSame(second, editor.getActivePane());
                return null;
            });
            assertEquals("Window resized", onEdt(() -> {
                editor.activateEditorPane(second);
                return editor.commandHandler.execute("window grow 20");
            }));
            assertEquals(0.3, onEdt(() -> editor.windowLayoutRoot.getRatio()));
            assertEquals("Window resized", onEdt(() -> {
                editor.activateEditorPane(second);
                return editor.commandHandler.execute("window shrink 20%");
            }));
            assertEquals(0.5, onEdt(() -> editor.windowLayoutRoot.getRatio()));
            assertEquals("Windows equalized", onEdt(() -> editor.commandHandler.execute("window equalize")));
            assertEquals("Command not recognised: close!", onEdt(() -> editor.commandHandler.execute("close!")));
            assertEquals(2, onEdt(() -> editor.editorPanes.size()));

            String close = onEdt(() -> {
                editor.activateEditorPane(second);
                return editor.commandHandler.execute("close");
            });
            assertEquals("Window closed", close);
            assertEquals(1, onEdt(() -> editor.editorPanes.size()));
            assertSame(initial, onEdt(editor::getCurrentBuffer));
            assertEquals("Horizontal split created", onEdt(() -> editor.commandHandler.execute("s")));
            assertEquals(2, onEdt(() -> editor.editorPanes.size()));
        } finally {
            disposeEditor(editor);
        }
    }

    @Test
    void discardCurrentBufferClosesTheActiveBufferWithoutSaving() throws Exception {
        assumeSwingAvailable();
        Path home = tempDir.resolve("home-discard-buffer");
        Path first = tempDir.resolve("discard-first.txt");
        Path second = tempDir.resolve("discard-second.txt");
        Files.createDirectories(home);
        Files.writeString(first, "first\n", StandardCharsets.UTF_8);
        Files.writeString(second, "second\n", StandardCharsets.UTF_8);

        Texteditor editor = createEditor(home, first);
        try {
            onEdt(() -> {
                editor.openFile(second.toFile());
                editor.writingArea.setText("unsaved\n");
                return null;
            });

            assertEquals("Buffer deleted", onEdt(() -> editor.commandHandler.execute("discard current buffer")));
            assertEquals(1, onEdt(() -> editor.buffers.size()));
            assertEquals(first.getFileName().toString(), onEdt(() -> editor.getCurrentBuffer().getDisplayName()));
            assertEquals("second\n", Files.readString(second, StandardCharsets.UTF_8));
        } finally {
            disposeEditor(editor);
        }
    }

    @Test
    void quitCommandsCloseOnlyTheActiveEditorWindow() throws Exception {
        assumeSwingAvailable();
        Path home = tempDir.resolve("home-quit-window");
        Path file = tempDir.resolve("quit-window.txt");
        Files.createDirectories(home);
        Files.writeString(file, "before\n", StandardCharsets.UTF_8);

        Texteditor editor = createEditor(home, file);
        try {
            assertEquals("Vertical split created", onEdt(() -> editor.commandHandler.execute("vsplit")));
            assertEquals("Window closed", onEdt(() -> editor.commandHandler.execute("q")));
            assertEquals(1, onEdt(() -> editor.editorPanes.size()));
            assertTrue(onEdt(editor::isDisplayable));

            String writeQuit = onEdt(() -> {
                editor.writingArea.setText("after\n");
                return editor.commandHandler.execute("wq");
            });
            assertEquals("Saving…", writeQuit);
            assertTrue(awaitBufferSaved(editor, file, "after\n"));
            assertEquals("after\n", Files.readString(file, StandardCharsets.UTF_8));
            assertEquals(1, onEdt(() -> editor.editorPanes.size()));
            assertEquals("[No Name]", onEdt(() -> editor.getCurrentBuffer().getDisplayName()));
            assertTrue(onEdt(editor::isDisplayable));
        } finally {
            disposeEditor(editor);
        }
    }

    @Test
    void editorScrollbarsFollowTheRuntimeVisibilitySetting() throws Exception {
        assumeSwingAvailable();
        Path home = tempDir.resolve("home-editor-scrollbars");
        Path file = tempDir.resolve("editor-scrollbars.txt");
        Files.createDirectories(home);
        Files.writeString(file, "scrolling\n", StandardCharsets.UTF_8);

        Texteditor editor = createEditor(home, file);
        try {
            assertEquals(javax.swing.JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED,
                onEdt(() -> editor.getActivePane().getScrollPane().getVerticalScrollBarPolicy()));
            assertEquals(javax.swing.JScrollPane.HORIZONTAL_SCROLLBAR_AS_NEEDED,
                onEdt(() -> editor.getActivePane().getScrollPane().getHorizontalScrollBarPolicy()));

            onEdt(() -> {
                editor.configManager.set("editor.scrollbars.visible", "false");
                editor.sessionConfigController.applyRuntimeConfigFromSettings();
                return null;
            });
            assertEquals(javax.swing.JScrollPane.VERTICAL_SCROLLBAR_NEVER,
                onEdt(() -> editor.getActivePane().getScrollPane().getVerticalScrollBarPolicy()));
            assertEquals(javax.swing.JScrollPane.HORIZONTAL_SCROLLBAR_NEVER,
                onEdt(() -> editor.getActivePane().getScrollPane().getHorizontalScrollBarPolicy()));
        } finally {
            disposeEditor(editor);
        }
    }

    @Test
    void inlineReplaceShowsLiveCountAndCanUndoAllMatches() throws Exception {
        assumeSwingAvailable();
        Path home = tempDir.resolve("home-inline-replace");
        Path file = tempDir.resolve("inline-replace.txt");
        Files.createDirectories(home);
        Files.writeString(file, "alpha beta alpha\n", StandardCharsets.UTF_8);
        Texteditor editor = createEditor(home, file);
        try {
            onEdt(() -> {
                editor.inlineFindBar.showReplace("alpha");
                List<javax.swing.JTextField> fields = descendants(editor.inlineFindBar, javax.swing.JTextField.class);
                fields.get(1).setText("gamma");
                return null;
            });
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            boolean counted = false;
            while (System.nanoTime() < deadline) {
                counted = onEdt(() -> descendants(editor.inlineFindBar, javax.swing.JLabel.class).stream()
                    .anyMatch(label -> label.getText().contains("2 matches")
                        && label.getText().contains("alpha → gamma")));
                if (counted) break;
                Thread.sleep(20);
            }
            assertTrue(counted, "inline replacement preview did not arrive");
            onEdt(() -> {
                descendants(editor.inlineFindBar, javax.swing.JButton.class).stream()
                    .filter(button -> button.getText().startsWith("Replace All"))
                    .findFirst().orElseThrow().doClick();
                return null;
            });
            deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            boolean replaced = false;
            while (System.nanoTime() < deadline) {
                replaced = onEdt(() -> "gamma beta gamma\n".equals(editor.getCurrentBuffer().getContent()));
                if (replaced) break;
                Thread.sleep(20);
            }
            assertTrue(replaced, "replace all did not update the active buffer");
            onEdt(() -> { editor.getCurrentBuffer().getUndoManager().undo(); return null; });
            assertEquals("alpha beta alpha\n", onEdt(() -> editor.getCurrentBuffer().getContent()));
        } finally {
            disposeEditor(editor);
        }
    }

    @Test
    void desktopMenusAndPositionCueAreVisible() throws Exception {
        assumeSwingAvailable();
        Path home = tempDir.resolve("home-menus");
        Path file = tempDir.resolve("menus.txt");
        Files.createDirectories(home);
        Files.writeString(file, "line\n".repeat(250), StandardCharsets.UTF_8);
        Texteditor editor = createEditor(home, file);
        try {
            assertEquals(List.of("File", "Edit", "View", "Navigate", "Help"), onEdt(() -> {
                List<String> names = new ArrayList<>();
                for (int index = 0; index < editor.getJMenuBar().getMenuCount(); index++) {
                    names.add(editor.getJMenuBar().getMenu(index).getText());
                }
                return names;
            }));
            assertTrue(onEdt(() -> editor.positionCue.getText().matches("\\d+%")));
            assertEquals(javax.swing.JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED,
                onEdt(() -> editor.getActivePane().getScrollPane().getVerticalScrollBarPolicy()));
        } finally {
            disposeEditor(editor);
        }
    }

    @Test
    void explorerFileDropOpensTheFileInASplitWithoutReplacingTheTargetPane() throws Exception {
        assumeSwingAvailable();
        Path home = tempDir.resolve("home-explorer-drop");
        Path workspace = tempDir.resolve("explorer-drop-workspace");
        Path source = workspace.resolve("source.txt");
        Path dropped = workspace.resolve("dropped.txt");
        Files.createDirectories(home);
        Files.createDirectories(workspace);
        Files.writeString(source, "source\n", StandardCharsets.UTF_8);
        Files.writeString(dropped, "dropped\n", StandardCharsets.UTF_8);

        Texteditor editor = createEditor(home, source);
        try {
            EditorPane targetPane = onEdt(editor::getActivePane);
            assertEquals("Tree pane opened", onEdt(() -> editor.showFileTree(workspace.toString())));

            String result = onEdt(() -> editor.openFileInSplit(dropped.toFile(), targetPane,
                WindowLayoutNode.Orientation.HORIZONTAL, false));

            assertEquals("Opening in split: " + dropped.toAbsolutePath(), result);
            assertTrue(awaitCurrentFile(editor, dropped));
            assertEquals(3, onEdt(() -> editor.editorPanes.size()));
            assertEquals(source.toAbsolutePath().toString(), onEdt(() -> targetPane.getBuffer().getFilePath()));
            assertEquals(dropped.toAbsolutePath().toString(), onEdt(() -> editor.getCurrentBuffer().getFilePath()));
            assertTrue(onEdt(() -> editor.treePane.getAlternateComponent() instanceof FileTreePanel));
            assertEquals(WindowLayoutNode.Orientation.HORIZONTAL, onEdt(() -> editor.windowLayoutRoot.getOrientation()));
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
    void cmakePresetDryRunsUseDirectArgumentsWithoutReadingWorkspaceTasks() throws Exception {
        assumeSwingAvailable();
        Path home = tempDir.resolve("home-cmake-preset-task");
        Path workspace = Files.createDirectories(tempDir.resolve("cmake-preset-project"));
        Path source = workspace.resolve("main.cpp");
        Files.createDirectories(home);
        Files.writeString(source, "int main() {}\n", StandardCharsets.UTF_8);
        Files.writeString(workspace.resolve("CMakeUserPresets.json"), "{}\n", StandardCharsets.UTF_8);
        Files.writeString(workspace.resolve(".shedtasks"), "[task.bad\n", StandardCharsets.UTF_8);

        Texteditor editor = createEditor(home, source);
        try {
            onEdt(() -> { editor.workspaceController.addDirectory(workspace.toFile(), true); return null; });
            String result = onEdt(() -> editor.handleTaskCommand("cmake dry-run build \"debug local\""));

            assertEquals("Task dry run shown (not started)", result);
            String output = onEdt(() -> editor.getCurrentBuffer().getContent());
            assertTrue(output.contains("Task dry run: cmake-preset-build"));
            assertTrue(output.contains("command: \"cmake\" \"--build\" \"--preset\" \"debug local\""));
            assertTrue(output.contains("shell: direct"));

            assertEquals("Task dry run shown (not started)", onEdt(() -> editor.handleTaskCommand("cmake dry-run package release")));
            assertTrue(onEdt(() -> editor.getCurrentBuffer().getContent()).contains("command: \"cpack\" \"--preset\" \"release\""));
            assertEquals("Task dry run shown (not started)", onEdt(() -> editor.handleTaskCommand("cmake dry-run workflow ci")));
            assertTrue(onEdt(() -> editor.getCurrentBuffer().getContent()).contains("command: \"cmake\" \"--workflow\" \"--preset\" \"ci\""));
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
                editor.remoteWorkspaceSessions.activate("ssh-project", new RemoteWorkspace() {
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
                editor.remoteWorkspaceSessions.activate("ssh-project", new RemoteWorkspace() {
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
                editor.remoteWorkspaceSessions.activate("ssh-project", new RemoteWorkspace() {
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
            assertEquals(":window", onEdt(() -> editor.completeCommand(":wind")));
            assertEquals(":zoom", onEdt(() -> editor.completeCommand(":zoo")));
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
            FileBuffer source = onEdt(editor::getCurrentBuffer);
            assertEquals("Showing remote workspaces", onEdt(() -> editor.commandHandler.execute("remote forward list")));
            ShedInformationDialog page = onEdt(() -> Arrays.stream(editor.getOwnedWindows())
                .filter(window -> window instanceof ShedInformationDialog && "Remote Workspaces".equals(((ShedInformationDialog) window).getTitle()))
                .map(window -> (ShedInformationDialog) window).findFirst().orElseThrow());
            String output = onEdt(() -> descendants(page, javax.swing.JEditorPane.class).getFirst().getText());
            assertTrue(output.contains("SSH loopback forwards"));
            assertTrue(output.contains("No SSH forwards active."));
            assertSame(source, onEdt(editor::getCurrentBuffer));
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
                assertEquals("Quickfix opened", editor.commandHandler.execute("quickfix open"));
                return null;
            });

            String result = onEdt(() -> editor.commandHandler.execute("quickfix current 1"));
            assertEquals("Opening: target.txt", result);
            assertTrue(awaitCurrentFile(editor, target));
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
            FileBuffer terminalBuffer = onEdt(() -> {
                assertEquals("Terminal opened", editor.commandHandler.execute("term"));
                assertEquals(1, editor.ptyTerminalPanes.size());
                EditorPane terminalPane = editor.getActivePane();
                assertNotNull(terminalPane.getTerminalPane());
                assertSame(terminalPane.getTerminalPane().getComponent(), terminalPane.getComponent());
                return terminalPane.getBuffer();
            });
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
            FileBuffer terminalBuffer = onEdt(terminalEditorPane::getBuffer);

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
    void debugFunctionBreakpointCommandsPersistNamedBreakpointOptions() throws Exception {
        assumeSwingAvailable();
        Path home = tempDir.resolve("home-debug-function-breakpoints");
        Path file = tempDir.resolve("debug-function-breakpoints.go");
        Files.createDirectories(home);
        Files.writeString(file, "package main\nfunc main() {}\n", StandardCharsets.UTF_8);

        Texteditor editor = createEditor(home, file);
        try {
            assertEquals("Function breakpoint 'main.main' added.", onEdt(() -> editor.commandHandler.execute("debug function add main.main")));
            assertEquals("Function breakpoint 'main.main' updated.",
                onEdt(() -> editor.commandHandler.execute("debug function condition main.main -- count > 0")));
            assertEquals("Function breakpoint 'main.main' updated.", onEdt(() -> editor.commandHandler.execute("debug function hit main.main -- 3")));
            assertEquals("Function breakpoint 'main.main' updated.", onEdt(() -> editor.commandHandler.execute("debug function disable main.main")));

            FunctionBreakpointStore.Breakpoint breakpoint = onEdt(() -> editor.debugSessionController.functionBreakpointsForPanel().getFirst());
            assertFalse(breakpoint.enabled());
            assertEquals("count > 0", breakpoint.condition());
            assertEquals("3", breakpoint.hitCondition());
            assertEquals("Showing function breakpoints.", onEdt(() -> editor.commandHandler.execute("debug function list")));
            assertTrue(onEdt(() -> editor.getCurrentBuffer().getContent()).contains("main.main"));
        } finally {
            disposeEditor(editor);
        }
    }

    @Test
    void debugInstructionBreakpointCommandsPersistOpaqueLocations() throws Exception {
        assumeSwingAvailable();
        Path home = tempDir.resolve("home-debug-instruction-breakpoints");
        Path file = tempDir.resolve("debug-instruction-breakpoints.cpp");
        Files.createDirectories(home);
        Files.writeString(file, "int main() { return 0; }\n", StandardCharsets.UTF_8);

        Texteditor editor = createEditor(home, file);
        try {
            assertEquals("Instruction breakpoint 'module!0x100' added.",
                onEdt(() -> editor.commandHandler.execute("debug instruction add module!0x100 4")));
            assertEquals("Instruction breakpoint 'module!0x100' updated.",
                onEdt(() -> editor.commandHandler.execute("debug instruction condition module!0x100 4 -- counter > 0")));
            assertEquals("Instruction breakpoint 'module!0x100' updated.",
                onEdt(() -> editor.commandHandler.execute("debug instruction hit module!0x100 4 -- 3")));
            assertEquals("Instruction breakpoint 'module!0x100' updated.",
                onEdt(() -> editor.commandHandler.execute("debug instruction disable module!0x100 4")));
            assertEquals("Showing instruction breakpoints.", onEdt(() -> editor.commandHandler.execute("debug instruction list")));
            String content = onEdt(() -> editor.getCurrentBuffer().getContent());
            assertTrue(content.contains("module!0x100"));
            assertTrue(content.contains("offset=4"));
            assertTrue(content.contains("counter > 0"));
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
    void explicitVscodeDefaultBuildTaskRunsWhenNoNamedBuildTaskExists() throws Exception {
        assumeSwingAvailable();
        Path home = tempDir.resolve("home-vscode-default-build");
        Path workspace = Files.createDirectories(tempDir.resolve("vscode-default-build-project"));
        Path source = workspace.resolve("Main.java");
        Path marker = workspace.resolve("default-build.marker");
        Files.createDirectories(home);
        Files.writeString(source, "class Main {}\n", StandardCharsets.UTF_8);
        Files.createDirectories(workspace.resolve(".vscode"));
        Files.writeString(workspace.resolve(".vscode/tasks.json"), """
            {"version":"2.0.0","tasks":[
              {"label":"Project build","type":"process","command":"sh",
               "args":["-c","printf built > default-build.marker"],"problemMatcher":[],
               "group":{"kind":"build","isDefault":true}}
            ]}
            """, StandardCharsets.UTF_8);

        Texteditor editor = createEditor(home, source);
        try {
            String requested = onEdt(() -> editor.handleTaskCommand("run build"));
            int jobId = Integer.parseInt(requested.replaceAll("Task job ([0-9]+) started.*", "$1"));

            assertTrue(awaitJobCompletion(editor, jobId));
            assertEquals("built", Files.readString(marker, StandardCharsets.UTF_8));
            assertTrue(onEdt(() -> editor.jobQuickfixController.taskOutputForPanel(jobId).contains("==> task vscode-project-build")));
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
              "folders": [{"path": "workspace-launch-project", "name": "Workspace launch"}],
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
            assertEquals("Workspace launch — " + workspace, onEdt(() -> editor.workspaceController.displayRoot(workspace)));
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
    void importedWorkspaceFileExclusionsApplyToExplorerOnly() throws Exception {
        assumeSwingAvailable();
        Path home = tempDir.resolve("home-workspace-excludes");
        Path workspace = tempDir.resolve("workspace-excludes-project");
        Path file = workspace.resolve("Main.java");
        Path manifest = tempDir.resolve("excludes.code-workspace");
        Files.createDirectories(home.resolve(".shed"));
        Files.createDirectories(workspace.resolve("generated"));
        Files.writeString(file, "class Main {}\n", StandardCharsets.UTF_8);
        Files.writeString(workspace.resolve("visible.txt"), "visible\n", StandardCharsets.UTF_8);
        Files.writeString(workspace.resolve("generated/Model.java"), "class Model {}\n", StandardCharsets.UTF_8);
        Files.writeString(manifest, """
            {"folders": [{"path": "workspace-excludes-project"}], "settings": {"files.exclude": {"**/generated/**": true}}}
            """, StandardCharsets.UTF_8);

        Texteditor editor = createEditor(home, file);
        try {
            assertEquals("Imported 1 workspace folder", onEdt(() -> editor.handleWorkspaceProfileCommand("import " + manifest)));
            List<String> visible = onEdt(() -> java.util.Arrays.stream(editor.treeGitController.listTreeChildren(workspace.toFile()))
                .map(File::getName).toList());

            assertTrue(visible.contains("visible.txt"));
            assertFalse(visible.contains("generated"));
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
    void testExplorerUsesAnExplicitCtestPresetWithoutGuessingABuildTree() throws Exception {
        assumeSwingAvailable();
        Path cmake = Files.isExecutable(Path.of("/usr/bin/cmake")) ? Path.of("/usr/bin/cmake") : Path.of("/bin/cmake");
        Path ctest = Files.isExecutable(Path.of("/usr/bin/ctest")) ? Path.of("/usr/bin/ctest") : Path.of("/bin/ctest");
        Path cpack = Files.isExecutable(Path.of("/usr/bin/cpack")) ? Path.of("/usr/bin/cpack") : Path.of("/bin/cpack");
        org.junit.jupiter.api.Assumptions.assumeTrue(Files.isExecutable(cmake) && Files.isExecutable(ctest) && Files.isExecutable(cpack),
            "CMake, CTest, and CPack are unavailable");
        Path home = tempDir.resolve("home-ctest-preset");
        Path workspace = tempDir.resolve("ctest-preset-workspace");
        Path source = workspace.resolve("CMakeLists.txt");
        Files.createDirectories(home.resolve(".shed"));
        Files.createDirectories(workspace);
        Files.writeString(source, """
            cmake_minimum_required(VERSION 3.21)
            project(shed_ctest_preset VERSION 1.0 LANGUAGES NONE)
            enable_testing()
            add_test(NAME preset-smoke COMMAND "${CMAKE_COMMAND}" -E true)
            include(CPack)
            """, StandardCharsets.UTF_8);
        Files.writeString(workspace.resolve("CMakePresets.json"), """
            {
              "version": 6,
              "configurePresets": [{"name": "shed-test", "generator": "Unix Makefiles", "binaryDir": "${sourceDir}/out/shed-test"}],
              "testPresets": [{"name": "shed-test", "configurePreset": "shed-test"}],
              "packagePresets": [{"name": "shed-package", "configurePreset": "shed-test", "generators": ["TGZ"]}],
              "workflowPresets": [{"name": "shed-workflow", "steps": [
                {"type": "configure", "name": "shed-test"},
                {"type": "test", "name": "shed-test"},
                {"type": "package", "name": "shed-package"}
              ]}]
            }
            """, StandardCharsets.UTF_8);
        Process configure = new ProcessBuilder(cmake.toString(), "--preset", "shed-test").directory(workspace.toFile()).start();
        assertTrue(configure.waitFor(20, TimeUnit.SECONDS));
        String configureOutput = new String(configure.getInputStream().readAllBytes(), StandardCharsets.UTF_8)
            + new String(configure.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
        assertEquals(0, configure.exitValue(), configureOutput);
        Process packagePreset = new ProcessBuilder(cpack.toString(), "--preset", "shed-package").directory(workspace.toFile()).start();
        assertTrue(packagePreset.waitFor(20, TimeUnit.SECONDS));
        String packageOutput = new String(packagePreset.getInputStream().readAllBytes(), StandardCharsets.UTF_8)
            + new String(packagePreset.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
        assertEquals(0, packagePreset.exitValue(), packageOutput);
        Process workflow = new ProcessBuilder(cmake.toString(), "--workflow", "--preset", "shed-workflow").directory(workspace.toFile()).start();
        assertTrue(workflow.waitFor(20, TimeUnit.SECONDS));
        String workflowOutput = new String(workflow.getInputStream().readAllBytes(), StandardCharsets.UTF_8)
            + new String(workflow.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
        assertEquals(0, workflow.exitValue(), workflowOutput);

        Texteditor editor = createEditor(home, source);
        try {
            String listing = onEdt(() -> {
                editor.testController.selectRoot(workspace);
                return editor.handleTestCommand("ctest list");
            });
            int listingJob = Integer.parseInt(listing.replaceAll(".*\\(job ([0-9]+)\\).*", "$1"));
            assertTrue(awaitJobCompletion(editor, listingJob));
            String listedPresets = onEdt(() -> editor.getCurrentBuffer().getContent());
            assertTrue(listedPresets.contains("Available test presets"));
            assertTrue(listedPresets.contains("shed-test"));

            String selected = onEdt(() -> {
                editor.testController.selectRoot(workspace);
                return editor.handleTestCommand("ctest use shed-test");
            });
            assertTrue(selected.startsWith("Test discovery requested"), selected);
            assertTrue(awaitTestDiscovery(editor, workspace, "preset-smoke"));
            assertEquals("shed-test", onEdt(() -> editor.testController.snapshot(workspace).ctestPreset()));
            TestService.TestCase smoke = onEdt(() -> editor.testController.snapshot(workspace).tests().stream()
                .filter(test -> "preset-smoke".equals(test.id())).findFirst().orElseThrow());
            assertEquals("Test run requested", onEdt(() -> editor.testController.runSelection(workspace, smoke)));
            boolean passed = awaitTestStatus(editor, workspace, "preset-smoke", TestService.Status.PASSED);
            assertTrue(passed, onEdt(() -> editor.testController.snapshot(workspace).output()));

            assertEquals("Test discovery complete", onEdt(() -> editor.handleTestCommand("ctest clear")));
            assertTrue(onEdt(() -> editor.testController.snapshot(workspace).ctestPreset()).isBlank());
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
            FileBuffer source = onEdt(editor::getCurrentBuffer);
            String result = onEdt(() -> editor.commandHandler.execute("workspace index enable"));

            assertEquals("Persistent workspace indexing enabled", result);
            assertTrue(onEdt(() -> editor.configManager.getWorkspaceIndexEnabled()));
            ShedInformationDialog page = onEdt(() -> Arrays.stream(editor.getOwnedWindows())
                .filter(window -> window instanceof ShedInformationDialog && "Workspace Index Status".equals(((ShedInformationDialog) window).getTitle()))
                .map(window -> (ShedInformationDialog) window).findFirst().orElseThrow());
            String output = onEdt(() -> descendants(page, javax.swing.JEditorPane.class).getFirst().getText());
            assertTrue(output.contains("Current search source"));
            assertTrue(output.contains("persistent workspace index"));
            assertTrue(output.contains("Persistent-index preference"));
            assertTrue(output.contains("enabled"));
            assertSame(source, onEdt(editor::getCurrentBuffer));
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

    @Test
    void importedWorkspaceSearchExclusionsFilterWorkspaceSearch() throws Exception {
        assumeSwingAvailable();
        Path home = tempDir.resolve("home-workspace-search-excludes");
        Path workspace = tempDir.resolve("workspace-search-excludes");
        Path file = workspace.resolve("Main.java");
        Path visible = workspace.resolve("visible.txt");
        Path excluded = workspace.resolve("generated/Hidden.txt");
        Path manifest = tempDir.resolve("search-excludes.code-workspace");
        Files.createDirectories(home);
        Files.createDirectories(excluded.getParent());
        Files.writeString(file, "class Main {}\n", StandardCharsets.UTF_8);
        Files.writeString(visible, "needle\n", StandardCharsets.UTF_8);
        Files.writeString(excluded, "needle\n", StandardCharsets.UTF_8);
        initializeGit(workspace);
        Files.writeString(manifest, """
            {"folders": [{"path": "workspace-search-excludes"}], "settings": {"search.exclude": {"**/generated/**": true}}}
            """, StandardCharsets.UTF_8);

        Texteditor editor = createEditor(home, file);
        try {
            assertEquals("Imported 1 workspace folder", onEdt(() -> editor.handleWorkspaceProfileCommand("import " + manifest)));
            String result = onEdt(() -> editor.commandHandler.execute("grep needle"));
            int jobId = Integer.parseInt(result.substring("Started workspace search job ".length()));
            assertTrue(awaitSearchCompletion(editor, jobId));

            List<QuickfixService.Entry> entries = onEdt(() -> editor.quickfixService.entries());
            assertEquals(1, entries.size());
            assertEquals(visible.toRealPath(), Path.of(entries.getFirst().getFilePath()).toRealPath());
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

    private static boolean awaitTestStatus(Texteditor editor, Path root, String testId, TestService.Status expected) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline) {
            boolean found = onEdt(() -> editor.testController.snapshot(root).tests().stream()
                .anyMatch(test -> testId.equals(test.id()) && expected == test.status()));
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

    private static boolean awaitCurrentFile(Texteditor editor, Path file) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < deadline) {
            if (onEdt(() -> file.toAbsolutePath().toString().equals(editor.getCurrentBuffer().getFilePath()))) return true;
            Thread.sleep(20);
        }
        return false;
    }

    private static void initializeGit(Path root) throws Exception {
        Process process = new ProcessBuilder("git", "init", "--quiet", root.toString()).start();
        assertEquals(0, process.waitFor());
    }

    private static void press(Texteditor editor, char character) {
        editor.keyPressed(new KeyEvent(editor.writingArea, KeyEvent.KEY_PRESSED, 0L, 0,
            KeyEvent.getExtendedKeyCodeForChar(character), character));
    }

    private static void pressEscape(Texteditor editor) {
        editor.keyPressed(new KeyEvent(editor.writingArea, KeyEvent.KEY_PRESSED, 0L, 0,
            KeyEvent.VK_ESCAPE, KeyEvent.CHAR_UNDEFINED));
    }

    private static void enableVim(Texteditor editor) {
        editor.configManager.set("keymap.profile", "vim");
        editor.setMode(EditorMode.NORMAL);
    }

    private static Texteditor createEditor(Path home, Path... targets) throws Exception {
        String previousHome = System.getProperty("user.home");
        Texteditor editor = onEdt(() -> {
            System.setProperty("user.home", home.toString());
            try {
                String[] arguments = Arrays.stream(targets).map(Path::toString).toArray(String[]::new);
                return new Texteditor(arguments);
            } finally {
                if (previousHome == null) {
                    System.clearProperty("user.home");
                } else {
                    System.setProperty("user.home", previousHome);
                }
            }
        });
        Path lastFile = Arrays.stream(targets).filter(path -> !Files.isDirectory(path))
            .reduce((ignored, next) -> next).orElse(null);
        if (lastFile != null) assertTrue(awaitCurrentFile(editor, lastFile), "startup file did not finish loading");
        return editor;
    }

    private static Texteditor createEmptyEditor(Path home) throws Exception {
        String previousHome = System.getProperty("user.home");
        return onEdt(() -> {
            System.setProperty("user.home", home.toString());
            try {
                return new Texteditor(new String[0]);
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

    private static <T extends Component> List<T> descendants(Component root, Class<T> type) {
        List<T> result = new ArrayList<>();
        if (root == null) return result;
        if (type.isInstance(root)) result.add(type.cast(root));
        if (root instanceof java.awt.Container container) {
            for (Component child : container.getComponents()) result.addAll(descendants(child, type));
        }
        return result;
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
