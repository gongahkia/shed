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
    void isolatedPresentationViewRetainsExistingPlainVimAndEmacsInputRoutes() throws Exception {
        assumeSwingAvailable(); Path file = Files.writeString(tempDir.resolve("presentation.md"), "# Heading\n\nbody\n");
        Texteditor editor = createEditor(tempDir.resolve("presentation-home"), file);
        try {
            assertTrue(awaitUiState(() -> !editor.stagedStartupPending));
            for (String profile : List.of("plain", "vim", "emacs")) {
                boolean dirtyBefore = onEdt(() -> editor.getCurrentBuffer().isModified());
                var prototype = onEdt(() -> {
                    editor.configManager.set("keymap.profile", profile); editor.setMode(EditorMode.INSERT);
                    return new MarkdownPresentationPrototype(editor.writingArea, () -> {
                        var buffer = editor.getCurrentBuffer();
                        return new MarkdownPresentationPrototype.Binding(MarkdownSemanticSnapshot.Source.capture(editor.documentStore.id(buffer), buffer), buffer.getDocument());
                    });
                });
                try {
                    var ticket = onEdt(prototype::capture);
                    var semantic = MarkdownHeadingAnalysis.analyze(ticket.source(), () -> false).semantic();
                    var frame = MarkdownPresentationFrame.prepare(semantic, ticket.settings(), ticket.generation(), () -> false);
                    onEdt(() -> {
                        assertTrue(prototype.install(frame)); var buffer = editor.getCurrentBuffer(); var text = buffer.textSnapshot(); long revision = buffer.documentRevision();
                        editor.writingArea.setCaretPosition(0); assertSame(text, buffer.textSnapshot()); assertEquals(revision, buffer.documentRevision()); assertEquals(dirtyBefore, buffer.isModified());
                        var typed = new KeyEvent(editor.writingArea, KeyEvent.KEY_TYPED, 0, 0, KeyEvent.VK_UNDEFINED, 'x'); editor.keyTyped(typed);
                        if (!typed.isConsumed()) editor.writingArea.getActionMap().get(javax.swing.text.DefaultEditorKit.defaultKeyTypedAction)
                            .actionPerformed(new ActionEvent(editor.writingArea, ActionEvent.ACTION_PERFORMED, "x"));
                        assertEquals("x# Heading\n\nbody\n", buffer.getContent(), profile); assertFalse(prototype.styled());
                        buffer.getUndoManager().undo(); assertEquals("# Heading\n\nbody\n", buffer.getContent()); return null;
                    });
                } finally { onEdt(() -> { prototype.close(); return null; }); }
            }
        } finally { disposeEditor(editor); }
    }

    @Test
    void markdownCheckedOpenRejectsATargetIntroducedBeforeInstallation() throws Exception {
        assumeSwingAvailable(); Path project = Files.createDirectories(tempDir.resolve("checked-open-project"));
        Path source = Files.writeString(project.resolve("source.md"), "[[target]]"); Path target = Files.writeString(project.resolve("target.md"), "# Target");
        Texteditor editor = createEditor(tempDir.resolve("checked-open-home"), source); var completed = new java.util.concurrent.CountDownLatch(1);
        var error = new java.util.concurrent.atomic.AtomicReference<java.io.IOException>(); var calls = new java.util.concurrent.atomic.AtomicInteger();
        try {
            assertTrue(awaitUiState(() -> !editor.stagedStartupPending)); FileBuffer original = onEdt(editor::getCurrentBuffer);
            var index = MarkdownLinkIndex.build(project, List.of(project), null, List.of(), Set.of(), () -> false);
            var edge = index.outgoing(source).edges().getFirst();
            var prepared = MarkdownLinkNavigation.prepare(index, List.of(project), edge, null, false, List.of(), () -> false);
            onEdt(() -> { editor.openMarkdownTarget(prepared, () -> {
                if (calls.incrementAndGet() == 3) {
                    try { editor.buffers.add(new FileBuffer(target.toFile())); } catch (java.io.IOException failure) { throw new RuntimeException(failure); }
                } return true;
            }, () -> { throw new AssertionError("Unvalidated concurrent target cannot install"); }, failure -> { error.set(failure); completed.countDown(); }); return null; });
            assertTrue(completed.await(20, TimeUnit.SECONDS)); assertNotNull(error.get());
            onEdt(() -> { assertSame(original, editor.getCurrentBuffer()); assertEquals("[[target]]", original.getContent()); assertFalse(original.isModified()); assertFalse(original.getUndoManager().canUndo()); return null; });
        } finally { disposeEditor(editor); }
    }

    @Test
    void markdownActualOutgoingPickerAcceptsAlreadyOpenTargetAfterModalFocusRestoration() throws Exception {
        assumeSwingAvailable(); Path project = Files.createDirectories(tempDir.resolve("outgoing-accept-project"));
        Path source = Files.writeString(project.resolve("source.md"), "[[target]]"); Path target = Files.writeString(project.resolve("target.md"), "# Target");
        Texteditor editor = createEditor(tempDir.resolve("outgoing-accept-home"), source);
        var accepted = new java.util.concurrent.CountDownLatch(1); var error = new java.util.concurrent.atomic.AtomicReference<Throwable>();
        var events = new java.util.concurrent.atomic.AtomicInteger();
        try {
            assertTrue(awaitUiState(() -> !editor.stagedStartupPending)); var original = onEdt(editor::getCurrentBuffer); var text = onEdt(original::textSnapshot);
            onEdt(() -> {
                editor.workspaceRoots.add(project); editor.openFile(target.toFile()); editor.switchToBuffer(editor.buffers.indexOf(original)); editor.writingArea.setCaretPosition(3);
                original.getDocument().addDocumentListener(new javax.swing.event.DocumentListener() {
                    public void insertUpdate(javax.swing.event.DocumentEvent event) { events.incrementAndGet(); }
                    public void removeUpdate(javax.swing.event.DocumentEvent event) { events.incrementAndGet(); }
                    public void changedUpdate(javax.swing.event.DocumentEvent event) { events.incrementAndGet(); }
                });
                javax.swing.Timer driver = new javax.swing.Timer(5, event -> {
                    var dialog = Arrays.stream(editor.getOwnedWindows()).filter(window -> window instanceof javax.swing.JDialog d && d.isShowing()
                        && d.getTitle().equals("Markdown Outgoing Links")).map(window -> (javax.swing.JDialog) window).findFirst().orElse(null);
                    if (dialog == null) return; ((javax.swing.Timer) event.getSource()).stop();
                    try { descendants(dialog, javax.swing.JTextField.class).getFirst().postActionEvent(); }
                    catch (Throwable failure) { error.set(failure); dialog.dispose(); }
                    finally { accepted.countDown(); }
                }); driver.start(); editor.showMessage(editor.commandHandler.execute("markdown outgoing")); return null;
            });
            assertTrue(accepted.await(20, TimeUnit.SECONDS)); if (error.get() != null) throw new AssertionError(error.get()); assertTrue(awaitCurrentFile(editor, target));
            assertTrue(awaitUiState(() -> editor.markdownController.linkDiagnostics().worker() == 0));
            onEdt(() -> { assertSame(text, original.textSnapshot()); assertEquals(0, events.get()); assertFalse(original.isModified()); assertFalse(original.getUndoManager().canUndo());
                assertEquals(2, editor.buffers.size()); editor.commandHandler.execute("back"); assertSame(original, editor.getCurrentBuffer()); assertEquals(3, editor.writingArea.getCaretPosition()); return null;
            });
        } finally { disposeEditor(editor); }
    }

    @Test
    void markdownExternalNavigationWithoutProjectUsesSemanticCodeExclusionAndCreatesNoIndex() throws Exception {
        assumeSwingAvailable(); String text = "[web](https://example.invalid)\n`[fake](https://example.invalid)`";
        Path file = Files.writeString(tempDir.resolve("external.md"), text); Texteditor editor = createEditor(tempDir.resolve("external-home"), file);
        var opened = new java.util.concurrent.atomic.AtomicInteger();
        try {
            assertTrue(awaitUiState(() -> !editor.stagedStartupPending));
            onEdt(() -> { editor.markdownController.linkBrowser = uri -> { opened.incrementAndGet(); return "External checked"; };
                assertTrue(editor.workspaceRoots.all().isEmpty()); editor.writingArea.setCaretPosition(2); editor.showMessage(editor.goToMarkdownLink()); return null;
            }); assertTrue(awaitUiState(() -> opened.get() == 1 && editor.markdownController.linkDiagnostics().active() == 0));
            onEdt(() -> { editor.writingArea.setCaretPosition(text.indexOf("fake")); editor.showMessage(editor.goToMarkdownLink()); return null; });
            assertTrue(awaitUiState(() -> editor.markdownController.linkDiagnostics().active() == 0));
            onEdt(() -> { assertEquals(1, opened.get()); var field = Texteditor.class.getDeclaredField("markdownLinks"); field.setAccessible(true); assertEquals(null, field.get(editor));
                assertEquals(text, editor.getCurrentBuffer().getContent()); assertFalse(editor.getCurrentBuffer().isModified()); return null;
            });
        } finally { disposeEditor(editor); }
    }

    @Test
    void markdownActualAmbiguousPickerAcceptsKeyboardChoiceAndCanonicalAliasesHaveBacklinks() throws Exception {
        assumeSwingAvailable(); Path project = Files.createDirectories(tempDir.resolve("keyboard-link-project"));
        Path source = Files.writeString(project.resolve("source.md"), "[[note]]");
        Files.createDirectories(project.resolve("a")); Files.createDirectories(project.resolve("b"));
        Files.writeString(project.resolve("a/note.md"), "# A"); Path target = Files.writeString(project.resolve("b/note.md"), "# B");
        Texteditor editor = createEditor(tempDir.resolve("keyboard-link-home"), source);
        var done = new java.util.concurrent.CountDownLatch(1); var error = new java.util.concurrent.atomic.AtomicReference<Throwable>();
        try {
            assertTrue(awaitUiState(() -> !editor.stagedStartupPending));
            onEdt(() -> {
                editor.workspaceRoots.add(project); editor.writingArea.setCaretPosition(3);
                javax.swing.Timer driver = new javax.swing.Timer(10, event -> {
                    var dialog = Arrays.stream(editor.getOwnedWindows()).filter(w -> w instanceof javax.swing.JDialog d && d.isShowing()
                        && d.getTitle().equals("Markdown Link Targets")).map(w -> (javax.swing.JDialog) w).findFirst().orElse(null);
                    if (dialog == null) return; ((javax.swing.Timer) event.getSource()).stop();
                    try {
                        var field = descendants(dialog, javax.swing.JTextField.class).getFirst(); var list = descendants(dialog, javax.swing.JList.class).getFirst();
                        assertEquals(2, list.getModel().getSize());
                        Object next = field.getInputMap().get(javax.swing.KeyStroke.getKeyStroke(KeyEvent.VK_DOWN, 0));
                        field.getActionMap().get(next).actionPerformed(new ActionEvent(field, 0, "down"));
                        assertTrue(list.getSelectedValue().toString().contains("b/note.md")); field.postActionEvent();
                    } catch (Throwable failure) { error.set(failure); dialog.dispose(); }
                    finally { done.countDown(); }
                }); driver.start(); editor.showMessage(editor.goToMarkdownLink()); return null;
            });
            assertTrue(done.await(20, TimeUnit.SECONDS)); if (error.get() != null) throw new AssertionError(error.get()); assertTrue(awaitCurrentFile(editor, target));
            Path alias = project.resolve("alias.md"); Files.createSymbolicLink(alias, target);
            Files.writeString(project.resolve("reference.md"), "[[b/note]]");
            onEdt(() -> { editor.openFile(alias.toFile()); editor.markdownController.linkPicker = (title, rows) -> {
                assertEquals("Markdown Backlinks", title); assertTrue(rows.stream().anyMatch(row -> row.contains("reference.md"))); return null;
            }; editor.showMessage(editor.commandHandler.execute("markdown backlinks")); return null; });
            assertTrue(awaitUiState(() -> editor.markdownController.linkDiagnostics().active() == 0 && editor.markdownController.linkDiagnostics().worker() == 0));
            assertEquals(alias.toString(), onEdt(() -> editor.getCurrentBuffer().getFilePath()));
        } finally { disposeEditor(editor); }
    }

    @Test
    void markdownRapidNavigationUnderWorkerSaturationCoalescesAndSupersededResultsCannotCancelNewest() throws Exception {
        assumeSwingAvailable(); Path project = Files.createDirectories(tempDir.resolve("saturated-link-project"));
        Path source = Files.writeString(project.resolve("source.md"), "[[target]]"); Path target = Files.writeString(project.resolve("target.md"), "# Target");
        Texteditor editor = createEditor(tempDir.resolve("saturated-link-home"), source);
        int workers = AsyncJobService.workerCount(); var entered = new java.util.concurrent.CountDownLatch(workers); var release = new java.util.concurrent.CountDownLatch(1);
        try {
            assertTrue(awaitUiState(() -> !editor.stagedStartupPending));
            onEdt(() -> { editor.workspaceRoots.add(project); editor.markdownLinks().request(project); return null; });
            assertTrue(awaitUiState(() -> editor.markdownLinks().diagnostics().running() == 0));
            onEdt(() -> { for (int i = 0; i < workers; i++) editor.asyncJobService.submit("navigation worker gate", token -> {
                entered.countDown(); release.await(20, TimeUnit.SECONDS); return null;
            }, null); return null; });
            assertTrue(entered.await(20, TimeUnit.SECONDS));
            onEdt(() -> { editor.writingArea.setCaretPosition(3); editor.showMessage(editor.goToMarkdownLink()); return null; });
            assertTrue(awaitUiState(() -> editor.markdownController.linkDiagnostics().worker() == 1));
            onEdt(() -> { for (int i = 0; i < 20; i++) editor.goToMarkdownLink(); return null; });
            assertTrue(awaitUiState(() -> editor.markdownLinks().diagnostics().running() == 0));
            onEdt(() -> {
                assertEquals(1, editor.markdownController.linkDiagnostics().worker()); assertTrue(editor.markdownController.linkDiagnostics().queued() <= 1);
                assertEquals(1, editor.buffers.size()); assertFalse(editor.getCurrentBuffer().isModified());
                assertTrue(editor.asyncJobService.list().stream().filter(job -> job.getDescription().equals("Resolve Markdown source identity")
                    || job.getDescription().equals("Validate Markdown navigation")).count() <= 1); return null;
            }); release.countDown(); assertTrue(awaitCurrentFile(editor, target));
            assertTrue(awaitUiState(() -> editor.markdownController.linkDiagnostics().active() == 0 && editor.markdownController.linkDiagnostics().worker() == 0));
        } finally { release.countDown(); disposeEditor(editor); }
    }

    @Test
    void markdownUsesActualPickerFilteringKeyboardDismissalAndReleasesListeners() throws Exception {
        assumeSwingAvailable(); Path project = Files.createDirectories(tempDir.resolve("real-link-picker"));
        Path source = Files.writeString(project.resolve("source.md"), "[[a]] [[b]]");
        Files.writeString(project.resolve("a.md"), ""); Files.writeString(project.resolve("b.md"), "");
        Texteditor editor = createEditor(tempDir.resolve("real-link-picker-home"), source);
        var completed = new java.util.concurrent.CountDownLatch(1); var failure = new java.util.concurrent.atomic.AtomicReference<Throwable>();
        var field = new java.util.concurrent.atomic.AtomicReference<javax.swing.JTextField>();
        var dialogRef = new java.util.concurrent.atomic.AtomicReference<javax.swing.JDialog>();
        try {
            assertTrue(awaitUiState(() -> !editor.stagedStartupPending)); var buffer = onEdt(editor::getCurrentBuffer); var snapshot = onEdt(buffer::textSnapshot);
            int listenerCount = onEdt(() -> editor.writingArea.getCaretListeners().length);
            onEdt(() -> {
                editor.workspaceRoots.add(project);
                javax.swing.Timer driver = new javax.swing.Timer(10, event -> {
                    var dialog = Arrays.stream(editor.getOwnedWindows()).filter(w -> w instanceof javax.swing.JDialog d && d.isShowing()
                        && d.getTitle().equals("Markdown Outgoing Links")).map(w -> (javax.swing.JDialog) w).findFirst().orElse(null);
                    if (dialog == null) return; ((javax.swing.Timer) event.getSource()).stop(); dialogRef.set(dialog);
                    try {
                        var filter = descendants(dialog, javax.swing.JTextField.class).getFirst(); field.set(filter);
                        var list = descendants(dialog, javax.swing.JList.class).getFirst();
                        assertEquals(editor.resolveUiFont(), list.getFont()); assertEquals(editor.configManager.getFloatingSurfaceColor(), list.getBackground());
                        assertEquals(2, list.getModel().getSize()); assertTrue(list.getModel().getElementAt(0).toString().contains(":1:1"));
                        filter.setText("b");
                        dialog.getRootPane().getActionMap().get("dismiss").actionPerformed(new ActionEvent(dialog, 0, "escape"));
                    } catch (Throwable error) { failure.set(error); dialog.dispose(); }
                    finally { completed.countDown(); }
                }); driver.start(); editor.showMessage(editor.commandHandler.execute("markdown outgoing")); return null;
            });
            assertTrue(completed.await(20, TimeUnit.SECONDS)); if (failure.get() != null) throw new AssertionError(failure.get());
            assertTrue(awaitUiState(() -> editor.markdownController.linkDiagnostics().active() == 0));
            onEdt(() -> {
                assertFalse(dialogRef.get().isDisplayable()); assertEquals(0, field.get().getActionListeners().length);
                assertEquals(listenerCount, editor.writingArea.getCaretListeners().length); assertSame(snapshot, buffer.textSnapshot());
                assertSame(buffer, editor.getCurrentBuffer()); assertFalse(buffer.isModified()); assertFalse(buffer.getUndoManager().canUndo()); return null;
            });
        } finally { disposeEditor(editor); }
    }

    @Test
    void markdownPendingNavigationCancelsOnPaneProjectAndBufferChangesWithoutHistoryOrMutation() throws Exception {
        assumeSwingAvailable(); Path project = Files.createDirectories(tempDir.resolve("pending-link-project"));
        Path source = Files.writeString(project.resolve("source.md"), "[[target]]"); Files.writeString(project.resolve("target.md"), "# Target");
        Texteditor editor = createEditor(tempDir.resolve("pending-link-home"), source);
        var entered = new java.util.concurrent.CountDownLatch(1); var release = new java.util.concurrent.CountDownLatch(1);
        try {
            assertTrue(awaitUiState(() -> !editor.stagedStartupPending)); var original = onEdt(editor::getCurrentBuffer);
            onEdt(() -> {
                editor.workspaceRoots.add(project);
                var service = new MarkdownProjectLinks(editor.workspaceRoots::all, List::of, (root, roots, old, overlays, force, stop) -> {
                    entered.countDown(); if (!release.await(20, TimeUnit.SECONDS)) throw new AssertionError("worker gate timed out");
                    return MarkdownLinkIndex.build(root, roots, old, overlays, force, () -> false);
                });
                var field = Texteditor.class.getDeclaredField("markdownLinks"); field.setAccessible(true); field.set(editor, service);
                editor.writingArea.setCaretPosition(3); editor.showMessage(editor.goToMarkdownLink()); return null;
            });
            assertTrue(entered.await(20, TimeUnit.SECONDS));
            onEdt(() -> { editor.splitWindow(true); assertEquals(0, editor.markdownController.linkDiagnostics().active());
                editor.workspaceRoots.remove(project); editor.markdownLinkRootsChanged(); return null;
            }); release.countDown();
            assertTrue(awaitUiState(() -> editor.markdownLinks().diagnostics().running() == 0));
            onEdt(() -> {
                assertSame(original, editor.getCurrentBuffer()); assertEquals("[[target]]", original.getContent()); assertFalse(original.isModified());
                assertFalse(original.getUndoManager().canUndo()); assertEquals(1, editor.buffers.size());
                assertEquals(0, editor.markdownLinks().diagnostics().partitions()); return null;
            });
        } finally { release.countDown(); disposeEditor(editor); }
    }

    @Test
    void markdownLinkCommandsFollowWikiRelativeFragmentsAndPreserveHistorySplitsAndSource() throws Exception {
        assumeSwingAvailable(); Path project = Files.createDirectories(tempDir.resolve("navigation-project"));
        String text = "[link](folder/note.md#second) [[folder/note#Second|Alias]]\n`[[wrong]]`\n```\n[[wrong]]\n```";
        Path source = Files.writeString(project.resolve("source.md"), text); Files.createDirectories(project.resolve("folder"));
        Path target = Files.writeString(project.resolve("folder/note.md"), "# First\n\n## Second\n");
        Texteditor editor = createEditor(tempDir.resolve("navigation-home"), source);
        try {
            assertTrue(awaitUiState(() -> !editor.stagedStartupPending));
            FileBuffer original = onEdt(editor::getCurrentBuffer); var snapshot = onEdt(original::textSnapshot); long revision = onEdt(original::documentRevision);
            int caretListeners = onEdt(() -> editor.writingArea.getCaretListeners().length);
            onEdt(() -> { editor.workspaceRoots.add(project); editor.writingArea.setCaretPosition(2); editor.showMessage(editor.commandHandler.execute("markdown link follow")); return null; });
            assertTrue(awaitCurrentFile(editor, target)); assertTrue(awaitUiState(() -> editor.writingArea.getCaretPosition() == 9));
            onEdt(() -> { editor.commandHandler.execute("back"); return null; });
            assertTrue(awaitCurrentFile(editor, source)); assertEquals(2, onEdt(() -> editor.writingArea.getCaretPosition()));
            onEdt(() -> {
                assertSame(snapshot, original.textSnapshot()); assertEquals(revision, original.documentRevision()); assertFalse(original.isModified()); assertFalse(original.getUndoManager().canUndo());
                editor.splitWindow(true); editor.writingArea.setCaretPosition(text.indexOf("Alias")); editor.showMessage(editor.goToMarkdownLink()); return null;
            });
            assertTrue(awaitCurrentFile(editor, target)); assertTrue(awaitUiState(() -> editor.writingArea.getCaretPosition() == 9));
            onEdt(() -> {
                assertEquals(1, editor.buffers.stream().filter(b -> b.hasFilePath() && b.getFilePath().equals(target.toString())).count());
                assertTrue(editor.editorPanes.stream().anyMatch(p -> p.getBuffer() == original));
                assertEquals(text, original.getContent()); assertFalse(original.isModified());
                assertEquals(caretListeners, editor.writingArea.getCaretListeners().length); return null;
            });
        } finally { disposeEditor(editor); }
    }

    @Test
    void markdownLinkPickersUseExistingRowsAndRejectSourceOrIndexChanges() throws Exception {
        assumeSwingAvailable(); Path project = Files.createDirectories(tempDir.resolve("picker-links"));
        Path source = Files.writeString(project.resolve("source.md"), "[[note]] [[note]]");
        Path a = Files.createDirectories(project.resolve("a")); Path b = Files.createDirectories(project.resolve("b"));
        Path chosen = Files.writeString(b.resolve("note.md"), "# Note"); Files.writeString(a.resolve("note.md"), "# Other");
        Texteditor editor = createEditor(tempDir.resolve("picker-links-home"), source);
        try {
            assertTrue(awaitUiState(() -> !editor.stagedStartupPending)); var original = onEdt(editor::getCurrentBuffer);
            onEdt(() -> {
                editor.workspaceRoots.add(project); editor.markdownController.linkPicker = (title, rows) -> {
                    assertEquals("Markdown Link Targets", title); assertEquals(2, rows.size());
                    return rows.stream().filter(row -> row.contains("b/note.md")).findFirst().orElseThrow();
                }; editor.writingArea.setCaretPosition(3); editor.showMessage(editor.goToMarkdownLink()); return null;
            });
            assertTrue(awaitCurrentFile(editor, chosen));
            onEdt(() -> { editor.switchToBuffer(editor.buffers.indexOf(original));
                editor.markdownController.linkPicker = (title, rows) -> {
                    assertEquals("Markdown Outgoing Links", title); assertEquals(2, rows.size()); assertTrue(rows.getFirst().contains(":1:1"));
                    editor.writingArea.append(" changed"); return rows.getFirst();
                }; editor.showMessage(editor.commandHandler.execute("markdown outgoing")); return null;
            });
            assertTrue(awaitUiState(() -> editor.writingArea.getText().endsWith(" changed")));
            onEdt(() -> {
                assertSame(original, editor.getCurrentBuffer()); assertEquals("[[note]] [[note]] changed", original.getContent());
                editor.markdownController.linkPicker = (title, rows) -> { editor.markdownLinks().refresh(project); return rows.getFirst(); };
                editor.showMessage(editor.commandHandler.execute("markdown outgoing")); return null;
            });
            assertTrue(awaitUiState(() -> editor.markdownLinks().diagnostics().running() == 0 && editor.markdownLinks().diagnostics().pending() == 0));
            onEdt(() -> { assertSame(original, editor.getCurrentBuffer()); return null; });
        } finally { disposeEditor(editor); }
    }

    @Test
    void markdownBacklinksNavigateUnsavedReferringSourceAndRejectDiscardedPickerRows() throws Exception {
        assumeSwingAvailable(); Path project = Files.createDirectories(tempDir.resolve("backlinks-project"));
        Path source = Files.writeString(project.resolve("source.md"), "preface\n"); Path target = Files.writeString(project.resolve("target.md"), "# Target");
        Texteditor editor = createEditor(tempDir.resolve("backlinks-home"), source);
        try {
            assertTrue(awaitUiState(() -> !editor.stagedStartupPending)); var referring = onEdt(editor::getCurrentBuffer);
            onEdt(() -> { editor.workspaceRoots.add(project); editor.writingArea.append("[[target]]"); return null; });
            onEdt(() -> { editor.openFile(target.toFile()); return null; });
            onEdt(() -> {
                editor.markdownController.linkPicker = (title, rows) -> { assertEquals("Markdown Backlinks", title); assertEquals(1, rows.size()); assertTrue(rows.getFirst().contains("source.md")); return rows.getFirst(); };
                editor.showMessage(editor.commandHandler.execute("markdown backlinks")); return null;
            });
            assertTrue(awaitCurrentFile(editor, source)); assertTrue(awaitUiState(() -> editor.writingArea.getCaretPosition() == 8));
            onEdt(() -> { assertSame(referring, editor.getCurrentBuffer()); assertEquals("preface\n[[target]]", referring.getContent()); assertTrue(referring.isModified());
                editor.writingArea.replaceRange("", 8, referring.getDocument().getLength()); editor.openFile(target.toFile());
                editor.markdownController.linkPicker = (title, rows) -> { throw new AssertionError("Removed reference cannot be displayed"); };
                editor.showMessage(editor.commandHandler.execute("markdown backlinks")); return null;
            });
            assertTrue(awaitUiState(() -> editor.markdownLinks().backlinks(project, target).state() == MarkdownLink.State.COMPLETE));
            assertTrue(onEdt(() -> editor.markdownLinks().backlinks(project, target).edges().isEmpty()));
        } finally { disposeEditor(editor); }
    }

    @Test
    void markdownNavigationRejectsCodeAndUsesExternalPolicyInEveryProfileWithoutMutatingReadOnlySource() throws Exception {
        assumeSwingAvailable(); Path project = Files.createDirectories(tempDir.resolve("link-modes"));
        String text = "`[[note]]`\n```\n[x](note.md)\n```\n[web](https://example.invalid)\n[[missing]]";
        Path source = Files.writeString(project.resolve("source.md"), text); Files.writeString(project.resolve("note.md"), "# Note");
        Texteditor editor = createEditor(tempDir.resolve("link-modes-home"), source); var browsed = new java.util.concurrent.atomic.AtomicInteger();
        try {
            assertTrue(awaitUiState(() -> !editor.stagedStartupPending)); var original = onEdt(editor::getCurrentBuffer);
            onEdt(() -> { editor.workspaceRoots.add(project); editor.markdownController.linkBrowser = uri -> { assertEquals("https", uri.getScheme()); browsed.incrementAndGet(); return "External checked"; }; return null; });
            for (String profile : List.of("plain", "vim", "emacs")) {
                int expected = browsed.get() + 1;
                onEdt(() -> { editor.configManager.set("keymap.profile", profile); editor.sessionConfigController.applyRuntimeConfigFromSettings(); editor.writingArea.setEditable(false);
                    editor.writingArea.setCaretPosition(text.indexOf("web")); editor.showMessage(editor.commandHandler.execute("markdown link follow")); return null;
                });
                assertTrue(awaitUiState(() -> browsed.get() >= expected));
                onEdt(() -> { editor.writingArea.setCaretPosition(3); editor.showMessage(editor.goToMarkdownLink()); return null; });
                assertTrue(awaitUiState(() -> editor.markdownLinks().diagnostics().running() == 0 && editor.markdownLinks().diagnostics().pending() == 0));
                onEdt(() -> { assertSame(original, editor.getCurrentBuffer()); assertEquals(text, original.getContent()); assertFalse(original.isModified()); assertFalse(original.getUndoManager().canUndo()); return null; });
            }
        } finally { disposeEditor(editor); }
    }

    @Test
    void headlessMarkdownLinkServiceUsesExistingEditSaveDiscardAndSplitLifecycle() throws Exception {
        assumeSwingAvailable();
        Path project = Files.createDirectories(tempDir.resolve("link-project"));
        Path a = Files.writeString(project.resolve("a.md"), "# A\n[[b]]\n");
        Path b = Files.writeString(project.resolve("b.md"), "# B\n");
        Path c = Files.writeString(project.resolve("c.md"), "# C\n");
        Texteditor editor = createEditor(tempDir.resolve("link-home"), a);
        try {
            assertTrue(awaitUiState(() -> !editor.stagedStartupPending));
            FileBuffer buffer = onEdt(editor::getCurrentBuffer);
            var snapshot = onEdt(buffer::textSnapshot); long revision = onEdt(buffer::documentRevision);
            var beforeWindows = onEdt(() -> editor.getOwnedWindows().length);
            onEdt(() -> {
                var field = Texteditor.class.getDeclaredField("markdownLinks"); field.setAccessible(true);
                assertEquals(null, field.get(editor));
                editor.workspaceRoots.add(project); editor.markdownLinks().request(project); return null;
            });
            assertTrue(awaitUiState(() -> editor.markdownLinks().backlinks(project, b).state() == MarkdownLink.State.COMPLETE));
            onEdt(() -> {
                assertEquals(1, editor.markdownLinks().backlinks(project, b).edges().size());
                assertSame(snapshot, buffer.textSnapshot()); assertEquals(revision, buffer.documentRevision()); assertFalse(buffer.isModified());
                assertFalse(buffer.getUndoManager().canUndo()); assertEquals(beforeWindows, editor.getOwnedWindows().length);
                editor.splitWindow(true); editor.writingArea.replaceRange("c", 6, 7); return null;
            });
            assertTrue(awaitUiState(() -> editor.markdownLinks().backlinks(project, c).edges().size() == 1));
            onEdt(() -> {
                assertTrue(editor.markdownLinks().backlinks(project, b).edges().isEmpty());
                for (EditorPane pane : editor.editorPanes) assertSame(buffer.getDocument(), pane.getTextArea().getDocument());
                return null;
            });
            var save = onEdt(() -> buffer.captureSaveSnapshot(null)); buffer.writeSaveSnapshot(save);
            onEdt(() -> { buffer.completeSaveSnapshot(save); editor.notifyBufferSaved(buffer); return null; });
            assertTrue(awaitUiState(() -> editor.markdownLinks().backlinks(project, c).state() == MarkdownLink.State.COMPLETE
                && editor.markdownLinks().backlinks(project, c).edges().getFirst().occurrence().source().document().isEmpty()));
            onEdt(() -> { editor.writingArea.replaceRange("b", 6, 7); return null; });
            assertTrue(awaitUiState(() -> editor.markdownLinks().backlinks(project, b).edges().size() == 1));
            onEdt(() -> {
                editor.buffers.remove(buffer); editor.markdownSourceClosed(buffer); return null;
            });
            assertTrue(awaitUiState(() -> editor.markdownLinks().backlinks(project, c).edges().size() == 1));
            assertEquals("# A\n[[c]]\n", Files.readString(a));
            onEdt(() -> {
                assertTrue(editor.markdownLinks().backlinks(project, b).edges().isEmpty());
                editor.workspaceRoots.remove(project); editor.markdownLinkRootsChanged(); assertEquals(0, editor.markdownLinks().diagnostics().partitions()); return null;
            });
        } finally { disposeEditor(editor); }
    }

    @Test
    void semanticMarkdownOutlineNavigationSplitsAndStaleSelectionPreserveSource() throws Exception {
        assumeSwingAvailable();
        Path home = Files.createDirectories(tempDir.resolve("home-semantic-headings"));
        String text = "preface\n# real\n```md\n# fake\n```\nSecond *formatted* 😀\n---\n#\n# duplicate\n# duplicate";
        Path file = Files.writeString(tempDir.resolve("semantic-headings.md"), text);
        Texteditor editor = createEditor(home, file);
        var events = new java.util.concurrent.atomic.AtomicInteger();
        try {
            assertTrue(awaitUiState(() -> !editor.stagedStartupPending));
            FileBuffer buffer = onEdt(editor::getCurrentBuffer);
            var snapshot = onEdt(buffer::textSnapshot);
            long revision = onEdt(buffer::documentRevision);
            onEdt(() -> {
                buffer.getDocument().addDocumentListener(new javax.swing.event.DocumentListener() {
                    public void insertUpdate(javax.swing.event.DocumentEvent e) { events.incrementAndGet(); }
                    public void removeUpdate(javax.swing.event.DocumentEvent e) { events.incrementAndGet(); }
                    public void changedUpdate(javax.swing.event.DocumentEvent e) { events.incrementAndGet(); }
                });
                editor.writingArea.setCaretPosition(0); editor.navigateHeading(true); return null;
            });
            assertTrue(awaitUiState(() -> editor.writingArea.getCaretPosition() == text.indexOf("# real")));
            onEdt(() -> { editor.navigateHeadingAtLevel(true, 2); return null; });
            assertTrue(awaitUiState(() -> editor.writingArea.getCaretPosition() == text.indexOf("Second")));
            onEdt(() -> { editor.showOutline(); return null; });
            MarkdownOutlineDialog dialog = onEdt(() -> Arrays.stream(editor.getOwnedWindows())
                .filter(w -> w instanceof MarkdownOutlineDialog && w.isDisplayable()).map(w -> (MarkdownOutlineDialog) w).findFirst().orElseThrow());
            assertTrue(awaitUiState(() -> descendants(dialog, javax.swing.JList.class).getFirst().getModel().getSize() == 5));
            onEdt(() -> {
                var list = descendants(dialog, javax.swing.JList.class).getFirst();
                for (int i = 0; i < list.getModel().getSize(); i++) assertFalse(list.getModel().getElementAt(i).toString().contains("fake"));
                list.setSelectedIndex(4);
                descendants(dialog, javax.swing.JButton.class).stream().filter(b -> b.getText().equals("Go to heading")).findFirst().orElseThrow().doClick(0);
                assertEquals(text.lastIndexOf("# duplicate"), editor.writingArea.getCaretPosition());
                assertEquals(text, editor.writingArea.getText()); assertSame(snapshot, buffer.textSnapshot());
                assertEquals(revision, buffer.documentRevision()); assertFalse(buffer.isModified()); assertFalse(buffer.getUndoManager().canUndo());
                assertEquals(0, events.get());
                buffer.getDocument().insertString(0, "\n", null);
                int afterOrdinaryEdit = editor.writingArea.getCaretPosition();
                descendants(dialog, javax.swing.JButton.class).stream().filter(b -> b.getText().equals("Go to heading")).findFirst().orElseThrow().doClick(0);
                assertEquals(afterOrdinaryEdit, editor.writingArea.getCaretPosition());
                return null;
            });
            assertTrue(awaitUiState(() -> {
                var list = descendants(dialog, javax.swing.JList.class).getFirst();
                return list.getModel().getSize() == 5 && list.getModel().getElementAt(0).toString().endsWith("·   3");
            }));
            onEdt(() -> {
                EditorPane first = editor.getActivePane(); int originalCaret = first.getTextArea().getCaretPosition();
                long submitted = editor.markdownController.headingAnalysis().diagnostics().submitted();
                editor.splitWindow(true);
                assertSame(first.getTextArea().getDocument(), editor.writingArea.getDocument());
                editor.writingArea.setCaretPosition(0); editor.navigateHeading(true);
                assertEquals(submitted, editor.markdownController.headingAnalysis().diagnostics().submitted());
                assertEquals(originalCaret, first.getTextArea().getCaretPosition());
                return null;
            });
            assertTrue(awaitUiState(() -> editor.writingArea.getCaretPosition() == text.indexOf("# real") + 1));
            onEdt(() -> {
                assertEquals("\n" + text, buffer.textSnapshot().text()); assertTrue(buffer.isModified());
                assertEquals(text, buffer.getSavedContent()); assertEquals(1, events.get());
                dialog.dispose(); return null;
            });
            assertEquals(text, Files.readString(file));
        } finally { disposeEditor(editor); }
    }

    @Test
    void pendingSemanticNavigationCannotMoveAnotherBufferOrInstallAnEditedRevision() throws Exception {
        assumeSwingAvailable();
        Path home = Files.createDirectories(tempDir.resolve("home-semantic-switch"));
        Path first = Files.writeString(tempDir.resolve("semantic-a.md"), "prose\n# A\n## A2");
        Path second = Files.writeString(tempDir.resolve("semantic-b.md"), "other\n# B");
        Texteditor editor = createEditor(home, first, second);
        var started = new java.util.concurrent.CountDownLatch(1);
        var release = new java.util.concurrent.CountDownLatch(1);
        try {
            assertTrue(awaitUiState(() -> !editor.stagedStartupPending));
            var service = new MarkdownAnalysisService(editor.asyncJobService, (source, cancelled) -> {
                started.countDown();
                try { assertTrue(release.await(15, TimeUnit.SECONDS)); } catch (InterruptedException e) { throw new RuntimeException(e); }
                return MarkdownHeadingAnalysis.analyze(source, cancelled);
            });
            FileBuffer a = onEdt(() -> editor.buffers.stream().filter(b -> b.getFilePath().equals(first.toString())).findFirst().orElseThrow());
            FileBuffer b = onEdt(editor::getCurrentBuffer);
            onEdt(() -> {
                var field = MarkdownController.class.getDeclaredField("headingAnalysis"); field.setAccessible(true); field.set(editor.markdownController, service);
                editor.loadBufferIntoEditor(a); editor.writingArea.setCaretPosition(0); editor.navigateHeading(true); return null;
            });
            assertTrue(started.await(15, TimeUnit.SECONDS));
            onEdt(() -> { a.getDocument().insertString(0, "edit\n", null); editor.loadBufferIntoEditor(b); editor.writingArea.setCaretPosition(0); return null; });
            release.countDown();
            assertTrue(awaitUiState(() -> service.diagnostics().running() == 0));
            onEdt(() -> {
                assertSame(b, editor.getCurrentBuffer()); assertEquals(0, editor.writingArea.getCaretPosition());
                assertEquals("other\n# B", editor.writingArea.getText()); assertFalse(b.isModified());
                assertTrue(service.diagnostics().staleRejected() > 0); return null;
            });
            onEdt(() -> {
                editor.loadBufferIntoEditor(a); editor.writingArea.setCaretPosition(0);
                editor.navigateHeading(true); editor.navigateHeading(true); return null;
            });
            assertTrue(awaitUiState(() -> editor.writingArea.getCaretPosition() == a.textSnapshot().text().indexOf("## A2")));
        } finally { release.countDown(); disposeEditor(editor); }
    }

    @Test
    void semanticSymbolPickerKeepsDuplicateTargetsRejectsStaleSelectionAndPreservesJavaSymbols() throws Exception {
        assumeSwingAvailable();
        Path home = Files.createDirectories(tempDir.resolve("home-semantic-symbols"));
        String text = "# duplicate\n```\n# fake\n```\n# duplicate\n";
        Path markdown = Files.writeString(tempDir.resolve("symbols.md"), text);
        Path java = Files.writeString(tempDir.resolve("Example.java"), "public class Example {\n    public void run() {}\n}\n");
        Texteditor editor = createEditor(home, markdown);
        var available = new java.util.concurrent.CountDownLatch(1);
        var retained = new java.util.concurrent.atomic.AtomicReference<MarkdownHeadingAnalysis>();
        try {
            assertTrue(awaitUiState(() -> !editor.stagedStartupPending));
            FileBuffer buffer = onEdt(editor::getCurrentBuffer);
            var snapshot = onEdt(buffer::textSnapshot);
            long revision = onEdt(buffer::documentRevision);
            onEdt(() -> {
                editor.requestMarkdownHeadings(new Object(), buffer, () -> true, result -> {
                    retained.set(result); available.countDown();
                }); return null;
            });
            assertTrue(available.await(15, TimeUnit.SECONDS));
            onEdt(() -> {
                String message = drivePicker(editor, "Symbols", dialog -> {
                    var list = descendants(dialog, javax.swing.JList.class).getFirst();
                    assertEquals(2, list.getModel().getSize());
                    list.setSelectedIndex(1);
                    descendants(dialog, javax.swing.JTextField.class).getFirst().postActionEvent();
                }, () -> editor.paletteController.showHeuristicSymbols("duplicate"));
                assertEquals("Line 5", message);
                assertEquals(text.lastIndexOf("# duplicate"), editor.writingArea.getCaretPosition());
                assertSame(snapshot, buffer.textSnapshot()); assertEquals(revision, buffer.documentRevision());
                assertFalse(buffer.isModified()); assertFalse(buffer.getUndoManager().canUndo());
                assertEquals(text, buffer.getSavedContent());
                String stale = drivePicker(editor, "Symbols", dialog -> {
                    try { buffer.getDocument().insertString(0, "edit\n", null); }
                    catch (javax.swing.text.BadLocationException error) { throw new AssertionError(error); }
                    int caret = editor.writingArea.getCaretPosition();
                    descendants(dialog, javax.swing.JTextField.class).getFirst().postActionEvent();
                    assertEquals(caret, editor.writingArea.getCaretPosition());
                }, () -> editor.paletteController.showHeuristicSymbols("duplicate"));
                assertEquals("Markdown headings changed; request again", stale);
                long submitted = editor.markdownController.headingAnalysis().diagnostics().submitted();
                FileBuffer javaBuffer = new FileBuffer(java.toFile(), editor.configManager);
                editor.buffers.add(javaBuffer); editor.loadBufferIntoEditor(javaBuffer);
                String ordinary = drivePicker(editor, "Symbols", dialog -> {
                    descendants(dialog, javax.swing.JTextField.class).getFirst().postActionEvent();
                }, () -> editor.paletteController.showHeuristicSymbols("Example"));
                assertEquals("Line 1", ordinary);
                assertEquals(submitted, editor.markdownController.headingAnalysis().diagnostics().submitted());
                assertFalse(javaBuffer.isModified());
                return null;
            });
            assertNotNull(retained.get());
            assertEquals(text, Files.readString(markdown));
        } finally { disposeEditor(editor); }
    }

    @Test
    void pendingHeadingWorkRespectsManualCaretMovementAndBufferClosure() throws Exception {
        assumeSwingAvailable();
        Path home = Files.createDirectories(tempDir.resolve("home-semantic-close"));
        Path first = Files.writeString(tempDir.resolve("close-a.md"), "prose\n# A\n");
        Path second = Files.writeString(tempDir.resolve("close-b.md"), "other\n# B\n");
        Texteditor editor = createEditor(home, first, second);
        var started = new java.util.concurrent.CountDownLatch[]{new java.util.concurrent.CountDownLatch(1), new java.util.concurrent.CountDownLatch(1)};
        var release = new java.util.concurrent.CountDownLatch[]{new java.util.concurrent.CountDownLatch(1), new java.util.concurrent.CountDownLatch(1)};
        var calls = new java.util.concurrent.atomic.AtomicInteger();
        try {
            assertTrue(awaitUiState(() -> !editor.stagedStartupPending));
            var service = new MarkdownAnalysisService(editor.asyncJobService, (source, cancelled) -> {
                int index = calls.getAndIncrement(); started[index].countDown();
                try { assertTrue(release[index].await(15, TimeUnit.SECONDS)); }
                catch (InterruptedException ignored) { }
                return MarkdownHeadingAnalysis.analyze(source, cancelled);
            });
            FileBuffer a = onEdt(() -> editor.buffers.stream().filter(b -> b.getFile().equals(first.toFile())).findFirst().orElseThrow());
            FileBuffer b = onEdt(editor::getCurrentBuffer);
            onEdt(() -> {
                var field = MarkdownController.class.getDeclaredField("headingAnalysis"); field.setAccessible(true);
                assertEquals(null, field.get(editor.markdownController)); field.set(editor.markdownController, service);
                editor.loadBufferIntoEditor(a); editor.writingArea.setCaretPosition(0); editor.navigateHeading(true); return null;
            });
            assertTrue(started[0].await(15, TimeUnit.SECONDS));
            onEdt(() -> { editor.writingArea.setCaretPosition(3); return null; });
            release[0].countDown();
            assertTrue(awaitUiState(() -> service.diagnostics().running() == 0));
            onEdt(() -> {
                assertEquals(3, editor.writingArea.getCaretPosition());
                a.getDocument().insertString(0, "edit\n", null);
                assertEquals(1, service.diagnostics().submitted(), "ordinary edits must not submit semantic work");
                editor.writingArea.setCaretPosition(0); editor.navigateHeading(true); editor.showOutline(); return null;
            });
            assertTrue(started[1].await(15, TimeUnit.SECONDS));
            onEdt(() -> {
                editor.closeActiveBufferWithoutExiting(true);
                assertFalse(editor.buffers.contains(a)); assertSame(b, editor.getCurrentBuffer());
                assertTrue(Arrays.stream(editor.getOwnedWindows()).noneMatch(w -> w instanceof MarkdownOutlineDialog && w.isDisplayable()));
                editor.writingArea.setCaretPosition(2); return null;
            });
            release[1].countDown();
            assertTrue(awaitUiState(() -> service.diagnostics().running() == 0));
            onEdt(() -> {
                assertEquals(2, editor.writingArea.getCaretPosition()); assertEquals("other\n# B\n", b.getContent());
                assertFalse(b.isModified()); assertFalse(b.getUndoManager().canUndo());
                assertEquals(0, service.diagnostics().subscribers()); return null;
            });
            assertEquals("prose\n# A\n", Files.readString(first));
        } finally { for (var gate : release) gate.countDown(); disposeEditor(editor); }
    }

    @Test
    void semanticHeadingEditsPreserveKeymapsSplitsRecoveryAndCurrentHighlighting() throws Exception {
        assumeSwingAvailable();
        String text = "## *café* 😀\nbody\n```md\n## example\n```\n";
        Path file = Files.writeString(tempDir.resolve("semantic-edits.md"), text);
        Texteditor editor = createEditor(Files.createDirectories(tempDir.resolve("home-semantic-edits")), file);
        try {
            assertTrue(awaitUiState(() -> !editor.stagedStartupPending));
            for (String profile : List.of("plain", "vim", "emacs")) {
                onEdt(() -> {
                    editor.configManager.set("keymap.profile", profile);
                    editor.getCurrentBuffer().setContent(text, false); editor.setMode(profile.equals("vim") ? EditorMode.NORMAL : EditorMode.INSERT);
                    editor.writingArea.select(3, 7);
                    var history = (BoundedUndoManager) editor.getCurrentBuffer().getUndoManager(); history.discardAllEdits();
                    assertEquals("Loading heading edit", editor.markdownHeadingShift(true));
                    assertEquals(text, editor.writingArea.getText()); return null;
                });
                assertTrue(awaitUiState(() -> editor.writingArea.getText().equals("#" + text)));
                assertTrue(awaitUiState(() -> markdownSyntaxSettled(editor)));
                onEdt(() -> {
                    assertCurrentMarkdownSyntax(editor);
                    assertEquals(8, editor.writingArea.getCaret().getDot()); assertEquals(4, editor.writingArea.getCaret().getMark());
                    var history = (BoundedUndoManager) editor.getCurrentBuffer().getUndoManager(); assertEquals(1, history.retainedEditCount());
                    history.undo(); assertEquals(text, editor.writingArea.getText()); history.redo(); assertEquals("#" + text, editor.writingArea.getText());
                    assertEquals(text, editor.getCurrentBuffer().getSavedContent()); return null;
                });
            }
            var splitAnalysis = new java.util.concurrent.atomic.AtomicReference<MarkdownHeadingAnalysis>();
            onEdt(() -> {
                editor.persistRecoverySnapshots();
                EditorPane first = editor.getActivePane(); editor.splitWindow(true);
                assertSame(first.getTextArea().getDocument(), editor.writingArea.getDocument());
                editor.requestMarkdownHeadings(new Object(), editor.getCurrentBuffer(), () -> true, splitAnalysis::set); return null;
            });
            assertTrue(awaitUiState(() -> splitAnalysis.get() != null));
            EditorPane splitTarget = onEdt(editor::getActivePane);
            onEdt(() -> {
                editor.editorPanes.getFirst().getTextArea().select(17, 19);
                editor.activateEditorPane(splitTarget); editor.writingArea.setCaretPosition(0);
                editor.markdownHeadingShift(false); assertSame(splitTarget, editor.getActivePane()); return null;
            });
            assertTrue(awaitUiState(() -> editor.writingArea.getText().equals(text)));
            onEdt(() -> {
                assertEquals(16, editor.editorPanes.getFirst().getTextArea().getSelectionStart());
                assertEquals(18, editor.editorPanes.getFirst().getTextArea().getSelectionEnd());
                editor.getCurrentBuffer().getUndoManager().undo(); assertEquals("#" + text, editor.writingArea.getText());
                editor.persistRecoverySnapshots(); return null;
            });
            assertTrue(awaitUiState(() -> {
                try { var journal = RecoveryJournal.read(editor.recoveryStoreDir.toPath());
                    return journal != null && journal.entries().stream().anyMatch(e -> e.content().equals("#" + text));
                } catch (java.io.IOException error) { return false; }
            }));
            var previewAnalysis = new java.util.concurrent.atomic.AtomicReference<MarkdownHeadingAnalysis>();
            onEdt(() -> {
                assertEquals("Markdown preview opened", editor.openMarkdownPreview());
                editor.requestMarkdownHeadings(new Object(), editor.getCurrentBuffer(), () -> true, previewAnalysis::set); return null;
            });
            assertTrue(awaitUiState(() -> previewAnalysis.get() != null));
            onEdt(() -> { editor.writingArea.setCaretPosition(0); editor.markdownHeadingShift(true); return null; });
            assertTrue(awaitUiState(() -> editor.writingArea.getText().equals("##" + text)));
            assertTrue(awaitUiState(() -> editor.editorPanes.stream().filter(EditorPane::isMarkdownPreview)
                .map(p -> (MarkdownPreviewPane) p.getComponent()).anyMatch(p -> p.getHtml().contains("<h4"))));
            assertEquals(text, Files.readString(file));
        } finally { disposeEditor(editor); }
    }

    @Test
    void semanticMarkerEditPreservesAnInactiveSplitViewportAtAnIndependentCaret() throws Exception {
        assumeSwingAvailable();
        String text = "## first\n" + "body\n".repeat(1000);
        Path file = Files.writeString(tempDir.resolve("edit-viewports.md"), text);
        Texteditor editor = createEditor(Files.createDirectories(tempDir.resolve("home-edit-viewports")), file);
        var ready = new java.util.concurrent.atomic.AtomicReference<MarkdownHeadingAnalysis>();
        try {
            assertTrue(awaitUiState(() -> !editor.stagedStartupPending));
            onEdt(() -> {
                editor.setMode(EditorMode.INSERT); editor.splitWindow(true); editor.validate();
                editor.requestMarkdownHeadings(new Object(), editor.getCurrentBuffer(), () -> true, ready::set); return null;
            });
            assertTrue(awaitUiState(() -> ready.get() != null));
            onEdt(() -> {
                EditorPane first = editor.editorPanes.getFirst(), second = editor.editorPanes.getLast();
                first.getTextArea().setCaretPosition(0); editor.activateEditorPane(second); second.getTextArea().setCaretPosition(0);
                first.surface().scrollTo(new EditorSurface.Viewport(0, 800));
                var viewport = first.surface().viewport(); assertTrue(viewport.y() > 0);
                assertEquals("Demoted heading", editor.markdownHeadingShift(true));
                assertSame(second, editor.getActivePane()); assertEquals(viewport, first.surface().viewport());
                assertSame(first.getTextArea().getDocument(), second.getTextArea().getDocument());
                assertEquals("#" + text, editor.getCurrentBuffer().getContent());
                editor.getCurrentBuffer().getUndoManager().undo(); assertEquals(text, editor.getCurrentBuffer().getContent()); return null;
            });
            assertEquals(text, Files.readString(file));
        } finally { disposeEditor(editor); }
    }

    @Test
    void pendingSemanticEditsCannotSurviveTypingCaretBufferModeOrLifetimeChanges() throws Exception {
        assumeSwingAvailable();
        for (String action : List.of("typing", "caret-return", "buffer-return", "mode-key", "focus", "close", "split", "undo-redo",
                "subtree-typing", "subtree-caret-return", "subtree-buffer-return", "subtree-mode-key", "subtree-focus", "subtree-close", "subtree-split", "subtree-undo-redo")) {
            Path home = Files.createDirectories(tempDir.resolve("home-edit-gate-" + action));
            Path first = Files.writeString(tempDir.resolve("edit-gate-a-" + action + ".md"), "## A\nbody");
            Path second = Files.writeString(tempDir.resolve("edit-gate-b-" + action + ".md"), "## B\nother");
            Texteditor editor = createEditor(home, first, second);
            var started = new java.util.concurrent.CountDownLatch(1); var release = new java.util.concurrent.CountDownLatch(1);
            try {
                assertTrue(awaitUiState(() -> !editor.stagedStartupPending));
                var service = new MarkdownAnalysisService(editor.asyncJobService, (source, stop) -> {
                    started.countDown();
                    try { assertTrue(release.await(15, TimeUnit.SECONDS)); } catch (InterruptedException ignored) { }
                    return MarkdownHeadingAnalysis.analyze(source, stop);
                });
                FileBuffer b = onEdt(editor::getCurrentBuffer);
                FileBuffer a = onEdt(() -> editor.buffers.stream().filter(f -> f.getFile().equals(first.toFile())).findFirst().orElseThrow());
                onEdt(() -> {
                    var field = MarkdownController.class.getDeclaredField("headingAnalysis"); field.setAccessible(true); field.set(editor.markdownController, service);
                    editor.loadBufferIntoEditor(a); editor.setMode(EditorMode.INSERT); editor.writingArea.setCaretPosition(0);
                    assertEquals("Loading heading edit", action.startsWith("subtree-") ? editor.markdownSubtreeShift(true) : editor.markdownHeadingShift(true));
                    assertEquals("Heading edit pending; request again", editor.markdownHeadingShift(false)); return null;
                });
                assertTrue(started.await(15, TimeUnit.SECONDS));
                onEdt(() -> {
                    switch (action.replace("subtree-", "")) {
                        case "typing" -> a.getDocument().insertString(a.getDocument().getLength(), "typed", null);
                        case "caret-return" -> { editor.writingArea.setCaretPosition(3); editor.writingArea.setCaretPosition(0); }
                        case "buffer-return" -> { editor.loadBufferIntoEditor(b); editor.loadBufferIntoEditor(a); }
                        case "mode-key" -> { editor.setMode(EditorMode.NORMAL); editor.keyPressed(new KeyEvent(editor.writingArea, KeyEvent.KEY_PRESSED, 0, 0, KeyEvent.VK_ESCAPE, (char) 27)); editor.setMode(EditorMode.INSERT); }
                        case "focus" -> {
                            var event = new FocusEvent(editor.writingArea, FocusEvent.FOCUS_LOST);
                            for (var listener : editor.writingArea.getFocusListeners()) listener.focusLost(event);
                        }
                        case "close" -> editor.closeActiveBufferWithoutExiting(true);
                        case "split" -> editor.splitWindow(true);
                        case "undo-redo" -> { a.getDocument().insertString(a.getDocument().getLength(), "x", null); a.getUndoManager().undo(); a.getUndoManager().redo(); }
                    }
                    return null;
                });
                record State(String text, VersionedTextSnapshot snapshot, long revision, boolean dirty, int history, long bytes) { }
                var expected = onEdt(() -> new State(a.getContent(), a.textSnapshot(), a.documentRevision(), a.isModified(),
                    ((BoundedUndoManager) a.getUndoManager()).retainedEditCount(), ((BoundedUndoManager) a.getUndoManager()).retainedBytes()));
                release.countDown(); assertTrue(awaitUiState(() -> service.diagnostics().running() == 0));
                onEdt(() -> {
                    assertEquals(expected.text(), a.getContent(), action); assertSame(expected.snapshot(), a.textSnapshot());
                    assertEquals(expected.revision(), a.documentRevision()); assertEquals(expected.dirty(), a.isModified());
                    assertEquals(expected.history(), ((BoundedUndoManager) a.getUndoManager()).retainedEditCount());
                    assertEquals(expected.bytes(), ((BoundedUndoManager) a.getUndoManager()).retainedBytes());
                    assertEquals("## B\nother", b.getContent()); assertFalse(b.isModified());
                    assertEquals(0, service.diagnostics().subscribers());
                    var pending = MarkdownController.class.getDeclaredField("pendingHeadingEdit"); pending.setAccessible(true);
                    assertEquals(null, pending.get(editor.markdownController), "Obsolete edit must release its source/selection stamp"); return null;
                });
            } finally { release.countDown(); disposeEditor(editor); }
        }
    }

    @Test
    void semanticHeadingRejectionDoesNotFallBackToFencedEscapedOrSetextMarkers() throws Exception {
        assumeSwingAvailable();
        Path file = Files.writeString(tempDir.resolve("edit-rejected.md"), "## first");
        Texteditor editor = createEditor(Files.createDirectories(tempDir.resolve("home-edit-rejected")), file);
        try {
            assertTrue(awaitUiState(() -> !editor.stagedStartupPending));
            for (String text : List.of("```\n## fake\n```", "\\## escaped", "Title\n---", "## first\n" + "body\n".repeat(210000))) {
                onEdt(() -> {
                    var buffer = editor.getCurrentBuffer(); buffer.setContent(text, false); editor.setMode(EditorMode.INSERT);
                    int dot = Math.max(0, text.indexOf("##")); editor.writingArea.setCaretPosition(dot);
                    var history = (BoundedUndoManager) buffer.getUndoManager(); history.discardAllEdits();
                    var snapshot = buffer.textSnapshot(); long revision = buffer.documentRevision();
                    editor.markdownHeadingShift(true); awaitPendingMarkdownHeadingEdit(editor);
                    assertEquals(text, buffer.getContent()); assertSame(snapshot, buffer.textSnapshot()); assertEquals(revision, buffer.documentRevision());
                    assertFalse(buffer.isModified()); assertFalse(history.canUndo()); assertEquals(text, buffer.getSavedContent());
                    return null;
                });
            }
        } finally { disposeEditor(editor); }
    }

    @Test
    void workbenchControlsKeepTheirStyleAndKeyboardBehaviorAcrossThemeAndZoomChanges() throws Exception {
        assumeSwingAvailable();
        Path home = Files.createDirectories(tempDir.resolve("home-workbench-style"));
        Path file = Files.writeString(tempDir.resolve("workbench-style.txt"), "text\n");
        Texteditor editor = createEditor(home, file);
        javax.swing.JDialog dialog = null;
        try {
            assertTrue(awaitUiState(() -> !editor.stagedStartupPending));
            dialog = onEdt(() -> {
                var window = new javax.swing.JDialog(editor, "Workbench controls", false);
                var content = new javax.swing.JPanel(new java.awt.GridLayout(0, 2, 8, 8));
                content.add(new javax.swing.JButton("Action"));
                content.add(new javax.swing.JCheckBox("Option"));
                content.add(new javax.swing.JTextField("Editable text"));
                content.add(new javax.swing.JComboBox<>(new String[]{"One", "Two"}));
                content.add(new javax.swing.JScrollPane(new javax.swing.JTable(new Object[][]{{"Row"}}, new Object[]{"Column"})));
                content.add(new javax.swing.JScrollPane(new javax.swing.JList<>(new String[]{"First", "Second"})));
                var tabs = new javax.swing.JTabbedPane(); tabs.addTab("Details", new javax.swing.JPanel()); content.add(tabs);
                content.add(new javax.swing.JSpinner());
                var prose = new JTextArea("Command description");
                prose.setFont(editor.resolveUiFont());
                content.add(prose);
                window.add(content);
                editor.editorUiController.prepareDialog(window, 720, 420);
                window.pack(); window.setVisible(true);
                return window;
            });
            var shown = dialog;
            onEdt(() -> {
                var button = descendants(shown, javax.swing.JButton.class).stream().filter(b -> "Action".equals(b.getText())).findFirst().orElseThrow();
                assertTrue(button.getUI() instanceof WorkbenchStyle.ButtonUI);
                for (var input : descendants(shown, javax.swing.text.JTextComponent.class)) {
                    assertTrue(input.getCaret() instanceof BlockCaret, input.getClass().getName());
                }
                assertTrue(button.getPreferredSize().height >= 40);
                var box = descendants(shown, javax.swing.JCheckBox.class).getFirst();
                Object press = box.getInputMap().get(javax.swing.KeyStroke.getKeyStroke("pressed SPACE"));
                Object release = box.getInputMap().get(javax.swing.KeyStroke.getKeyStroke("released SPACE"));
                assertNotNull(press); assertNotNull(release);
                box.getActionMap().get(press).actionPerformed(new ActionEvent(box, 0, "press"));
                box.getActionMap().get(release).actionPerformed(new ActionEvent(box, 0, "release"));
                assertTrue(box.isSelected());
                var combo = descendants(shown, javax.swing.JComboBox.class).getFirst();
                assertTrue(combo.getUI() instanceof WorkbenchStyle.ComboUI);
                combo.setSelectedIndex(1);
                assertEquals("Two", combo.getSelectedItem());
                return null;
            });
            onEdt(() -> {
                editor.configManager.set("theme", "shed-light");
                editor.configManager.set("ui.zoom", "1.5");
                editor.sessionConfigController.applyRuntimeConfigFromSettings();
                // A second application must not double-scale or restore native painting delegates.
                editor.sessionConfigController.applyRuntimeConfigFromSettings();
                var button = descendants(shown, javax.swing.JButton.class).stream().filter(b -> "Action".equals(b.getText())).findFirst().orElseThrow();
                assertTrue(button.getUI() instanceof WorkbenchStyle.ButtonUI);
                for (var input : descendants(shown, javax.swing.text.JTextComponent.class)) {
                    assertTrue(input.getCaret() instanceof BlockCaret, input.getClass().getName());
                }
                assertEquals(editor.resolveUiFont().getSize(), button.getFont().getSize());
                assertTrue(button.getPreferredSize().height >= 60);
                var field = descendants(shown, javax.swing.JTextField.class).getFirst();
                assertEquals(editor.configManager.getFloatingSurfaceColor(), field.getBackground());
                assertEquals("Editable text", field.getText());
                var prose = descendants(shown, JTextArea.class).getFirst();
                assertEquals(editor.resolveUiFont().getFamily(), prose.getFont().getFamily());
                assertEquals(editor.resolveUiFont().getSize(), prose.getFont().getSize());
                var table = descendants(shown, javax.swing.JTable.class).getFirst();
                assertFalse(table.getShowHorizontalLines());
                assertTrue(table.getRowHeight() >= 60);
                assertEquals(editor.configManager.getSelectionColor(), table.getSelectionBackground());
                var tabs = descendants(shown, javax.swing.JTabbedPane.class).getFirst();
                assertTrue(tabs.getUI() instanceof WorkbenchStyle.TabsUI);
                var later = new javax.swing.JDialog(editor, "Opened at 150%", false);
                try {
                    var content = new javax.swing.JPanel();
                    var label = new javax.swing.JLabel("Label");
                    var input = new javax.swing.JTextField("Query", 12);
                    var action = new javax.swing.JButton("Apply");
                    content.add(label); content.add(input); content.add(action);
                    later.add(content);
                    editor.editorUiController.prepareDialog(later, 500, 200);
                    assertEquals(editor.resolveUiFont().getSize(), label.getFont().getSize());
                    assertEquals(editor.resolveUiFont().getSize(), input.getFont().getSize());
                    assertEquals(editor.resolveUiFont().getSize(), action.getFont().getSize());
                    editor.editorUiController.applyUiFont(later);
                    assertEquals(editor.resolveUiFont().getSize(), label.getFont().getSize());
                } finally { later.dispose(); }
                for (var scroll : descendants(shown, javax.swing.JScrollBar.class)) {
                    for (var arrow : descendants(scroll, javax.swing.JButton.class)) assertFalse(arrow.isFocusable());
                }
                editor.configManager.set("ui.font.size", "18");
                editor.sessionConfigController.applyRuntimeConfigFromSettings();
                var custom = new javax.swing.JDialog(editor, "Custom typography", false);
                try {
                    var content = new javax.swing.JPanel();
                    var body = new javax.swing.JLabel("Body");
                    var heading = new javax.swing.JLabel("Heading");
                    heading.setFont(body.getFont().deriveFont(java.awt.Font.BOLD, body.getFont().getSize2D() * 2));
                    content.add(body); content.add(heading); custom.add(content);
                    editor.editorUiController.prepareDialog(custom, 500, 200);
                    assertEquals(editor.resolveUiFont().getSize(), body.getFont().getSize());
                    assertEquals(body.getFont().getSize() * 2, heading.getFont().getSize());
                    editor.configManager.set("ui.font.size", "0");
                    editor.editorUiController.applyUiFont(custom);
                    assertEquals(editor.resolveUiFont().getSize(), body.getFont().getSize());
                    assertEquals(body.getFont().getSize() * 2, heading.getFont().getSize());
                } finally { custom.dispose(); }
                return null;
            });
        } finally {
            var current = dialog;
            if (current != null) onEdt(() -> { current.dispose(); return null; });
            disposeEditor(editor);
        }
    }

    @Test
    void selectingToolTabsGrowsTheDefaultDockButPreservesManualSizing() throws Exception {
        assumeSwingAvailable();
        Path home = Files.createDirectories(tempDir.resolve("home-tool-tab-sizing"));
        Path file = Files.writeString(tempDir.resolve("tool-tab-sizing.txt"), "text\n");
        Texteditor editor = createEditor(home, file);
        try {
            assertTrue(awaitUiState(() -> !editor.stagedStartupPending));
            onEdt(() -> {
                editor.setSize(1100, 950);
                editor.validate();
                editor.showToolWindow(ToolWindowHost.Tab.SEARCH);
                editor.validate();
                var tabs = descendants(editor.toolWindowHost, javax.swing.JTabbedPane.class).stream()
                    .filter(pane -> pane.indexOfTab("Search") >= 0 && pane.indexOfTab("Replace") >= 0)
                    .findFirst().orElseThrow();
                int searchDivider = editor.editorToolSplit.getDividerLocation();
                // Selecting the tab model follows the same listener as clicking its header.
                tabs.setSelectedIndex(tabs.indexOfTab("Debug"));
                editor.validate();
                int debugDivider = editor.editorToolSplit.getDividerLocation();
                assertTrue(debugDivider <= searchDivider - 150, "Debug should grow the default Search dock");
                tabs.setSelectedIndex(tabs.indexOfTab("Search"));
                assertEquals(debugDivider, editor.editorToolSplit.getDividerLocation(), "Smaller tools should not shrink the dock");

                editor.editorToolSplit.setDividerLocation(editor.editorToolSplit.getHeight() - 300);
                editor.validate();
                int manualDivider = editor.editorToolSplit.getDividerLocation();
                tabs.setSelectedIndex(tabs.indexOfTab("Replace"));
                editor.validate();
                assertEquals(manualDivider, editor.editorToolSplit.getDividerLocation(), "Tab changes should preserve a manually sized dock");
                editor.toolWindowHost.refreshActive();
                assertEquals(manualDivider, editor.editorToolSplit.getDividerLocation(), "Data refresh should preserve the divider");
                return null;
            });
        } finally {
            disposeEditor(editor);
        }
    }

    @Test
    void replacePanelInvalidatesPendingPreviewsAndGuardsDirtyDocuments() throws Exception {
        assumeSwingAvailable();
        Path home = Files.createDirectories(tempDir.resolve("home-replace-panel"));
        Path root = Files.createDirectories(tempDir.resolve("replace-panel-root"));
        Path file = Files.writeString(root.resolve("notes.txt"), "prefix old old");
        Texteditor editor = createEditor(home, file);
        try {
            assertTrue(awaitUiState(() -> !editor.stagedStartupPending));
            onEdt(() -> {
                editor.configManager.set("project.replace.enabled", "true");
                editor.showToolWindow(ToolWindowHost.Tab.REPLACE);
                var panel = editor.toolWindowHost.replacePanel();
                panel.setSearchQuery(WorkspaceSearchQuery.literal("old"));
                var fields = descendants(panel.component(), javax.swing.JTextField.class);
                fields.get(1).setText("new");
                descendants(panel.component(), javax.swing.JButton.class).stream().filter(b -> b.getText().equals("Preview")).findFirst().orElseThrow().doClick();
                fields.getFirst().setText("other");
                return null;
            });
            assertTrue(awaitUiState(() -> editor.asyncJobService.list().stream().noneMatch(job -> job.getStatus() == AsyncJobService.Status.RUNNING)));
            assertEquals(null, onEdt(() -> editor.workspaceReplaceCoordinator().planForPanel()));
            onEdt(() -> { editor.workspaceReplaceCoordinator().previewForPanel(WorkspaceSearchQuery.literal("old"), "new"); return null; });
            assertTrue(awaitUiState(() -> editor.workspaceReplaceCoordinator().planForPanel() != null));
            onEdt(() -> {
                var tables = descendants(editor.toolWindowHost.replacePanel().component(), javax.swing.JTable.class);
                assertEquals("notes.txt", tables.get(0).getValueAt(0, 1));
                assertEquals(2, tables.get(1).getRowCount());
                tables.get(1).setRowSelectionInterval(1, 1);
                tables.get(1).setValueAt(false, 1, 0);
                assertEquals(1, tables.get(1).getSelectedRow());
                assertEquals(1, editor.workspaceReplaceCoordinator().planForPanel().selectedMatchCount());
                editor.writingArea.append(" unsaved");
                assertTrue(editor.workspaceReplaceCoordinator().applyForPanel().startsWith("Save or close"));
                return null;
            });
            assertEquals("prefix old old", Files.readString(file));
        } finally { disposeEditor(editor); }
    }

    @Test
    void openEditorsShowDirtyStatePreserveViewsAndCloseSafelyAcrossSplits() throws Exception {
        assumeSwingAvailable();
        Path home = Files.createDirectories(tempDir.resolve("home-open-editors"));
        Path a = Files.writeString(Files.createDirectories(tempDir.resolve("a")).resolve("same.txt"), "first document");
        Path b = Files.writeString(Files.createDirectories(tempDir.resolve("b")).resolve("same.txt"), "second document");
        Texteditor editor = createEditor(home, a, b);
        try {
            assertTrue(awaitUiState(() -> !editor.stagedStartupPending));
            onEdt(() -> {
                var bar = editor.openEditorsBar;
                assertTrue(bar.isVisible());
                FileBuffer second = editor.getCurrentBuffer();
                editor.writingArea.select(2, 6);
                var list = descendants(bar, javax.swing.JList.class).getFirst();
                assertEquals(2, list.getModel().getSize());
                javax.swing.JLabel firstLabel = (javax.swing.JLabel) list.getCellRenderer().getListCellRendererComponent(
                    list, list.getModel().getElementAt(0), 0, false, false);
                assertTrue(firstLabel.getText().contains("same.txt —"));
                list.setSelectedIndex(0);
                FileBuffer first = editor.getCurrentBuffer();
                assertTrue(first != second);
                editor.writingArea.append(" dirty");
                editor.updateStatusBar();
                javax.swing.JLabel dirtyLabel = (javax.swing.JLabel) list.getCellRenderer().getListCellRendererComponent(
                    list, list.getSelectedValue(), list.getSelectedIndex(), true, true);
                assertTrue(dirtyLabel.getText().startsWith("● "));
                assertTrue(dirtyLabel.getAccessibleContext().getAccessibleName().contains("unsaved changes"));
                assertFalse(bar.closeDocument(editor.documentStore.id(first), false));
                assertTrue(editor.buffers.contains(first));
                list.setSelectedIndex(1);
                assertEquals("cond", editor.writingArea.getSelectedText());
                assertTrue(bar.closeDocument(editor.documentStore.id(first), true));
                assertSame(second, editor.getCurrentBuffer());
                editor.splitWindow(true);
                assertTrue(bar.closeDocument(editor.documentStore.id(second), false));
                assertEquals(1, editor.buffers.size());
                assertTrue(editor.editorPanes.stream().allMatch(pane -> pane.getBuffer() == editor.getCurrentBuffer()));
                assertTrue(editor.isDisplayable());
                editor.configManager.set("ui.open_editors.visible", "false");
                editor.sessionConfigController.applyRuntimeConfigFromSettings();
                assertFalse(bar.isVisible());
                return null;
            });
        } finally { disposeEditor(editor); }
    }

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

    private static OpenBufferCompletionIndex completionIndex(Texteditor editor) throws Exception {
        var field = InputController.class.getDeclaredField("openBufferCompletionIndex"); field.setAccessible(true);
        return (OpenBufferCompletionIndex) field.get(editor.inputController);
    }

    @Test
    void fileWatchRegistrationEndsWithItsBufferAndPreservesIndependentListeners() throws Exception {
        assumeSwingAvailable();
        Path path = Files.writeString(tempDir.resolve("watch-owner.md"), "watched original");
        Texteditor editor = createEditor(tempDir.resolve("watch-owner-home"), path);
        FileWatcherService.WatchRegistration independent = null;
        try {
            assertTrue(awaitUiState(() -> !editor.stagedStartupPending));
            FileBuffer buffer = onEdt(editor::getCurrentBuffer);
            independent = onEdt(() -> editor.fileWatcherService.watch(path.toFile(), file -> { }));
            onEdt(() -> {
                for (int i=0;i<3;i++) editor.registerFileWatch(buffer);
                var field=FileWatcherService.class.getDeclaredField("listeners"); field.setAccessible(true);
                var listeners=(java.util.Map<?,?>)field.get(editor.fileWatcherService);
                assertEquals(2, ((java.util.List<?>)listeners.get(path.toFile().getAbsolutePath())).size());
                editor.splitWindow(true); editor.closeActiveWindow();
                assertTrue(editor.fileWatcherService.isWatching(path.toFile()));
                editor.closeActiveBufferWithoutExiting(true);
                assertEquals(1, ((java.util.List<?>)listeners.get(path.toFile().getAbsolutePath())).size());
                assertEquals("watched original", buffer.getContent()); return null;
            });
            independent.close(); independent=null;
            assertFalse(onEdt(() -> editor.fileWatcherService.isWatching(path.toFile())));
        } finally { if(independent!=null)independent.close(); disposeEditor(editor); }
    }

    @Test
    void closingCancelsPendingWordIndexesAndReopeningCannotReceiveTheOldResult() throws Exception {
        assumeSwingAvailable();
        Path path = Files.writeString(tempDir.resolve("completion-reopen.md"), "alpha beta original");
        Texteditor editor = createEditor(tempDir.resolve("completion-reopen-home"), path);
        var executor = java.util.concurrent.Executors.newSingleThreadExecutor();
        var blockerEntered = new java.util.concurrent.CountDownLatch(1); var release = new java.util.concurrent.CountDownLatch(1);
        AsyncJobService original = editor.asyncJobService; var controlled = new AsyncJobService(executor, 20, null);
        try {
            assertTrue(awaitUiState(() -> !editor.stagedStartupPending)); FileBuffer closed = onEdt(editor::getCurrentBuffer);
            assertTrue(awaitUiState(() -> completionIndex(editor).hasSnapshot(closed, closed.textSnapshot())));
            executor.submit(() -> { blockerEntered.countDown(); try { release.await(); } catch (InterruptedException ignored) { } });
            assertTrue(blockerEntered.await(5, TimeUnit.SECONDS));
            onEdt(() -> {
                editor.asyncJobService = controlled;
                var submit = InputController.class.getDeclaredMethod("submitOpenBufferWordIndex", FileBuffer.class, VersionedTextSnapshot.class);
                submit.setAccessible(true); submit.invoke(editor.inputController, closed, closed.textSnapshot());
                int job = controlled.list().getFirst().getId();
                editor.closeActiveBufferWithoutExiting(true);
                assertEquals(AsyncJobService.Status.CANCELLED, controlled.get(job).getStatus());
                assertFalse(completionIndex(editor).hasSnapshot(closed));
                var pending = InputController.class.getDeclaredField("pendingWordIndexBuffer"); pending.setAccessible(true);
                assertFalse(pending.get(editor.inputController) == closed);
                return null;
            });
            release.countDown();
            onEdt(() -> { editor.documents.open(path, 1, 1, "reopen"); return null; });
            assertTrue(awaitCurrentFile(editor, path));
            FileBuffer reopened = onEdt(editor::getCurrentBuffer); assertFalse(reopened == closed);
            assertTrue(awaitUiState(() -> completionIndex(editor).hasSnapshot(reopened, reopened.textSnapshot())));
            onEdt(() -> {
                assertEquals("alpha beta original", reopened.getContent()); assertFalse(reopened.isModified());
                assertFalse(completionIndex(editor).hasSnapshot(closed));
                assertTrue(completionIndex(editor).hasSnapshot(reopened)); return null;
            });
        } finally {
            release.countDown(); controlled.shutdownNow(); onEdt(() -> { editor.asyncJobService = original; return null; }); disposeEditor(editor);
        }
    }

    @Test
    void replacingASessionDropsOnlyTheRetiredBufferCompletionSnapshot() throws Exception {
        assumeSwingAvailable();
        Path path = Files.writeString(tempDir.resolve("completion-session.md"), "session alpha original");
        Texteditor editor = createEditor(tempDir.resolve("completion-session-home"), path);
        Path session = tempDir.resolve("completion-session.json");
        try {
            assertTrue(awaitUiState(() -> !editor.stagedStartupPending)); FileBuffer retired = onEdt(editor::getCurrentBuffer);
            assertTrue(awaitUiState(() -> completionIndex(editor).hasSnapshot(retired, retired.textSnapshot())));
            onEdt(() -> {
                assertTrue(editor.sessionConfigController.saveSession(session.toString()).startsWith("Session saved:"));
                assertTrue(editor.sessionConfigController.loadSession(session.toString(), true).startsWith("Restored session:"));
                assertFalse(editor.buffers.contains(retired)); assertFalse(completionIndex(editor).hasSnapshot(retired)); return null;
            });
            FileBuffer active = onEdt(editor::getCurrentBuffer);
            assertEquals("session alpha original", onEdt(active::getContent));
            assertTrue(awaitUiState(() -> completionIndex(editor).hasSnapshot(active, active.textSnapshot())));
        } finally { disposeEditor(editor); }
    }

    @Test
    void completionIndexEndsWithBufferOwnershipRatherThanSplitVisibility() throws Exception {
        assumeSwingAvailable();
        Path firstPath = Files.writeString(tempDir.resolve("completion-first.md"), "alpha original");
        Path nextPath = Files.writeString(tempDir.resolve("completion-next.java"), "class Next { int beta; }");
        Texteditor editor = createEditor(tempDir.resolve("completion-close-home"), firstPath);
        try {
            assertTrue(awaitUiState(() -> !editor.stagedStartupPending));
            FileBuffer first = onEdt(editor::getCurrentBuffer);
            assertTrue(awaitUiState(() -> completionIndex(editor).hasSnapshot(first, first.textSnapshot())));
            onEdt(() -> {
                editor.splitWindow(true); editor.closeActiveWindow();
                assertTrue(editor.buffers.contains(first)); assertTrue(completionIndex(editor).hasSnapshot(first));
                editor.writingArea.insert("unsaved ", 0);
                assertTrue(first.isModified());
                FileBuffer next = new FileBuffer(nextPath.toFile()); editor.buffers.add(next); editor.loadBufferIntoEditor(next);
                editor.loadBufferIntoEditor(first);
                assertTrue(editor.deleteBuffer(false).contains("No write"));
                assertTrue(completionIndex(editor).hasSnapshot(first)); assertTrue(first.getUndoManager().canUndo());
                assertEquals("unsaved alpha original", first.getContent());
                editor.deleteBuffer(true);
                assertFalse(editor.buffers.contains(first));
                assertFalse(completionIndex(editor).hasSnapshot(first), "closed buffer remains in completion index");
                assertEquals("unsaved alpha original", first.getContent()); assertTrue(first.getUndoManager().canUndo());
                return null;
            });
            FileBuffer next = onEdt(editor::getCurrentBuffer);
            assertTrue(awaitUiState(() -> completionIndex(editor).hasSnapshot(next, next.textSnapshot())));
            onEdt(() -> {
                editor.closeActiveBufferWithoutExiting(true);
                assertFalse(completionIndex(editor).hasSnapshot(next)); return null;
            });
        } finally { disposeEditor(editor); }
    }

    @Test
    void indicEditsUseExistingVimEmacsAndPlainMultiSelectionRoutes() throws Exception {
        assumeSwingAvailable();
        String source = "क्ष|क्ष";
        Texteditor editor = createEditor(tempDir.resolve("indic-routes-home"), Files.writeString(tempDir.resolve("indic-routes.md"), source));
        try {
            assertTrue(awaitUiState(() -> !editor.stagedStartupPending));
            onEdt(() -> {
                var buffer = editor.getCurrentBuffer();
                for (String profile : List.of("vim", "emacs", "plain")) {
                    editor.configManager.set("keymap.profile", profile); editor.sessionConfigController.applyRuntimeConfigFromSettings();
                    buffer.getUndoManager().discardAllEdits();
                    if (profile.equals("vim")) {
                        editor.setMode(EditorMode.INSERT); editor.writingArea.setCaretPosition(3);
                        editor.keyPressed(new KeyEvent(editor.writingArea, KeyEvent.KEY_PRESSED, 0, 0, KeyEvent.VK_BACK_SPACE, '\b'));
                    } else if (profile.equals("emacs")) {
                        editor.writingArea.setCaretPosition(0);
                        editor.keyPressed(new KeyEvent(editor.writingArea, KeyEvent.KEY_PRESSED, 0, KeyEvent.CTRL_DOWN_MASK, KeyEvent.VK_F, 'f'));
                        assertEquals(3, editor.writingArea.getCaretPosition()); editor.writingArea.setCaretPosition(0);
                        editor.keyPressed(new KeyEvent(editor.writingArea, KeyEvent.KEY_PRESSED, 0, KeyEvent.CTRL_DOWN_MASK, KeyEvent.VK_D, 'd'));
                    } else {
                        editor.writingArea.setCaretPosition(3); editor.extraSelections.add(MultiSelection.caret(7));
                        editor.keyPressed(new KeyEvent(editor.writingArea, KeyEvent.KEY_PRESSED, 0, 0, KeyEvent.VK_BACK_SPACE, '\b'));
                    }
                    String expected = profile.equals("plain") ? "|" : "|क्ष";
                    assertEquals(expected, buffer.getContent(), profile); assertEquals(0, editor.writingArea.getCaretPosition());
                    editor.clearExtraCursors(); buffer.getUndoManager().undo(); assertEquals(source, buffer.getContent(), profile);
                    assertFalse(buffer.getUndoManager().canUndo());
                    buffer.getUndoManager().redo(); assertEquals(expected, buffer.getContent());
                    buffer.getUndoManager().undo(); assertEquals(source, buffer.getContent());
                }
                editor.configManager.set("keymap.profile", "vim"); editor.setMode(EditorMode.NORMAL);
                buffer.getUndoManager().discardAllEdits(); editor.writingArea.setCaretPosition(0);
                editor.keyPressed(new KeyEvent(editor.writingArea, KeyEvent.KEY_PRESSED, 0, 0, KeyEvent.VK_X, 'x'));
                assertEquals("|क्ष", buffer.getContent());
                buffer.getUndoManager().undo(); assertEquals(source, buffer.getContent());
                assertFalse(buffer.getUndoManager().canUndo());
                buffer.getUndoManager().redo(); assertEquals("|क्ष", buffer.getContent());
                editor.persistRecoverySnapshots();
                editor.splitWindow(true);
                var first = editor.editorPanes.getFirst().getTextArea(); var second = editor.editorPanes.getLast().getTextArea();
                assertSame(buffer.getDocument(), first.getDocument()); assertSame(first.getDocument(), second.getDocument());
                first.setCaretPosition(0); second.setCaretPosition(1);
                assertEquals(0, first.getCaretPosition()); assertEquals(1, second.getCaretPosition());
                assertEquals("shed-light", editor.configManager.getThemeId());
                assertEquals("JetBrains Mono", editor.writingArea.getFont().getFamily());
                return null;
            });
            assertTrue(awaitUiState(() -> {
                try {
                    var journal = RecoveryJournal.read(editor.recoveryStoreDir.toPath());
                    return journal != null && journal.entries().stream().anyMatch(entry -> entry.content().equals("|क्ष"));
                } catch (java.io.IOException failure) { throw new RuntimeException(failure); }
            }));
        } finally { disposeEditor(editor); }
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
            assertEquals("JetBrains Mono", onEdt(() -> area.getFont().getFamily()));
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
            awaitWindowGeometrySettled(editor);
            assertEquals(javax.swing.JFrame.NORMAL,
                onEdt(() -> editor.getExtendedState() & javax.swing.JFrame.MAXIMIZED_BOTH));
            saved = onEdt(() -> {
                Rectangle current = editor.getBounds();
                Rectangle next = new Rectangle(current.x + 8, current.y + 8,
                    Math.max(400, current.width - 16), Math.max(300, current.height - 16));
                editor.setBounds(next);
                return next;
            });
            awaitWindowBounds(editor, saved);
            onEdt(() -> { editor.windowPlacementStore.save(editor); return null; });
        } finally {
            disposeEditor(editor);
        }
        Texteditor restored = createEmptyEditor(home);
        try {
            awaitWindowBounds(restored, saved);
            assertEquals(saved, onEdt(restored::getBounds));
        } finally {
            disposeEditor(restored);
        }
    }

    private static void awaitWindowGeometrySettled(Texteditor editor) throws Exception {
        // Showing a frame returns before an X11 window manager has applied its decorations.
        // Wait off the EDT for native bounds/insets events before resizing or persisting it.
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5), stableSince = System.nanoTime();
        String previous = "";
        while (System.nanoTime() < deadline) {
            String geometry = onEdt(() -> editor.getBounds() + " " + editor.getInsets());
            if (!geometry.equals(previous)) { previous = geometry; stableSince = System.nanoTime(); }
            else if (System.nanoTime() - stableSince >= TimeUnit.MILLISECONDS.toNanos(250)) return;
            Thread.sleep(25);
        }
        org.junit.jupiter.api.Assertions.fail("Window geometry did not settle");
    }

    private static void awaitWindowBounds(Texteditor editor, Rectangle expected) throws Exception {
        awaitWindowGeometrySettled(editor);
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline) {
            if (expected.equals(onEdt(editor::getBounds))) return;
            Thread.sleep(25);
        }
        assertEquals(expected, onEdt(editor::getBounds), "Window manager did not restore the requested bounds");
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
    void closingTabChecksCurrentDirtyStateInsteadOfCachedPresentation() throws Exception {
        assumeSwingAvailable();
        Path home = Files.createDirectories(tempDir.resolve("home-live-dirty-close"));
        Texteditor editor = createEditor(home, Files.writeString(tempDir.resolve("dirty-close.txt"), "text"));
        try {
            assertTrue(awaitUiState(() -> !editor.stagedStartupPending));
            onEdt(() -> {
                FileBuffer document = editor.getCurrentBuffer();
                document.setModified(false);
                editor.openEditorsBar.refresh();
                document.setModified(true); // Application state changes before the next presentation refresh.
                boolean[] prompted = {false};
                drivePicker(editor, "Unsaved Changes", dialog -> {
                    prompted[0] = true;
                    dialog.dispose(); // Cancel; never grant discard permission.
                }, () -> { editor.openEditorsBar.closeSelected(); return ""; });
                assertTrue(prompted[0], "a cached clean tab must not bypass confirmation");
                assertTrue(editor.buffers.contains(document));
                assertTrue(document.isModified());
                return null;
            });
        } finally { disposeEditor(editor); }
    }

    @Test
    void documentOperationsRejectSupersededAndClosedPaneLoads() throws Exception {
        assumeSwingAvailable();
        Path home = Files.createDirectories(tempDir.resolve("home-document-requests"));
        Path source = Files.writeString(tempDir.resolve("request-source.txt"), "source");
        Path rejected = Files.writeString(tempDir.resolve("request-rejected.txt"), "rejected");
        Path latest = Files.writeString(tempDir.resolve("request-latest.txt"), "latest");
        Texteditor editor = createEditor(home, source);
        try {
            assertTrue(awaitUiState(() -> !editor.stagedStartupPending));
            onEdt(() -> {
                editor.documents.open(rejected, 0, 0, "rejected");
                editor.documents.open(latest, 0, 0, "latest");
                return null;
            });
            assertTrue(awaitCurrentFile(editor, latest));
            assertTrue(awaitUiState(() -> editor.asyncJobService.list().stream()
                .filter(job -> "open file".equals(job.getDescription()))
                .noneMatch(job -> job.getStatus() == AsyncJobService.Status.RUNNING)));
            flushEdt();
            onEdt(() -> {
                assertFalse(editor.documents.isOpen(rejected));
                editor.documents.split(true);
                editor.documents.open(rejected, 0, 0, "rejected");
                assertEquals("Window closed", editor.documents.closeWindow(true));
                return null;
            });
            assertTrue(awaitUiState(() -> editor.asyncJobService.list().stream()
                .filter(job -> "open file".equals(job.getDescription()))
                .noneMatch(job -> job.getStatus() == AsyncJobService.Status.RUNNING)));
            flushEdt();
            onEdt(() -> {
                assertFalse(editor.documents.isOpen(rejected));
                assertEquals(latest.toFile(), editor.getCurrentBuffer().getFile());
                return null;
            });
        } finally { disposeEditor(editor); }
    }

    @Test
    void paletteDisposesResourcesWhenPreviewSetupFails() throws Exception {
        assumeSwingAvailable();
        Path home = Files.createDirectories(tempDir.resolve("home-picker-setup-failure"));
        Path source = Files.writeString(tempDir.resolve("picker-setup-failure.txt"), "source stays open");
        Texteditor editor = createEditor(home, source);
        javax.swing.JDialog[] failed = new javax.swing.JDialog[1];
        try {
            assertTrue(awaitUiState(() -> !editor.stagedStartupPending));
            onEdt(() -> {
                org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class, () ->
                    editor.paletteController.showPaletteDialog("Failing preview", List.of("alpha"), selected -> {
                        failed[0] = Arrays.stream(editor.getOwnedWindows())
                            .filter(javax.swing.JDialog.class::isInstance).map(javax.swing.JDialog.class::cast)
                            .filter(window -> "Failing preview".equals(window.getTitle())).findFirst().orElseThrow();
                        failed[0].pack();
                        assertTrue(failed[0].isDisplayable());
                        throw new IllegalStateException("preview fixture failed");
                    }));
                assertFalse(failed[0].isDisplayable(), "setup failure must dispose the realized picker");
                var field = descendants(failed[0], javax.swing.JTextField.class).getFirst();
                var results = descendants(failed[0], javax.swing.JList.class).getFirst();
                assertEquals(0, field.getActionListeners().length, "picker must release its acceptance listener");
                field.setText("after disposal");
                assertTrue(results.isEnabled());
                assertEquals(1, results.getModel().getSize(), "disposed widgets must no longer trigger filtering");
                assertEquals(source.toFile(), editor.getCurrentBuffer().getFile());
                return null;
            });
        } finally {
            if (failed[0] != null) onEdt(() -> { failed[0].dispose(); return null; });
            disposeEditor(editor);
        }
    }

    @Test
    void bufferPickerResolvesCapturedIdentityAfterLiveListReordering() throws Exception {
        assumeSwingAvailable();
        Path home = Files.createDirectories(tempDir.resolve("home-buffer-boundary"));
        Path first = Files.writeString(tempDir.resolve("first-buffer.txt"), "first");
        Path second = Files.writeString(tempDir.resolve("second-buffer.txt"), "second");
        Texteditor editor = createEditor(home, first, second);
        try {
            assertTrue(awaitUiState(() -> !editor.stagedStartupPending));
            onEdt(() -> {
                String result = drivePicker(editor, "Open Buffers", dialog -> {
                    java.util.Collections.reverse(editor.buffers);
                    var field = descendants(dialog, javax.swing.JTextField.class).getFirst();
                    field.setText("first-buffer.txt");
                    field.postActionEvent();
                }, editor.paletteController::showBufferFinder);
                assertEquals("Switched to buffer", result);
                assertEquals(first.toFile(), editor.getCurrentBuffer().getFile());
                return null;
            });
        } finally { disposeEditor(editor); }
    }

    @Test
    void settingsDialogReadsAndPersistsThroughApplicationBoundary() throws Exception {
        assumeSwingAvailable();
        Path home = Files.createDirectories(tempDir.resolve("home-settings-boundary"));
        Texteditor editor = createEditor(home, Files.writeString(tempDir.resolve("settings-boundary.txt"), "text"));
        SettingsEditorDialog[] dialog = new SettingsEditorDialog[1];
        try {
            onEdt(() -> {
                SettingsEditorDialog.showFor(editor);
                dialog[0] = Arrays.stream(Window.getWindows()).filter(SettingsEditorDialog.class::isInstance)
                    .map(SettingsEditorDialog.class::cast).filter(Window::isDisplayable).findFirst().orElseThrow();
                var field = descendants(dialog[0], javax.swing.JTextField.class).stream()
                    .filter(input -> "tab.size".equals(input.getClientProperty("shed.setting.key")))
                    .findFirst().orElseThrow();
                field.setText("7");
                field.postActionEvent();
                assertEquals(7, editor.configManager.getTabSize());
                assertEquals(7, editor.writingArea.getTabSize());
                assertTrue(Files.readString(Path.of(editor.configManager.getConfigPath())).contains("7"));
                assertEquals("Set and saved tab.size", editor.setConfigOptionPersistent("tab.size", "5"));
                assertEquals(5, editor.writingArea.getTabSize());
                return null;
            });
        } finally {
            if (dialog[0] != null) onEdt(() -> { dialog[0].dispose(); return null; });
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
            assertTrue(fields.stream().allMatch(field -> editor.configManager.getFloatingSurfaceColor().equals(field.getBackground())));
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
                .allMatch(list -> editor.configManager.getFloatingSurfaceColor().equals(list.getBackground()))));
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
            assertTrue(awaitUiState(() -> !editor.stagedStartupPending), "Footer assertions require full UI readiness");
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

            preparePreviewScrollGeometry(editor, preview);
            onEdt(() -> {
                javax.swing.JScrollBar sourceBar = source.getScrollPane().getVerticalScrollBar();
                sourceBar.setValue(sourceBar.getMaximum() - sourceBar.getVisibleAmount());
                return null;
            });
            awaitPreviewScrollAtSource(editor, source, preview);
            PreviewScrollState sourceScroll = onEdt(() -> previewScrollState(preview));
            assertTrue(sourceScroll.value() > 0);

            onEdt(() -> {
                source.getTextArea().setCaretPosition(source.getTextArea().getDocument().getLength());
                return null;
            });
            awaitPreviewScrollAtSource(editor, source, preview);
            assertTrue(onEdt(preview::getVerticalScrollPosition) >= sourceScroll.value(),
                () -> "Before caret: " + sourceScroll);
            PreviewScrollState beforeDisable = onEdt(() -> {
                editor.validate();
                PreviewScrollState state = previewScrollState(preview);
                assertEquals(state.maximum(), state.value(), "The EOF caret must synchronize to the preview bottom");
                editor.configManager.set("markdown.preview.scroll.sync", "false");
                source.getScrollPane().getVerticalScrollBar().setValue(0);
                return state;
            });
            flushEdt();
            onEdt(() -> {
                editor.validate();
                assertEquals(beforeDisable, previewScrollState(preview),
                    "Source scrolling while sync is disabled must preserve position and settled geometry");
                return null;
            });
        } finally {
            onEdt(() -> { editor.disposeMarkdownPreviews(); return null; });
            disposeEditor(editor);
        }
    }

    @Test
    void markdownPreviewDisabledSyncClampsToNewRangeWhenNotificationExpires() throws Exception {
        assumeSwingAvailable();
        Path file = Files.writeString(tempDir.resolve("preview-expiry.md"),
            "## Section\n\nContent\n\n".repeat(180));
        Texteditor editor = createEditor(tempDir.resolve("home-preview-expiry"), file);
        try {
            assertTrue(awaitUiState(() -> !editor.stagedStartupPending));
            EditorPane source = onEdt(editor::getActivePane);
            FileBuffer buffer = onEdt(editor::getCurrentBuffer);
            assertEquals("Markdown preview opened", onEdt(() -> editor.commandHandler.execute("markdown preview")));
            MarkdownPreviewPane preview = onEdt(() -> (MarkdownPreviewPane) editor.editorPanes.stream()
                .filter(EditorPane::isMarkdownPreview).findFirst().orElseThrow().getComponent());
            onEdt(() -> {
                editor.showMessage("Temporary preview test notification");
                stopTimer(editor.messageResetTimer);
                editor.validate();
                source.getTextArea().setCaretPosition(buffer.getDocument().getLength());
                return null;
            });
            awaitPreviewScrollAtSource(editor, source, preview);
            onEdt(() -> {
                PreviewSourceState sourceBefore = previewSourceState(buffer);
                PreviewScrollState before = previewScrollState(preview);
                assertEquals(before.maximum(), before.value());
                editor.configManager.set("markdown.preview.scroll.sync", "false");
                // Exercise the real notification expiry handler, without a wall-clock delay.
                for (var listener : editor.messageResetTimer.getActionListeners())
                    listener.actionPerformed(new ActionEvent(editor.messageResetTimer, ActionEvent.ACTION_PERFORMED, "expire"));
                editor.validate();
                PreviewScrollState after = previewScrollState(preview);
                assertEquals(before.contentHeight(), after.contentHeight());
                assertTrue(after.extentHeight() > before.extentHeight(), "Hiding the command row must enlarge the viewport");
                assertEquals(Math.min(before.value(), after.maximum()), after.value(),
                    "Swing must clamp a scroll value that is outside the new range, even with sync disabled");
                assertEquals(sourceBefore, previewSourceState(buffer));
                return null;
            });
        } finally {
            onEdt(() -> { editor.disposeMarkdownPreviews(); return null; });
            disposeEditor(editor);
        }
    }

    @Test
    void markdownPreviewDebounceSplitsAndBufferSwitchKeepContentAndListenersIsolated() throws Exception {
        assumeSwingAvailable();
        Path file = Files.writeString(tempDir.resolve("preview-lifecycle.md"), "# Original\n\ntext\n");
        Texteditor editor = createEditor(tempDir.resolve("home-preview-lifecycle"), file);
        try {
            assertTrue(awaitUiState(() -> !editor.stagedStartupPending));
            onEdt(() -> { editor.splitWindow(true); return null; });
            EditorPane source = onEdt(editor::getActivePane);
            EditorPane sibling = onEdt(() -> editor.editorPanes.stream().filter(pane -> pane != source).findFirst().orElseThrow());
            FileBuffer buffer = onEdt(editor::getCurrentBuffer);
            int caretListeners = onEdt(() -> source.getTextArea().getCaretListeners().length);
            int scrollListeners = onEdt(() -> source.getScrollPane().getVerticalScrollBar().getAdjustmentListeners().length);
            assertEquals("Markdown preview opened", onEdt(() -> editor.commandHandler.execute("markdown preview")));
            MarkdownPreviewPane first = onEdt(() -> (MarkdownPreviewPane) editor.editorPanes.stream()
                .filter(EditorPane::isMarkdownPreview).findFirst().orElseThrow().getComponent());
            var sourceListener = onEdt(() -> Arrays.stream(buffer.getDocument().getDocumentListeners())
                .filter(listener -> listener.getClass().getEnclosingClass() == MarkdownPreviewPane.class)
                .findFirst().orElseThrow());
            onEdt(() -> {
                assertEquals(1, Arrays.stream(buffer.getDocument().getDocumentListeners())
                    .filter(listener -> listener.getClass().getEnclosingClass() == MarkdownPreviewPane.class).count());
                assertEquals(caretListeners + 1, source.getTextArea().getCaretListeners().length);
                assertEquals(scrollListeners + 1, source.getScrollPane().getVerticalScrollBar().getAdjustmentListeners().length);
                editor.activateEditorPane(sibling);
                assertSame(source.getTextArea().getDocument(), sibling.getTextArea().getDocument());
                int start = editor.writingArea.getDocument().getLength();
                editor.writingArea.append("\n## Obsolete");
                editor.writingArea.replaceRange("\n## Intermediate", start, editor.writingArea.getDocument().getLength());
                editor.writingArea.replaceRange("\n## Latest", start, editor.writingArea.getDocument().getLength());
                return null;
            });
            PreviewSourceState latest = onEdt(() -> previewSourceState(buffer));
            assertTrue(awaitUiState(() -> first.getHtml().contains("id=\"latest\"")));
            onEdt(() -> {
                assertFalse(first.getHtml().contains("Obsolete"));
                assertFalse(first.getHtml().contains("Intermediate"));
                assertEquals(latest, previewSourceState(buffer), "Debounced rendering must not edit shared source or history");
                assertEquals("Markdown preview refreshed", editor.commandHandler.execute("markdown refresh"));
                assertEquals(latest, previewSourceState(buffer));
                editor.activateEditorPane(source);
                return null;
            });
            Path otherFile = Files.writeString(tempDir.resolve("other-preview.md"), "# Other\n");
            FileBuffer other = new FileBuffer(otherFile.toFile());
            String closedHtml = onEdt(() -> {
                editor.writingArea.append("\n## Pending old document render");
                String html = first.getHtml();
                editor.buffers.add(other);
                editor.loadBufferIntoEditor(other); // Closes the preview and cancels its pending debounce.
                assertTrue(editor.editorPanes.stream().noneMatch(EditorPane::isMarkdownPreview));
                assertFalse(Arrays.asList(buffer.getDocument().getDocumentListeners()).contains(sourceListener));
                assertEquals(caretListeners, source.getTextArea().getCaretListeners().length);
                assertEquals(scrollListeners, source.getScrollPane().getVerticalScrollBar().getAdjustmentListeners().length);
                return html;
            });
            assertEquals("Markdown preview opened", onEdt(() -> editor.commandHandler.execute("markdown preview")));
            MarkdownPreviewPane current = onEdt(() -> (MarkdownPreviewPane) editor.editorPanes.stream()
                .filter(EditorPane::isMarkdownPreview).findFirst().orElseThrow().getComponent());
            onEdt(() -> { editor.writingArea.append("\n## Current final\n"); return null; });
            PreviewSourceState currentSource = onEdt(() -> previewSourceState(other));
            // Waiting for the newer debounce also lets the older deadline pass; no arbitrary sleep.
            assertTrue(awaitUiState(() -> current.getHtml().contains("id=\"current-final\"")));
            onEdt(() -> {
                assertFalse(current.getHtml().contains("Original"));
                assertFalse(current.getHtml().contains("Pending old document render"));
                assertEquals(closedHtml, first.getHtml(), "A disposed preview must ignore its older pending update");
                assertEquals(currentSource, previewSourceState(other));
                assertEquals("Markdown preview closed", editor.commandHandler.execute("markdown close"));
                assertTrue(editor.editorPanes.stream().noneMatch(EditorPane::isMarkdownPreview));
                assertEquals(currentSource, previewSourceState(other));
                return null;
            });
            Path large = Files.writeString(tempDir.resolve("preview-projected.md"), "# Large\n" + "body\n".repeat(1001));
            onEdt(() -> { editor.configManager.set("large.file.line.threshold", "1000"); return null; });
            FileBuffer projected = new FileBuffer(large.toFile(), editor.configManager);
            onEdt(() -> {
                assertTrue(projected.isLargeFile());
                editor.buffers.add(projected); editor.loadBufferIntoEditor(projected);
                PreviewSourceState before = previewSourceState(projected);
                assertEquals("Markdown preview unavailable for a large file", editor.commandHandler.execute("markdown preview"));
                assertEquals(before, previewSourceState(projected));
                return null;
            });
        } finally {
            onEdt(() -> { editor.disposeMarkdownPreviews(); return null; });
            disposeEditor(editor);
        }
    }

    private record PreviewSourceState(String text, long revision, VersionedTextSnapshot snapshot,
                                      boolean dirty, String saved, int undoEntries, long undoBytes,
                                      boolean canUndo, boolean canRedo) { }

    private static PreviewSourceState previewSourceState(FileBuffer buffer) {
        BoundedUndoManager history = (BoundedUndoManager) buffer.getUndoManager();
        return new PreviewSourceState(buffer.getContent(), buffer.documentRevision(), buffer.textSnapshot(),
            buffer.isModified(), buffer.getSavedContent(), history.retainedEditCount(), history.retainedBytes(),
            history.canUndo(), history.canRedo());
    }

    private record PreviewScrollState(int value, int maximum, int contentHeight, int extentWidth, int extentHeight) { }

    private static PreviewScrollState previewScrollState(MarkdownPreviewPane preview) {
        var scroll = descendants(preview, javax.swing.JScrollPane.class).getFirst();
        var viewport = scroll.getViewport();
        var bar = scroll.getVerticalScrollBar();
        return new PreviewScrollState(bar.getValue(), bar.getMaximum() - bar.getVisibleAmount(),
            viewport.getViewSize().height, viewport.getExtentSize().width, viewport.getExtentSize().height);
    }

    private static void preparePreviewScrollGeometry(Texteditor editor, MarkdownPreviewPane preview) throws Exception {
        assertTrue(awaitUiState(() -> !editor.stagedStartupPending));
        onEdt(() -> {
            // The startup notification's expiry changes footer height independently of source synchronization.
            // Keep that row present while comparing exact pixels; production timer behavior is unchanged.
            editor.showMessage("Preview scroll synchronization test");
            stopTimer(editor.messageResetTimer);
            editor.validate();
            return null;
        });
        assertTrue(awaitUiState(() -> {
            editor.validate();
            var scroll = descendants(preview, javax.swing.JScrollPane.class).getFirst();
            var viewport = scroll.getViewport();
            var bar = scroll.getVerticalScrollBar();
            return scroll.isValid() && viewport.getExtentSize().height > 0
                && bar.getVisibleAmount() == viewport.getExtentSize().height
                && bar.getMaximum() == viewport.getViewSize().height;
        }), "The real preview layout and scrollbar model must agree before measuring synchronization");
    }

    private static void awaitPreviewScrollAtSource(Texteditor editor, EditorPane source, MarkdownPreviewPane preview) throws Exception {
        assertTrue(awaitUiState(() -> {
            editor.validate();
            var sourceBar = source.getScrollPane().getVerticalScrollBar();
            int sourceMaximum = sourceBar.getMaximum() - sourceBar.getVisibleAmount();
            PreviewScrollState state = previewScrollState(preview);
            if (sourceMaximum <= 0 || state.maximum() <= 0) return false;
            int expected = (int) Math.round((double) sourceBar.getValue() / sourceMaximum * state.maximum());
            return state.value() == expected;
        }), "Wait for the queued source-to-preview update at the current layout, not a fixed EDT turn count");
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
            assertTrue(onEdt(() -> terminalPane.getInputComponent() instanceof AnimatedTerminalPanel));
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

    @Test
    void focusModeRestoresPrehiddenChromeAndPreservesSource() throws Exception {
        assumeSwingAvailable();
        Path file = Files.writeString(tempDir.resolve("focus-restore.md"), "\uFEFF# Heading\r\nUnicode e\u0301 😀\r\n");
        byte[] originalBytes = Files.readAllBytes(file);
        Texteditor editor = createEditor(tempDir.resolve("focus-restore-home"), file);
        try {
            assertTrue(awaitUiState(() -> !editor.stagedStartupPending));
            onEdt(() -> {
                editor.workbenchHeader.setVisible(false); editor.openEditorsBar.setVisible(false);
                editor.footerPanel.setVisible(false); editor.statusBar.setVisible(false);
                var buffer = editor.getCurrentBuffer(); long revision = buffer.documentRevision();
                var document = editor.writingArea.getDocument(); editor.writingArea.select(2, 8);
                editor.toggleGoyoMode(); editor.toggleGoyoMode();
                assertFalse(editor.workbenchHeader.isVisible()); assertFalse(editor.openEditorsBar.isVisible());
                assertFalse(editor.footerPanel.isVisible()); assertFalse(editor.statusBar.isVisible());
                assertSame(document, editor.writingArea.getDocument()); assertEquals(revision, buffer.documentRevision());
                assertEquals(2, editor.writingArea.getSelectionStart()); assertEquals(8, editor.writingArea.getSelectionEnd());
                assertFalse(buffer.isModified()); assertFalse(buffer.getUndoManager().canUndo());
                buffer.save(); org.junit.jupiter.api.Assertions.assertArrayEquals(originalBytes, Files.readAllBytes(file));
                return null;
            });
        } finally { disposeEditor(editor); }
    }

    @Test
    void focusCommandsAreIdempotentAndKeepCommandExitAvailableAcrossKeymaps() throws Exception {
        assumeSwingAvailable();
        Texteditor editor = createEditor(tempDir.resolve("focus-commands-home"), Files.writeString(tempDir.resolve("focus-commands.md"), "# Heading\nbody\n"));
        try {
            assertTrue(awaitUiState(() -> !editor.stagedStartupPending));
            onEdt(() -> {
                assertEquals("focus toggle", PaletteController.surfaceActionCommand("Toggle Focus Mode"));
                assertEquals("focus off", PaletteController.surfaceActionCommand("Exit Focus Mode"));
                assertEquals("center toggle", PaletteController.surfaceActionCommand("Toggle Centered Writing"));
                for (String profile : new String[]{"plain", "vim", "emacs"}) {
                    editor.configManager.set("keymap.profile", profile);
                    assertEquals("Focus mode enabled", editor.commandHandler.execute("focus on"));
                    assertEquals("Focus mode enabled", editor.commandHandler.execute("focus on"));
                    assertFalse(editor.workbenchHeader.isVisible()); assertFalse(editor.footerPanel.isVisible());
                    editor.setMode(EditorMode.COMMAND);
                    assertTrue(editor.footerPanel.isVisible()); assertTrue(editor.commandBar.isVisible());
                    assertFalse(editor.statusBar.isVisible());
                    assertEquals("Focus mode disabled", editor.commandHandler.execute("focus off"));
                    editor.setMode(EditorMode.NORMAL);
                    assertTrue(editor.workbenchHeader.isVisible()); assertTrue(editor.footerPanel.isVisible());
                    assertEquals("Focus mode disabled", editor.commandHandler.execute("focus off"));
                    assertFalse(editor.focusModeController.isCenteredWriting());
                }
                assertTrue(editor.commandHandler.execute("focus invalid").startsWith("Usage:"));
                assertTrue(editor.commandHandler.execute("center invalid").startsWith("Usage:"));
                return null;
            });
        } finally { disposeEditor(editor); }
    }

    @Test
    void focusKeepsMinimapIdentityAndListenerCountAcrossRepeatedCycles() throws Exception {
        assumeSwingAvailable();
        Texteditor editor = createEditor(tempDir.resolve("focus-minimap-home"), Files.writeString(tempDir.resolve("focus-minimap.md"), "body\n".repeat(100)));
        try {
            assertTrue(awaitUiState(() -> !editor.stagedStartupPending));
            onEdt(() -> {
                editor.toggleMinimap(); var minimap = editor.activeMinimapPanel;
                var viewport = editor.getActivePane().getScrollPane().getViewport(); int listeners = viewport.getChangeListeners().length;
                for (int i = 0; i < 12; i++) {
                    editor.focusModeController.setGoyoEnabled(true);
                    assertSame(minimap, editor.activeMinimapPanel); assertFalse(minimap.isVisible());
                    editor.focusModeController.setGoyoEnabled(false);
                    assertSame(minimap, editor.activeMinimapPanel); assertTrue(minimap.isVisible());
                }
                assertEquals(listeners, viewport.getChangeListeners().length);
                return null;
            });
        } finally { disposeEditor(editor); }
    }

    @Test
    void focusBoundsEachSplitAndKeepsSharedDocumentAndNavigation() throws Exception {
        assumeSwingAvailable();
        Texteditor editor = createEditor(tempDir.resolve("focus-splits-home"), Files.writeString(tempDir.resolve("focus-splits.md"), "body text\n".repeat(100)));
        try {
            assertTrue(awaitUiState(() -> !editor.stagedStartupPending));
            FileBuffer buffer = onEdt(editor::getCurrentBuffer);
            onEdt(() -> {
                editor.setSize(1600, 900); editor.configManager.set("zen.mode.width", "40");
                editor.splitWindow(true); editor.commandHandler.execute("focus on"); editor.validate(); return null;
            });
            assertTrue(awaitUiState(() -> editor.editorPanes.stream().allMatch(pane -> {
                int desired = 40 * pane.getTextArea().getFontMetrics(pane.getTextArea().getFont()).charWidth('M');
                int width = pane.getScrollPane().getViewport().getExtentSize().width;
                return width > 100 && width <= desired + 2;
            })));
            onEdt(() -> {
                for (EditorPane pane : editor.editorPanes) assertSame(buffer.getDocument(), pane.getTextArea().getDocument());
                editor.activateEditorPane(editor.editorPanes.getFirst()); editor.writingArea.setCaretPosition(10);
                editor.splitWindow(false); EditorPane added = editor.getActivePane();
                editor.closeActiveWindow(); assertFalse(editor.editorPanes.contains(added));
                EditorPane active = editor.getActivePane(); editor.writingArea.select(2, 12);
                editor.commandHandler.execute("focus off");
                assertSame(active, editor.getActivePane()); assertEquals(2, editor.writingArea.getSelectionStart());
                assertEquals(12, editor.writingArea.getSelectionEnd()); assertEquals(2, editor.editorPanes.size());
                assertFalse(buffer.getUndoManager().canUndo()); assertFalse(buffer.isModified()); return null;
            });
        } finally { disposeEditor(editor); }
    }

    @Test
    void centeredWritingPreservesBordersWrapAndUnicodeHitTestingAcrossResizes() throws Exception {
        assumeSwingAvailable();
        String content = "# Héading 😀\ne\u0301 emoji 😀\t中文\n" + "long prose ".repeat(80) + "\n";
        Texteditor editor = createEditor(tempDir.resolve("center-geometry-home"), Files.writeString(tempDir.resolve("center-geometry.md"), content));
        try {
            assertTrue(awaitUiState(() -> !editor.stagedStartupPending));
            FileBuffer buffer = onEdt(editor::getCurrentBuffer);
            var original = onEdt(() -> editor.getActivePane().getScrollPane().getBorder());
            onEdt(() -> { editor.setSize(1400, 800); editor.configManager.set("zen.mode.width", "40");
                editor.commandHandler.execute("center on"); editor.validate(); return null; });
            assertTrue(awaitUiState(() -> editor.getActivePane().getScrollPane().getInsets().left > 0));
            for (boolean wrap : new boolean[]{false, true}) {
                onEdt(() -> {
                    editor.writingArea.setLineWrap(wrap); editor.writingArea.setWrapStyleWord(wrap);
                    editor.writingArea.setFont(editor.writingArea.getFont().deriveFont(18f)); editor.updateZenModeLayout();
                    editor.validate();
                    for (int offset : new int[]{0, 2, content.indexOf("emoji"), content.indexOf("中文")}) {
                        var box = editor.writingArea.modelToView2D(offset);
                        assertNotNull(box);
                        assertEquals(offset, editor.writingArea.viewToModel2D(new java.awt.geom.Point2D.Double(box.getX() + .1, box.getY() + box.getHeight() / 2)));
                    }
                    editor.writingArea.select(2, 12); return null;
                });
            }
            onEdt(() -> { editor.setSize(320, 700); editor.validate(); editor.updateZenModeLayout(); return null; });
            assertTrue(awaitUiState(() -> {
                var scroll = editor.getActivePane().getScrollPane();
                int desired = 40 * editor.writingArea.getFontMetrics(editor.writingArea.getFont()).charWidth('M');
                int visible = scroll.getViewport().getExtentSize().width;
                return visible > 0 && visible <= Math.min(scroll.getWidth(), desired) + 2
                    && (scroll.getWidth() >= desired || scroll.getInsets().left == 0);
            }), onEdt(() -> "pane=" + editor.getActivePane().getScrollPane().getSize() + " font=" + editor.writingArea.getFont()
                + " viewport=" + editor.getActivePane().getScrollPane().getViewport().getExtentSize()));
            onEdt(() -> {
                editor.commandHandler.execute("center off"); assertSame(original, editor.getActivePane().getScrollPane().getBorder());
                assertTrue(editor.writingArea.getLineWrap()); assertEquals(content, buffer.getContent());
                assertEquals(2, editor.writingArea.getSelectionStart()); assertEquals(12, editor.writingArea.getSelectionEnd());
                assertFalse(buffer.isModified()); assertFalse(buffer.getUndoManager().canUndo()); return null;
            });
        } finally { disposeEditor(editor); }
    }

    @Test
    void focusRestoresIndependentCenterAndRespectsChangedTabPreference() throws Exception {
        assumeSwingAvailable();
        Texteditor editor = createEditor(tempDir.resolve("focus-center-home"), Files.writeString(tempDir.resolve("focus-center.md"), "body\n"));
        try {
            assertTrue(awaitUiState(() -> !editor.stagedStartupPending));
            onEdt(() -> {
                editor.commandHandler.execute("center on"); editor.commandHandler.execute("focus on");
                editor.commandHandler.execute("center off"); editor.configManager.set("ui.open_editors.visible", "false");
                editor.commandHandler.execute("focus off");
                assertTrue(editor.focusModeController.isCenteredWriting());
                assertFalse(editor.openEditorsBar.isVisible());
                editor.commandHandler.execute("center off");
                assertTrue(editor.workbenchHeader.isVisible()); return null;
            });
        } finally { disposeEditor(editor); }
    }

    @Test
    void focusRestoresHiddenTreeAndDoesNotResurrectClosedPanes() throws Exception {
        assumeSwingAvailable();
        Texteditor editor = createEditor(tempDir.resolve("focus-tree-home"), Files.writeString(tempDir.resolve("focus-tree.md"), "body\n"));
        try {
            assertTrue(awaitUiState(() -> !editor.stagedStartupPending));
            onEdt(() -> {
                EditorPane source = editor.getActivePane(); editor.splitWindow(true);
                EditorPane sidebar = editor.getActivePane(); editor.treePane = sidebar;
                sidebar.setHiddenByFocusMode(true); editor.activateEditorPane(source); editor.renderWindowLayout();
                editor.commandHandler.execute("focus on"); editor.commandHandler.execute("focus off");
                assertTrue(sidebar.isHiddenByFocusMode());
                sidebar.setHiddenByFocusMode(false); editor.renderWindowLayout(); editor.commandHandler.execute("focus on");
                editor.paneBufferController.closePane(sidebar);
                editor.commandHandler.execute("focus off"); assertEquals(1, editor.editorPanes.size());
                assertFalse(editor.editorPanes.contains(sidebar)); return null;
            });
        } finally { disposeEditor(editor); }
    }

    @Test
    void focusPreservesDockSizeAndSessionVisibilityWhileTemporaryToolsCanOpen() throws Exception {
        assumeSwingAvailable();
        Texteditor editor = createEditor(tempDir.resolve("focus-dock-home"), Files.writeString(tempDir.resolve("focus-dock.md"), "body\n"));
        try {
            assertTrue(awaitUiState(() -> !editor.stagedStartupPending));
            onEdt(() -> { editor.showToolWindow(); editor.validate(); editor.editorToolSplit.setDividerLocation(350); editor.validate(); return null; });
            int height = onEdt(() -> editor.toolWindowHost.getHeight());
            onEdt(() -> {
                editor.commandHandler.execute("focus on"); assertFalse(editor.toolWindowHost.isVisible());
                assertEquals(true, editor.focusModeController.dockStateForSession().get("visible"));
                editor.commandHandler.execute("focus off"); editor.validate(); return null;
            });
            assertTrue(awaitUiState(() -> Math.abs(editor.toolWindowHost.getHeight() - height) <= 2));
            onEdt(() -> {
                editor.hideToolWindow(); editor.commandHandler.execute("focus on"); editor.showToolWindow();
                assertTrue(editor.toolWindowHost.isVisible()); editor.commandHandler.execute("focus off");
                assertFalse(editor.toolWindowHost.isVisible()); return null;
            });
        } finally { disposeEditor(editor); }
    }

    @Test
    void workspaceChangeAndDisposalEndFocusWithoutRetainingOldPresentation() throws Exception {
        assumeSwingAvailable();
        Path first = Files.createDirectories(tempDir.resolve("focus-project-a"));
        Path next = Files.createDirectories(tempDir.resolve("focus-project-b"));
        Texteditor editor = createEditor(tempDir.resolve("focus-workspace-home"), Files.writeString(tempDir.resolve("focus-workspace.md"), "body\n"));
        try {
            assertTrue(awaitUiState(() -> !editor.stagedStartupPending));
            onEdt(() -> {
                editor.workspaceController.addDirectory(first.toFile(), true);
                editor.activateEditorPane(editor.editorPanes.stream().filter(pane -> pane != editor.treePane).findFirst().orElseThrow());
                editor.commandHandler.execute("focus on"); assertTrue(editor.goyoModeEnabled);
                editor.workspaceController.addDirectory(next.toFile(), true);
                assertFalse(editor.goyoModeEnabled); assertFalse(editor.focusModeController.isCenteredWriting());
                assertEquals(next.toRealPath().toFile(), editor.treeRoot);
                editor.activateEditorPane(editor.editorPanes.stream().filter(pane -> pane != editor.treePane).findFirst().orElseThrow());
                editor.commandHandler.execute("focus on"); return null;
            });
        } finally { disposeEditor(editor); }
        assertTrue(awaitUiState(() -> {
            var state = FocusModeController.class.getDeclaredField("focusState"); state.setAccessible(true);
            var borders = FocusModeController.class.getDeclaredField("centeredBorders"); borders.setAccessible(true);
            return state.get(editor.focusModeController) == null && ((java.util.Map<?, ?>) borders.get(editor.focusModeController)).isEmpty();
        }));
    }

    @Test
    void focusRemembersDraggedDividerAndKeepsWindowState() throws Exception {
        assumeSwingAvailable();
        Texteditor editor = createEditor(tempDir.resolve("focus-divider-home"), Files.writeString(tempDir.resolve("focus-divider.md"), "body\n"));
        try {
            assertTrue(awaitUiState(() -> !editor.stagedStartupPending));
            onEdt(() -> { editor.setSize(1500, 900); editor.splitWindow(true); editor.validate(); return null; });
            assertTrue(awaitUiState(() -> editor.renderedLayoutComponent instanceof javax.swing.JSplitPane split && split.getWidth() > 1000));
            onEdt(() -> { ((javax.swing.JSplitPane) editor.renderedLayoutComponent).setDividerLocation(520); return null; });
            assertTrue(awaitUiState(() -> ((javax.swing.JSplitPane) editor.renderedLayoutComponent).getDividerLocation() == 520));
            onEdt(() -> {
                int windowState = editor.getExtendedState(); editor.commandHandler.execute("focus on"); editor.commandHandler.execute("focus off");
                assertEquals(windowState, editor.getExtendedState()); editor.validate(); return null;
            });
            assertTrue(awaitUiState(() -> Math.abs(((javax.swing.JSplitPane) editor.renderedLayoutComponent).getDividerLocation() - 520) <= 2));
        } finally { disposeEditor(editor); }
    }

    @Test
    void focusSessionSaveUsesPriorDockAndSessionReplacementEndsPresentation() throws Exception {
        assumeSwingAvailable();
        Path file = Files.writeString(tempDir.resolve("focus-session.md"), "body\n");
        Texteditor editor = createEditor(tempDir.resolve("focus-session-home"), file);
        try {
            assertTrue(awaitUiState(() -> !editor.stagedStartupPending));
            onEdt(() -> {
                editor.showToolWindow(); editor.commandHandler.execute("focus on");
                assertTrue(editor.sessionConfigController.saveSession("focus-state").startsWith("Session saved:"));
                var payload = MiniJson.asObject(MiniJson.parse(Files.readString(editor.sessionConfigController.resolveSessionFile("focus-state").toPath())));
                assertEquals(true, MiniJson.asObject(payload.get("toolDock")).get("visible"));
                assertFalse(editor.toolWindowHost.isVisible());
                assertTrue(editor.sessionConfigController.loadSession("focus-state", true).startsWith("Restored session:"));
                assertFalse(editor.goyoModeEnabled); assertFalse(editor.focusModeController.isCenteredWriting());
                assertEquals("body\n", editor.getCurrentBuffer().getContent()); return null;
            });
        } finally { disposeEditor(editor); }
    }

    @Test
    void focusLeavesUserEditsAndUndoIntactAndThemeChangesDoNotStackCenterBorders() throws Exception {
        assumeSwingAvailable();
        Texteditor editor = createEditor(tempDir.resolve("focus-edit-home"), Files.writeString(tempDir.resolve("focus-edit.md"), "body\n"));
        try {
            assertTrue(awaitUiState(() -> !editor.stagedStartupPending));
            onEdt(() -> {
                editor.commandHandler.execute("focus on"); editor.writingArea.append("draft");
                editor.setThemeFromCommand("shed-dark"); editor.setThemeFromCommand("shed-light");
                editor.commandHandler.execute("focus off");
                assertEquals("body\ndraft", editor.getCurrentBuffer().getContent()); assertTrue(editor.getCurrentBuffer().isModified());
                editor.getCurrentBuffer().getUndoManager().undo(); assertEquals("body\n", editor.getCurrentBuffer().getContent());
                editor.getCurrentBuffer().getUndoManager().redo(); assertEquals("body\ndraft", editor.getCurrentBuffer().getContent());
                assertFalse(editor.focusModeController.isCenteredWriting());
                assertTrue(editor.getActivePane().getScrollPane().getInsets().left == 0); return null;
            });
        } finally { disposeEditor(editor); }
    }

    @Test
    void focusKeepsLargeWrappedSourcesAtNormalWidthWithoutChangingSourceOrWrap() throws Exception {
        assumeSwingAvailable();
        Path file = Files.writeString(tempDir.resolve("focus-large-wrap.md"), "wrapped prose\n".repeat(12000));
        Texteditor editor = createEditor(tempDir.resolve("focus-large-wrap-home"), file);
        try {
            assertTrue(awaitUiState(() -> !editor.stagedStartupPending));
            onEdt(() -> {
                var buffer = editor.getCurrentBuffer(); long revision = buffer.documentRevision();
                var area = editor.writingArea; var document = area.getDocument();
                area.setLineWrap(true); area.setWrapStyleWord(true);
                var border = editor.getActivePane().getScrollPane().getBorder();
                boolean headerVisible = editor.workbenchHeader.isVisible();
                assertTrue(editor.commandHandler.execute("focus on").contains("large wrapped panes"));
                assertFalse(editor.goyoModeEnabled); assertEquals(headerVisible, editor.workbenchHeader.isVisible());
                assertTrue(editor.commandHandler.execute("center on").contains("large wrapped panes"));
                assertFalse(editor.focusModeController.isCenteredWriting());
                assertSame(border, editor.getActivePane().getScrollPane().getBorder());
                assertSame(document, area.getDocument()); assertEquals(revision, buffer.documentRevision());
                assertTrue(area.getLineWrap()); assertFalse(buffer.getUndoManager().canUndo());
                editor.commandHandler.execute("focus off"); assertSame(border, editor.getActivePane().getScrollPane().getBorder()); return null;
            });
        } finally { disposeEditor(editor); }
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

    @Test
    void workbenchNavigationUsesRealDocumentsAndKeepsActionsReachableAtEnlargedScale() throws Exception {
        assumeSwingAvailable();
        Path home = Files.createDirectories(tempDir.resolve("home-navigation"));
        Path a = Files.writeString(tempDir.resolve("navigation-a.txt"), "first\n");
        Path b = Files.writeString(tempDir.resolve("navigation-b.txt"), "second\n");
        Texteditor editor = createEditor(home, a, b);
        try {
            assertTrue(awaitUiState(() -> !editor.stagedStartupPending));
            onEdt(() -> {
                assertNotNull(editor.workbenchHeader);
                assertEquals(5, descendants(editor.workbenchHeader, javax.swing.JButton.class).stream()
                    .filter(button -> button.getIcon() != null).count());
                var document = editor.documents.documents().getFirst();
                editor.documents.activate(document.id());
                editor.documents.activeSurface().replace(0, 0, "edited ");
                editor.documents.activeSurface().undo();
                assertEquals("first\n", editor.writingArea.getText());
                editor.settingsApplication.update("ui.zoom", "1.5");
                editor.setSize(760, 750); editor.validate();
                assertTrue(editor.workbenchHeader.isShowing());
                Rectangle visible = new Rectangle(editor.workbenchHeader.getSize());
                for (var button : descendants(editor.workbenchHeader, javax.swing.JButton.class)) {
                    Rectangle bounds = SwingUtilities.convertRectangle(button.getParent(), button.getBounds(), editor.workbenchHeader);
                    assertTrue(visible.contains(bounds), button.getAccessibleContext().getAccessibleName() + " must remain in the navigation row: " + bounds);
                    assertTrue(bounds.height >= 60, "Controls must scale with UI zoom");
                }
                var location = descendants(editor.workbenchHeader, javax.swing.JLabel.class).stream()
                    .filter(label -> "workbench.location".equals(label.getName())).findFirst().orElseThrow();
                assertTrue(location.getText().contains("navigation-a.txt"));
                editor.settingsApplication.update("ui.open_editors.visible", "false");
                assertFalse(editor.openEditorsBar.isVisible());
                assertTrue(editor.workbenchHeader.isVisible(), "Tab visibility must not remove workspace navigation");
                return null;
            });
        } finally { disposeEditor(editor); }
    }

    @Test
    void toolsPreserveTestTreeAndSortedProblemSelectionOnBackgroundRefresh() throws Exception {
        assumeSwingAvailable();
        Path home = Files.createDirectories(tempDir.resolve("home-tool-refresh"));
        Path root = Files.createDirectories(tempDir.resolve("tool-refresh-project"));
        Path tests = Files.createDirectories(root.resolve("src/test/java/demo"));
        Files.writeString(root.resolve("pom.xml"), "<project/>\n");
        Files.writeString(root.resolve(".shedtests"), "schema_version = 1\n[[adapter]]\nid = \"maven\"\n");
        Files.writeString(tests.resolve("AlphaTest.java"), "package demo; class AlphaTest { @Test void works() {} }\n");
        Files.writeString(tests.resolve("BetaTest.java"), "package demo; class BetaTest { @Test void works() {} }\n");
        Path source = Files.writeString(root.resolve("source.txt"), "one\ntwo\n");
        Texteditor editor = createEditor(home, source);
        try {
            assertTrue(awaitUiState(() -> !editor.stagedStartupPending));
            onEdt(() -> { editor.workspaceController.addFolder(root, false); editor.testController.selectRoot(root); editor.handleTestCommand("refresh"); editor.showToolWindow(ToolWindowHost.Tab.TESTS); return null; });
            assertTrue(awaitTestDiscovery(editor, root, "demo.BetaTest#works"));
            onEdt(() -> {
                var tree = descendants(editor.toolWindowHost, javax.swing.JTree.class).stream()
                    .filter(value -> "Tests".equals(value.getAccessibleContext().getAccessibleName())).findFirst().orElseThrow();
                var model = tree.getModel();
                var treeRoot = (javax.swing.tree.DefaultMutableTreeNode) model.getRoot();
                javax.swing.tree.TreePath collapsed = null, selected = null;
                var nodes = treeRoot.preorderEnumeration();
                while (nodes.hasMoreElements()) {
                    var node = (javax.swing.tree.DefaultMutableTreeNode) nodes.nextElement();
                    if (!node.isLeaf() && node.toString().contains("AlphaTest")) collapsed = new javax.swing.tree.TreePath(node.getPath());
                    if (node.isLeaf() && node.getParent().toString().contains("BetaTest")) selected = new javax.swing.tree.TreePath(node.getPath());
                }
                assertNotNull(collapsed); assertNotNull(selected);
                tree.collapsePath(collapsed); tree.setSelectionPath(selected);
                for (int iteration = 0; iteration < 3; iteration++) editor.toolWindowHost.refresh(ToolWindowHost.Tab.TESTS);
                assertSame(model, tree.getModel(), "unchanged background refresh must not rebuild the tree");
                assertEquals(selected, tree.getSelectionPath()); assertFalse(tree.isExpanded(collapsed));
                editor.problemsController.recordQuickfixEntries(List.of(
                    new QuickfixService.Entry(source.toString(), 1, 1, "selected diagnostic", "refresh"),
                    new QuickfixService.Entry(source.toString(), 2, 1, "another diagnostic", "refresh")));
                editor.showToolWindow(ToolWindowHost.Tab.PROBLEMS);
                var table = descendants(editor.toolWindowHost.problemsPanel().component(), javax.swing.JTable.class).getFirst();
                table.getRowSorter().toggleSortOrder(4);
                int row = java.util.stream.IntStream.range(0, table.getRowCount()).filter(i -> "selected diagnostic".equals(table.getValueAt(i, 4))).findFirst().orElseThrow();
                table.setRowSelectionInterval(row, row);
                editor.problemsController.recordQuickfixEntries(List.of(new QuickfixService.Entry(source.toString(), 2, 2, "new diagnostic", "extra")));
                editor.toolWindowHost.refresh(ToolWindowHost.Tab.PROBLEMS);
                assertTrue(table.getSelectedRow() >= 0);
                assertEquals("selected diagnostic", table.getValueAt(table.getSelectedRow(), 4));
                return null;
            });
        } finally { disposeEditor(editor); }
    }

    @Test
    void panelTaskCompletionAndCancellationPreserveTheEditingDocument() throws Exception {
        assumeSwingAvailable();
        assumeFalse(System.getProperty("os.name").startsWith("Windows"), "POSIX task fixture");
        Path home = Files.createDirectories(tempDir.resolve("home-panel-task"));
        Path root = Files.createDirectories(tempDir.resolve("panel-task-project"));
        Path source = Files.writeString(root.resolve("source.txt"), "editing stays here\n");
        Path release = root.resolve("release");
        Files.writeString(root.resolve(".shedtasks"), "schema_version = 1\n[task.check]\ncommand = \"while [ ! -e release ]; do sleep 0.05; done; printf 'task output'; exit 2\"\npresentation = \"always\"\n");
        Texteditor editor = createEditor(home, source);
        try {
            assertTrue(awaitUiState(() -> !editor.stagedStartupPending));
            int id = onEdt(() -> {
                editor.workspaceController.addFolder(root, true);
                var loaded = editor.taskService.loadWorkspaceTasks(root.toFile());
                assertTrue(loaded.isValid(), loaded.diagnostics().toString());
                editor.showToolWindow(ToolWindowHost.Tab.TASKS);
                var list = descendants(editor.toolWindowHost, javax.swing.JList.class).stream()
                    .filter(value -> "Workspace tasks".equals(value.getAccessibleContext().getAccessibleName())).findFirst().orElseThrow();
                list.setSelectedValue("check", true);
                descendants(editor.toolWindowHost, javax.swing.JButton.class).stream().filter(b -> "Run".equals(b.getText())).findFirst().orElseThrow().doClick();
                int job = editor.asyncJobService.list().stream().filter(value -> value.getDescription().startsWith("task ")).mapToInt(AsyncJobService.JobSnapshot::getId).max().orElseThrow();
                editor.hideToolWindow(); editor.requestActivePaneFocus();
                editor.documents.activeSurface().replace(0, 0, "unsaved ");
                return job;
            });
            Files.writeString(release, "finish");
            assertTrue(awaitUiState(() -> Integer.valueOf(2).equals(editor.jobQuickfixController.taskExitCodeForPanel(id))));
            onEdt(() -> {
                assertEquals(source.toFile(), editor.getCurrentBuffer().getFile());
                assertEquals("unsaved editing stays here\n", editor.getCurrentBuffer().getContent());
                editor.showToolWindow(ToolWindowHost.Tab.TASKS);
                var table = descendants(editor.toolWindowHost, javax.swing.JTable.class).stream()
                    .filter(value -> "Task jobs".equals(value.getAccessibleContext().getAccessibleName())).findFirst().orElseThrow();
                int row = java.util.stream.IntStream.range(0, table.getRowCount()).filter(i -> Integer.valueOf(id).equals(table.getValueAt(i, 0))).findFirst().orElseThrow();
                table.setRowSelectionInterval(row, row);
                assertEquals("failed · exit 2", table.getValueAt(row, 1));
                var output = descendants(editor.toolWindowHost, javax.swing.JTextArea.class).stream()
                    .filter(value -> "Task output".equals(value.getAccessibleContext().getAccessibleName())).findFirst().orElseThrow();
                assertTrue(output.getText().contains("task output"));
                for (String zoom : List.of("1.0", "1.5")) {
                    editor.settingsApplication.update("ui.zoom", zoom);
                    editor.setSize(zoom.equals("1.5") ? 760 : 1100, 780); editor.validate();
                    editor.editorToolSplit.setDividerLocation(editor.editorToolSplit.getHeight() - 210 - editor.editorToolSplit.getDividerSize());
                    editor.validate();
                    assertEquals(editor.resolveUiFont().getSize(), output.getFont().getSize(), "Task output must follow interface text scaling");
                    Rectangle jobCell = table.getCellRect(row, 1, true);
                    ToolViewport.reveal(table, jobCell);
                    assertTrue(table.getVisibleRect().contains(jobCell), "Selected task status must remain reachable at " + zoom);
                    int readableHeight = output.getInsets().top + output.getFontMetrics(output.getFont()).getHeight() * 3;
                    Rectangle readableOutput = new Rectangle(0, 0, Math.min(100, output.getWidth()), readableHeight);
                    ToolViewport.reveal(output, readableOutput);
                    assertTrue(output.getVisibleRect().contains(readableOutput), "Selected task output must expose readable rows at " + zoom);
                }
                editor.settingsApplication.update("ui.zoom", "1.0");
                output.setCaretPosition(7); output.moveCaretPosition(2);
                editor.toolWindowHost.refresh(ToolWindowHost.Tab.TASKS);
                assertEquals(7, output.getCaret().getMark()); assertEquals(2, output.getCaret().getDot());
                var cancel = descendants(editor.toolWindowHost, javax.swing.JButton.class).stream().filter(b -> "tasks.cancel".equals(b.getName())).findFirst().orElseThrow();
                assertFalse(cancel.isEnabled());
                return null;
            });
            Files.delete(release);
            int cancelled = onEdt(() -> {
                descendants(editor.toolWindowHost, javax.swing.JButton.class).stream().filter(b -> "Run".equals(b.getText())).findFirst().orElseThrow().doClick();
                int job = editor.asyncJobService.list().stream().filter(value -> value.getDescription().startsWith("task ")).mapToInt(AsyncJobService.JobSnapshot::getId).max().orElseThrow();
                var cancel = descendants(editor.toolWindowHost, javax.swing.JButton.class).stream().filter(b -> "tasks.cancel".equals(b.getName())).findFirst().orElseThrow();
                assertTrue(cancel.isEnabled()); cancel.doClick(); assertFalse(cancel.isEnabled());
                return job;
            });
            assertTrue(awaitUiState(() -> editor.asyncJobService.get(cancelled).getStatus() == AsyncJobService.Status.CANCELLED
                && descendants(editor.toolWindowHost, javax.swing.JTextArea.class).stream().anyMatch(area -> "Task output".equals(area.getAccessibleContext().getAccessibleName()) && area.getText().contains("Task cancelled"))));
            onEdt(() -> {
                assertEquals(source.toFile(), editor.getCurrentBuffer().getFile());
                assertEquals("unsaved editing stays here\n", editor.getCurrentBuffer().getContent());
                return null;
            });
        } finally { disposeEditor(editor); }
    }

    @Test
    void toolLayoutRetainsManualSizeAndRestoresDetachedPlacementFromSession() throws Exception {
        assumeSwingAvailable();
        Path home = Files.createDirectories(tempDir.resolve("home-tool-session"));
        Path root = Files.createDirectories(tempDir.resolve("tool-session-project"));
        Path source = Files.writeString(root.resolve("source.txt"), "source\n");
        Path session = tempDir.resolve("tool-layout.json");
        Texteditor editor = createEditor(home, source);
        try {
            assertTrue(awaitUiState(() -> !editor.stagedStartupPending));
            onEdt(() -> { editor.setSize(1100, 820); editor.workspaceController.addFolder(root, true); editor.showToolWindow(ToolWindowHost.Tab.TASKS); return null; });
            flushEdt();
            int dockHeight = onEdt(() -> {
                editor.editorToolSplit.setDividerLocation(300); editor.validate();
                return editor.editorToolSplit.getHeight() - editor.editorToolSplit.getDividerLocation() - editor.editorToolSplit.getDividerSize();
            });
            onEdt(() -> { editor.hideToolWindow(); return null; }); flushEdt();
            onEdt(() -> {
                editor.showToolWindow(ToolWindowHost.Tab.DEBUG);
                editor.validate();
                assertEquals(dockHeight, editor.editorToolSplit.getHeight() - editor.editorToolSplit.getDividerLocation() - editor.editorToolSplit.getDividerSize(), 2,
                    "reopening another tool must preserve dock height even when footer layout changes the divider coordinate");
                editor.showToolWindow(ToolWindowHost.Tab.TASKS);
                descendants(editor.toolWindowHost, javax.swing.JButton.class).stream()
                    .filter(b -> "Detach selected tool".equals(b.getAccessibleContext().getAccessibleName())).findFirst().orElseThrow().doClick();
                return null;
            });
            assertTrue(awaitUiState(() -> Arrays.stream(editor.getOwnedWindows()).anyMatch(w -> w instanceof javax.swing.JDialog d && d.isShowing() && "Tasks".equals(d.getTitle()))));
            onEdt(() -> {
                var floating = Arrays.stream(editor.getOwnedWindows()).filter(w -> w instanceof javax.swing.JDialog d && d.isShowing() && "Tasks".equals(d.getTitle())).findFirst().orElseThrow();
                floating.setBounds(75, 80, 750, 450);
                editor.settingsApplication.update("ui.zoom", "1.5");
                assertEquals(new Rectangle(75, 80, 750, 450), floating.getBounds(), "Appearance updates must preserve user-owned tool geometry");
                editor.settingsApplication.update("ui.zoom", "1.0");
                assertEquals(new Rectangle(75, 80, 750, 450), floating.getBounds());
                var dock = editor.toolWindowHost.captureDockState();
                editor.toolWindowHost.restoreWorkbench(dock); // Same placement model must also be safe.
                assertTrue(editor.sessionConfigController.saveSession(session.toString()).startsWith("Session saved:"));
                return null;
            });
            onEdt(() -> { editor.toolWindowHost.reattachAll(); return null; }); flushEdt();
            onEdt(() -> {
                assertEquals(WorkbenchPlacementState.Mode.DOCKED, editor.workbenchPlacementState.get(root.toRealPath().toString(), WorkbenchLayout.SurfaceType.TASKS, "panel").mode());
                assertTrue(editor.sessionConfigController.loadSession(session.toString(), true).startsWith("Restored session:"));
                return null;
            });
            assertTrue(awaitUiState(() -> Arrays.stream(editor.getOwnedWindows()).anyMatch(w -> w instanceof javax.swing.JDialog d && d.isShowing() && "Tasks".equals(d.getTitle()))));
            onEdt(() -> {
                var floating = Arrays.stream(editor.getOwnedWindows()).filter(w -> w instanceof javax.swing.JDialog d && d.isShowing() && "Tasks".equals(d.getTitle())).findFirst().orElseThrow();
                assertEquals(new Rectangle(75, 80, 750, 450), floating.getBounds());
                assertEquals(WorkbenchPlacementState.Mode.DETACHED, editor.workbenchPlacementState.get(root.toRealPath().toString(), WorkbenchLayout.SurfaceType.TASKS, "panel").mode());
                floating.dispose(); return null;
            });
            assertTrue(awaitUiState(() -> editor.workbenchPlacementState.get(root.toRealPath().toString(), WorkbenchLayout.SurfaceType.TASKS, "panel").mode() == WorkbenchPlacementState.Mode.DOCKED));
        } finally { disposeEditor(editor); }
    }

    @Test
    void productionReviewRequiresExplicitApplyAndRetainsUnsavedEditorChanges() throws Exception {
        assumeSwingAvailable();
        Path home = Files.createDirectories(tempDir.resolve("home-production-review"));
        Path root = Files.createDirectories(tempDir.resolve("production-review-project"));
        Path source = Files.writeString(root.resolve("source.txt"), "original\n");
        Texteditor editor = createEditor(home, source);
        try {
            assertTrue(awaitUiState(() -> !editor.stagedStartupPending));
            onEdt(() -> { editor.workspaceController.addFolder(root, true); return null; });
            var scope = new AgentReviewPanel.Scope(root, Path.of(editor.configManager.getSessionDirectory()), editor.workspaceDocuments());
            var queries = new WorkspaceQueryService(root, AgentPolicy.readOnly(), scope.documents());
            scope.service().propose("review fixture", "task", "turn", List.of(new WorkspaceProposalService.Input("source.txt", queries.snapshot(queries.workspaceId(), "source.txt").version(), "reviewed\n")));
            onEdt(() -> { editor.toolWindowHost.showAgentReview(); return null; });
            assertTrue(awaitUiState(() -> descendants(editor.toolWindowHost, javax.swing.JButton.class).stream().anyMatch(b -> "agent.apply".equals(b.getName()) && b.isEnabled())));
            assertEquals("original\n", Files.readString(source));
            onEdt(() -> {
                assertEquals("original\n", editor.getCurrentBuffer().getContent());
                var state = descendants(editor.toolWindowHost, javax.swing.JLabel.class).stream().filter(label -> "agent.reviewState".equals(label.getName())).findFirst().orElseThrow();
                assertTrue(state.getText().contains("Review"));
                var apply = descendants(editor.toolWindowHost, javax.swing.JButton.class).stream().filter(b -> "agent.apply".equals(b.getName())).findFirst().orElseThrow();
                editor.settingsApplication.update("ui.zoom", "1.5"); editor.setSize(760, 780); editor.validate();
                for (String name : List.of("agent.apply", "agent.reject")) {
                    var decision = descendants(editor.toolWindowHost, javax.swing.JButton.class).stream().filter(b -> name.equals(b.getName())).findFirst().orElseThrow();
                    Rectangle control = new Rectangle(0, 0, decision.getWidth(), decision.getHeight());
                    ToolViewport.reveal(decision, control);
                    Rectangle bounds = javax.swing.SwingUtilities.convertRectangle(decision.getParent(), decision.getBounds(), editor.getRootPane());
                    assertTrue(decision.getVisibleRect().contains(control), name + " must be fully visible after scrolling at 150% scale");
                    assertTrue(editor.getRootPane().getVisibleRect().contains(bounds), name + " must remain reachable at narrow width and 150% scale: " + bounds);
                }
                editor.settingsApplication.update("ui.zoom", "1.0"); editor.setSize(1100, 820); editor.validate();
                assertTrue(apply.isShowing()); apply.doClick(); return null;
            });
            assertTrue(awaitUiState(() -> editor.getCurrentBuffer().getContent().equals("reviewed\n") && editor.getCurrentBuffer().isModified()));
            assertEquals("original\n", Files.readString(source), "Apply to an open document must preserve the existing unsaved-apply policy");
            assertTrue(awaitUiState(() -> descendants(editor.toolWindowHost, javax.swing.JButton.class).stream()
                .anyMatch(b -> "agent.undo".equals(b.getName()) && b.isEnabled())));
            onEdt(() -> {
                var undo = descendants(editor.toolWindowHost, javax.swing.JButton.class).stream().filter(b -> "agent.undo".equals(b.getName())).findFirst().orElseThrow();
                assertTrue(undo.isEnabled()); undo.doClick(); return null;
            });
            assertTrue(awaitUiState(() -> editor.getCurrentBuffer().getContent().equals("original\n")));
        } finally { disposeEditor(editor); }
    }

    @Test
    void settingsValidationKeepsDraftsInlineAndModifiedFilterCanResetDefaults() throws Exception {
        assumeSwingAvailable();
        Path home = Files.createDirectories(tempDir.resolve("home-settings-validation"));
        Texteditor editor = createEditor(home, Files.writeString(tempDir.resolve("settings-validation.txt"), "text\n"));
        SettingsEditorDialog[] shown = new SettingsEditorDialog[1];
        try {
            assertTrue(awaitUiState(() -> !editor.stagedStartupPending));
            onEdt(() -> {
                SettingsEditorDialog.showFor(editor);
                shown[0] = Arrays.stream(editor.getOwnedWindows()).filter(SettingsEditorDialog.class::isInstance)
                    .map(SettingsEditorDialog.class::cast).filter(Window::isShowing).findFirst().orElseThrow();
                var query = descendants(shown[0], javax.swing.JTextField.class).stream()
                    .filter(field -> "Search settings".equals(field.getAccessibleContext().getAccessibleName())).findFirst().orElseThrow();
                query.setText("tab.size"); return null;
            });
            assertTrue(awaitUiState(() -> descendants(shown[0], javax.swing.JTextField.class).stream()
                .filter(field -> field.getClientProperty("shed.setting.key") != null).count() == 1));
            int original = editor.configManager.getTabSize();
            onEdt(() -> {
                var field = descendants(shown[0], javax.swing.JTextField.class).stream()
                    .filter(input -> "tab.size".equals(input.getClientProperty("shed.setting.key"))).findFirst().orElseThrow();
                field.setText("not-a-number"); field.postActionEvent();
                assertEquals(original, editor.configManager.getTabSize());
                assertEquals("not-a-number", field.getText());
                assertEquals(Boolean.TRUE, field.getClientProperty("shed.invalid"));
                assertTrue(descendants(shown[0], javax.swing.JButton.class).stream()
                    .anyMatch(button -> "settings.reset.tab.size".equals(button.getName()) && button.isEnabled()), "Reset must recover an invalid draft even when the saved value is already default");
                var feedback = descendants(shown[0], javax.swing.JTextArea.class).stream()
                    .filter(area -> "settings.feedback".equals(area.getName())).findFirst().orElseThrow();
                assertTrue(feedback.getText().contains("Error"));
                assertFalse(Arrays.stream(editor.getOwnedWindows()).filter(javax.swing.JDialog.class::isInstance)
                    .map(javax.swing.JDialog.class::cast).anyMatch(dialog -> dialog.isShowing() && dialog.isModal()), "Validation must stay inline");
                field.setText("7"); field.postActionEvent();
                assertEquals(7, editor.configManager.getTabSize()); return null;
            });
            assertTrue(awaitUiState(() -> descendants(shown[0], javax.swing.JButton.class).stream()
                .anyMatch(button -> "settings.reset.tab.size".equals(button.getName()) && button.isEnabled())));
            onEdt(() -> {
                var reset = descendants(shown[0], javax.swing.JButton.class).stream()
                    .filter(button -> "settings.reset.tab.size".equals(button.getName())).findFirst().orElseThrow();
                reset.doClick();
                var setting = editor.settingsApplication.search("tab.size").stream().filter(item -> item.key().equals("tab.size")).findFirst().orElseThrow();
                assertEquals(Integer.parseInt(setting.defaultValue()), editor.configManager.getTabSize());
                descendants(shown[0], javax.swing.JCheckBox.class).stream()
                    .filter(box -> "settings.modifiedOnly".equals(box.getName())).findFirst().orElseThrow().doClick();
                return null;
            });
            assertTrue(awaitUiState(() -> descendants(shown[0], javax.swing.JTextField.class).stream()
                .noneMatch(field -> "tab.size".equals(field.getClientProperty("shed.setting.key")))));
        } finally {
            if (shown[0] != null) onEdt(() -> { shown[0].dispose(); return null; });
            disposeEditor(editor);
        }
    }

    @Test
    void documentNavigationRetainsFocusAndToolRefreshDoesNotTakeIt() throws Exception {
        assumeSwingAvailable();
        Path home = Files.createDirectories(tempDir.resolve("home-navigation-focus"));
        Path a = Files.writeString(tempDir.resolve("focus-a.txt"), "first\n");
        Path b = Files.writeString(tempDir.resolve("focus-b.txt"), "second\n");
        Texteditor editor = createEditor(home, a, b);
        try {
            assertTrue(awaitUiState(() -> !editor.stagedStartupPending));
            onEdt(() -> {
                if (java.awt.Desktop.isDesktopSupported() && java.awt.Desktop.getDesktop().isSupported(java.awt.Desktop.Action.APP_REQUEST_FOREGROUND)) {
                    java.awt.Desktop.getDesktop().requestForeground(true);
                }
                editor.toFront(); editor.openEditorsBar.focusEditors(); return null;
            });
            var list = onEdt(() -> descendants(editor.openEditorsBar, javax.swing.JList.class).getFirst());
            assumeTrue(awaitUiState(list::isFocusOwner), "Desktop must grant native focus");
            onEdt(() -> {
                int next = list.getSelectedIndex() == 0 ? 1 : 0;
                list.setSelectedIndex(next);
                assertEquals(editor.documents.documents().get(next).id(), editor.documents.activeDocument().id());
                return null;
            });
            assertTrue(awaitUiState(list::isFocusOwner), "Selecting a document must keep focus in keyboard navigation");
            onEdt(() -> {
                var action = list.getActionMap().get(list.getInputMap().get(javax.swing.KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0)));
                action.actionPerformed(new ActionEvent(list, ActionEvent.ACTION_PERFORMED, "Enter"));
                return null;
            });
            assertTrue(awaitUiState(() -> editor.writingArea.isFocusOwner()));
            onEdt(() -> {
                editor.showToolWindow(ToolWindowHost.Tab.PROBLEMS);
                editor.requestActivePaneFocus(); editor.toolWindowHost.refreshActive();
                return null;
            });
            assertTrue(awaitUiState(() -> editor.writingArea.isFocusOwner()), "Background refresh must not steal editor focus");
            onEdt(() -> { editor.toolWindowHost.focus(ToolWindowHost.Tab.PROBLEMS); return null; });
            assertTrue(awaitUiState(() -> descendants(editor.toolWindowHost.problemsPanel().component(), javax.swing.JTextField.class).getFirst().isFocusOwner()));
        } finally { disposeEditor(editor); }
    }

    @Test
    void markdownFoldingPreservesSourceRevisionsAndEventsForAllSections() throws Exception {
        assumeSwingAvailable();
        Path file = Files.writeString(tempDir.resolve("fold.md"), "# Initial\nbody\n");
        Texteditor editor = createEditor(tempDir.resolve("fold-home"), file);
        try {
            onEdt(() -> {
                for (String text : List.of("# Heading\nbody\n", "# Parent\n## Child\nbody\n# Next\n",
                    "# Empty\n# Next\n", "# Final", "# Heading\n ... (2 lines)\noriginal indicator-like text")) {
                    editor.getCurrentBuffer().setContent(text, false);
                    editor.writingArea.setCaretPosition(0);
                    assertMarkdownFoldsUnchanged(editor);
                    editor.writingArea.append(" ordinary edit");
                    assertTrue(editor.getCurrentBuffer().isModified());
                    assertMarkdownFoldsUnchanged(editor);
                }
                return null;
            });
        } finally { disposeEditor(editor); }
    }

    @Test
    void markdownFoldingPreservesSaveRecoveryUndoAndRedo() throws Exception {
        assumeSwingAvailable();
        Path file = tempDir.resolve("fold-persistence.md");
        byte[] original = "\ufeff# Heading\r\nbody \uD83D\uDC69\u200D\uD83D\uDCBB\r\n## Final".getBytes(StandardCharsets.UTF_8);
        Files.write(file, original);
        Texteditor editor = createEditor(tempDir.resolve("fold-save-home"), file);
        try {
            FileBuffer buffer = onEdt(editor::getCurrentBuffer);
            onEdt(() -> { assertMarkdownFoldsUnchanged(editor); return null; });
            FileBuffer.SaveSnapshot save = onEdt(() -> buffer.captureSaveSnapshot(null));
            buffer.writeSaveSnapshot(save);
            onEdt(() -> { buffer.completeSaveSnapshot(save); return null; });
            org.junit.jupiter.api.Assertions.assertArrayEquals(original, Files.readAllBytes(file));
            assertEquals(onEdt(buffer::getContent), new FileBuffer(file.toFile()).getContent());
            String before = onEdt(buffer::getContent);
            onEdt(() -> {
                editor.writingArea.append(" draft");
                assertMarkdownFoldsUnchanged(editor);
                buffer.getUndoManager().undo();
                assertEquals(before, buffer.getContent());
                assertMarkdownFoldsUnchanged(editor);
                buffer.getUndoManager().redo();
                assertEquals(before + " draft", buffer.getContent());
                assertMarkdownFoldsUnchanged(editor);
                editor.persistRecoverySnapshots();
                return null;
            });
            Path recovery = editor.recoveryStoreDir.toPath();
            assertTrue(awaitUiState(() -> {
                try {
                    RecoveryJournal.Journal journal = RecoveryJournal.read(recovery);
                    return journal != null && journal.entries().stream().anyMatch(e -> e.content().equals(before + " draft"));
                } catch (java.io.IOException error) { return false; }
            }));
            RecoveryJournal.Entry entry = RecoveryJournal.read(recovery).entries().stream()
                .filter(e -> e.content().equals(before + " draft")).findFirst().orElseThrow();
            FileBuffer restored = new FileBuffer(file.toFile());
            restored.setContent(entry.content(), true);
            assertTrue(restored.isModified());
            assertEquals(before + " draft", restored.getContent());
            org.junit.jupiter.api.Assertions.assertArrayEquals(original, Files.readAllBytes(file));
        } finally { disposeEditor(editor); }
    }

    @Test
    void markdownFoldingIsIsolatedAcrossBuffersSplitsAndReadOnlyViews() throws Exception {
        assumeSwingAvailable();
        Path a = Files.writeString(tempDir.resolve("fold-a.md"), "# A\nbody A\n");
        Path b = Files.writeString(tempDir.resolve("fold-b.md"), "# B\nbody B\n");
        Texteditor editor = createEditor(tempDir.resolve("fold-isolation-home"), a, b);
        try {
            onEdt(() -> {
                FileBuffer first = editor.buffers.stream().filter(x -> a.toFile().equals(x.getFile())).findFirst().orElseThrow();
                FileBuffer second = editor.getCurrentBuffer();
                editor.loadBufferIntoEditor(first);
                editor.splitWindow(true);
                assertSame(editor.editorPanes.get(0).getTextArea().getDocument(), editor.editorPanes.get(1).getTextArea().getDocument());
                assertMarkdownFoldsUnchanged(editor);
                editor.writingArea.setEditable(false);
                assertMarkdownFoldsUnchanged(editor);
                editor.loadBufferIntoEditor(second);
                assertMarkdownFoldsUnchanged(editor);
                editor.loadBufferIntoEditor(first);
                assertMarkdownFoldsUnchanged(editor);
                assertEquals("# A\nbody A\n", first.getContent());
                assertEquals("# B\nbody B\n", second.getContent());
                assertEquals("Markdown preview opened", editor.commandHandler.execute("mdpreview"));
                EditorPane preview = editor.editorPanes.stream().filter(EditorPane::isMarkdownPreview).findFirst().orElseThrow();
                editor.activateEditorPane(preview);
                assertMarkdownFoldsUnchanged(editor);
                return null;
            });
            Path large = tempDir.resolve("fold-large.md");
            Files.writeString(large, "# Large\n" + "body\n".repeat(1001));
            onEdt(() -> {
                editor.configManager.set("large.file.line.threshold", "1000");
                return null;
            });
            FileBuffer projected = new FileBuffer(large.toFile(), editor.configManager);
            onEdt(() -> {
                assertTrue(projected.isLargeFile());
                // Use a source pane rather than replacing the preview surface.
                editor.activateEditorPane(editor.editorPanes.stream().filter(pane -> !pane.isMarkdownPreview()).findFirst().orElseThrow());
                editor.buffers.add(projected);
                editor.loadBufferIntoEditor(projected);
                assertMarkdownFoldsUnchanged(editor);
                assertFalse(editor.writingArea.isEditable());
                return null;
            });
        } finally { disposeEditor(editor); }
    }

    @Test
    void markdownFoldShortcutsStayHarmlessAndHeadingNavigationStillWorks() throws Exception {
        assumeSwingAvailable();
        Path file = Files.writeString(tempDir.resolve("fold-keys.md"), "# First\nbody\n## Second\ntext\n");
        Texteditor editor = createEditor(tempDir.resolve("fold-keys-home"), file);
        try {
            onEdt(() -> {
                enableVim(editor);
                FileBuffer buffer = editor.getCurrentBuffer();
                String text = buffer.getContent();
                long revision = buffer.documentRevision();
                for (int modifiers : new int[]{0, KeyEvent.SHIFT_DOWN_MASK}) {
                    KeyEvent tab = new KeyEvent(editor.writingArea, KeyEvent.KEY_PRESSED, 0, modifiers, KeyEvent.VK_TAB, '\t');
                    editor.keyPressed(tab);
                    assertTrue(tab.isConsumed());
                    assertEquals(text, buffer.getContent());
                }
                for (char command : new char[]{'a', 'M', 'R'}) {
                    press(editor, 'z');
                    KeyEvent key = new KeyEvent(editor.writingArea, KeyEvent.KEY_PRESSED, 0, 0,
                        KeyEvent.getExtendedKeyCodeForChar(command), command);
                    editor.keyPressed(key);
                    assertTrue(key.isConsumed());
                    KeyEvent typed = new KeyEvent(editor.writingArea, KeyEvent.KEY_TYPED, 0, 0, KeyEvent.VK_UNDEFINED, command);
                    editor.keyTyped(typed);
                    assertTrue(typed.isConsumed());
                    assertEquals('\0', editor.editorState.pendingKey);
                }
                assertEquals(revision, buffer.documentRevision());
                assertEquals(text, buffer.getContent());
                editor.writingArea.setCaretPosition(0);
                press(editor, ']'); press(editor, ']');
                return null;
            });
            assertTrue(awaitUiState(() -> editor.getCurrentCaretLine() == 2));
            onEdt(() -> {
                assertEquals(2, editor.getCurrentCaretLine());
                press(editor, '['); press(editor, '[');
                return null;
            });
            assertTrue(awaitUiState(() -> editor.getCurrentCaretLine() == 0));
            onEdt(() -> {
                assertEquals(0, editor.getCurrentCaretLine());
                for (String profile : List.of("plain", "emacs")) {
                    editor.configManager.set("keymap.profile", profile);
                    assertMarkdownFoldsUnchanged(editor);
                    KeyEvent typed = new KeyEvent(editor.writingArea, KeyEvent.KEY_TYPED, 0, 0, KeyEvent.VK_UNDEFINED, 'z');
                    editor.keyTyped(typed);
                    assertFalse(typed.isConsumed(), "Non-Vim typing must retain its normal route");
                }
                assertEquals("Table of contents opened", editor.showTableOfContents());
                assertTrue(Arrays.stream(editor.getOwnedWindows()).anyMatch(MarkdownOutlineDialog.class::isInstance));
                return null;
            });
        } finally { disposeEditor(editor); }
    }

    private static void assertMarkdownFoldsUnchanged(Texteditor editor) {
        FileBuffer buffer = editor.getCurrentBuffer();
        String text = buffer.getContent(), saved = buffer.getSavedContent();
        long revision = buffer.documentRevision();
        VersionedTextSnapshot snapshot = buffer.textSnapshot();
        boolean dirty = buffer.isModified();
        BoundedUndoManager history = (BoundedUndoManager) buffer.getUndoManager();
        int entries = history.retainedEditCount();
        long bytes = history.retainedBytes();
        boolean undo = history.canUndo(), redo = history.canRedo();
        int dot = editor.writingArea.getCaretPosition(), mark = editor.writingArea.getCaret().getMark();
        int[] events = {0};
        javax.swing.event.DocumentListener listener = new javax.swing.event.DocumentListener() {
            public void insertUpdate(javax.swing.event.DocumentEvent event) { events[0]++; }
            public void removeUpdate(javax.swing.event.DocumentEvent event) { events[0]++; }
            public void changedUpdate(javax.swing.event.DocumentEvent event) { events[0]++; }
        };
        buffer.getDocument().addDocumentListener(listener);
        try {
            for (String result : List.of(editor.toggleFoldAtCursor(), editor.foldHeading(0, null, null),
                editor.unfoldHeading(0, null), editor.foldAll(), editor.unfoldAll(), editor.globalFoldCycle())) {
                assertEquals("Markdown folding is temporarily unavailable", result);
            }
            assertEquals(0, events[0]);
            assertEquals(text, buffer.getContent());
            assertEquals(saved, buffer.getSavedContent());
            assertEquals(revision, buffer.documentRevision());
            assertSame(snapshot, buffer.textSnapshot());
            assertEquals(dirty, buffer.isModified());
            assertEquals(entries, history.retainedEditCount());
            assertEquals(bytes, history.retainedBytes());
            assertEquals(undo, history.canUndo()); assertEquals(redo, history.canRedo());
            assertEquals(dot, editor.writingArea.getCaretPosition()); assertEquals(mark, editor.writingArea.getCaret().getMark());
        } finally { buffer.getDocument().removeDocumentListener(listener); }
    }

    @Test
    void markdownSubtreePreservesFencedHeadingExamples() throws Exception {
        assumeSwingAvailable();
        Path file = Files.writeString(tempDir.resolve("subtree-fence.md"), "## Implementation");
        Texteditor editor = createEditor(tempDir.resolve("subtree-fence-home"), file);
        try {
            String before = "# Architecture\n\n## Implementation\n\n```markdown\n### Example heading\n```\n\n### Actual subsection\n\n## Next\n";
            String after = before.replace("## Implementation", "# Implementation")
                .replace("### Actual subsection", "## Actual subsection");
            assertMarkdownCommandUndo(editor, before, after, () -> {
                editor.writingArea.setCaretPosition(before.indexOf("## Implementation"));
                return editor.markdownSubtreeShift(false);
            });
        } finally { disposeEditor(editor); }
    }

    @Test
    void markdownHeadingCommandsAndPaletteActionsWorkAcrossProfilesAndVimRoutes() throws Exception {
        assumeSwingAvailable();
        Path file = Files.writeString(tempDir.resolve("heading-commands.md"), "## Parent\n### child");
        Texteditor editor = createEditor(tempDir.resolve("heading-commands-home"), file);
        try {
            for (String profile : List.of("plain", "vim", "emacs")) {
                onEdt(() -> { editor.configManager.set("keymap.profile", profile); return null; });
                for (String[] action : new String[][]{{"Promote Markdown Heading", "# Parent\n### child"},
                        {"Demote Markdown Heading", "### Parent\n### child"}, {"Promote Markdown Subtree", "# Parent\n## child"},
                        {"Demote Markdown Subtree", "### Parent\n#### child"}}) {
                    assertMarkdownCommandUndo(editor, "## Parent\n### child", action[1],
                        () -> editor.commandHandler.execute(PaletteController.surfaceActionCommand(action[0])));
                }
            }
            onEdt(() -> {
                for (char direction : new char[]{'<', '>'}) {
                    String after = direction == '<' ? "# Parent\n## child" : "### Parent\n#### child";
                    assertMarkdownCommandUndo(editor, "## Parent\n### child", after, () -> {
                        enableVim(editor); press(editor, direction); press(editor, 'r'); return "Vim subtree route";
                    });
                }
                assertMarkdownRejectedUnchanged(editor, () -> List.of(editor.commandHandler.execute("md heading unknown"),
                    editor.commandHandler.execute("markdown heading promote extra")));
                return null;
            });
        } finally { disposeEditor(editor); }
    }

    @Test
    void coldVimSubtreeCommandSurvivesOnlyItsNormalPromptExit() throws Exception {
        assumeSwingAvailable();
        String before = "## Parent\n### child";
        Path file = Files.writeString(tempDir.resolve("subtree-prompt.md"), before);
        Texteditor editor = createEditor(tempDir.resolve("subtree-prompt-home"), file);
        try {
            assertTrue(awaitUiState(() -> !editor.stagedStartupPending));
            for (String transition : List.of("normal", "input", "mode", "focus")) for (boolean prompt : List.of(false, true)) {
                var started = new java.util.concurrent.CountDownLatch(1); var release = new java.util.concurrent.CountDownLatch(1);
                var service = new MarkdownAnalysisService(editor.asyncJobService, (source, cancelled) -> {
                    started.countDown();
                    try { assertTrue(release.await(15, TimeUnit.SECONDS)); } catch (InterruptedException ignored) { }
                    return MarkdownHeadingAnalysis.analyze(source, cancelled);
                });
                try {
                    onEdt(() -> {
                        editor.getCurrentBuffer().setContent(before, false); enableVim(editor); editor.writingArea.setCaretPosition(0);
                        editor.markdownController.headingAnalysis().close();
                        var field = MarkdownController.class.getDeclaredField("headingAnalysis"); field.setAccessible(true); field.set(editor.markdownController, service);
                        editor.setMode(EditorMode.COMMAND); editor.editorUiController.setCommandPromptText("markdown heading demote-subtree");
                        KeyEvent enter = new KeyEvent(prompt ? editor.commandBar : editor.writingArea, KeyEvent.KEY_PRESSED, 0, 0, KeyEvent.VK_ENTER, '\n');
                        if (prompt) assertTrue(editor.inputController.handleCommandPromptKeyPressed(enter)); else editor.handleCommandMode(enter);
                        assertEquals(EditorMode.NORMAL, editor.editorState.mode);
                        assertTrue(editor.commandHistory.contains("markdown heading demote-subtree"));
                        if (transition.equals("input")) editor.keyPressed(new KeyEvent(editor.writingArea, KeyEvent.KEY_PRESSED, 0, 0, KeyEvent.VK_ESCAPE, (char) 27));
                        if (transition.equals("mode")) editor.setMode(EditorMode.INSERT);
                        if (transition.equals("focus")) {
                            var event = new FocusEvent(editor.writingArea, FocusEvent.FOCUS_LOST);
                            for (var listener : editor.writingArea.getFocusListeners()) listener.focusLost(event);
                        }
                        return null;
                    });
                    assertTrue(started.await(15, TimeUnit.SECONDS)); release.countDown();
                    assertTrue(awaitUiState(() -> service.diagnostics().running() == 0));
                    assertEquals(transition.equals("normal") ? "### Parent\n#### child" : before,
                        onEdt(() -> editor.getCurrentBuffer().getContent()));
                } finally { release.countDown(); onEdt(() -> { service.close(); return null; }); }
            }
        } finally { disposeEditor(editor); }
    }

    @Test
    void markdownSubtreeRejectsUnsupportedAndLevelBoundariesWithoutEventsOrHistory() throws Exception {
        assumeSwingAvailable();
        Path file = Files.writeString(tempDir.resolve("subtree-reject.md"), "## Root");
        Texteditor editor = createEditor(tempDir.resolve("subtree-reject-home"), file);
        try {
            onEdt(() -> {
                for (String text : List.of("## Root\n### child\n###### too deep", "# Root", "## Root\n> ### quote", "# Root\nSetext\n---",
                        "```md\n## fake\n```", "Title\n===", "    ## code")) {
                    editor.getCurrentBuffer().setContent(text, false); editor.setMode(EditorMode.INSERT);
                    editor.writingArea.setCaretPosition(text.startsWith("```") ? text.indexOf("## fake") : 0);
                    assertMarkdownRejectedUnchanged(editor, () -> {
                        String message = editor.markdownSubtreeShift(!text.equals("# Root"));
                        awaitPendingMarkdownHeadingEdit(editor); return List.of(message);
                    });
                }
                return null;
            });
        } finally { disposeEditor(editor); }
    }

    @Test
    void semanticSubtreeEditKeepsInitiatingSplitAndInactiveViewport() throws Exception {
        assumeSwingAvailable();
        String text = "## Parent\n" + "body\n".repeat(1000) + "### child\n";
        Path file = Files.writeString(tempDir.resolve("subtree-splits.md"), text);
        Texteditor editor = createEditor(tempDir.resolve("subtree-splits-home"), file);
        var retained = new java.util.concurrent.atomic.AtomicReference<MarkdownHeadingAnalysis>();
        String after = text.replace("## Parent", "### Parent").replace("### child", "#### child");
        try {
            assertTrue(awaitUiState(() -> !editor.stagedStartupPending));
            onEdt(() -> {
                editor.setMode(EditorMode.INSERT); editor.splitWindow(true); editor.validate();
                editor.requestMarkdownHeadings(new Object(), editor.getCurrentBuffer(), () -> true, retained::set); return null;
            });
            assertTrue(awaitUiState(() -> retained.get() != null));
            onEdt(() -> {
                EditorPane first = editor.editorPanes.getFirst(), second = editor.editorPanes.getLast();
                first.getTextArea().setCaretPosition(0); editor.activateEditorPane(second); second.getTextArea().setCaretPosition(5);
                first.surface().scrollTo(new EditorSurface.Viewport(0, 800)); var viewport = first.surface().viewport();
                assertEquals("Demoted subtree", editor.markdownSubtreeShift(true));
                assertSame(second, editor.getActivePane()); assertEquals(6, second.getTextArea().getCaretPosition());
                assertEquals(viewport, first.surface().viewport());
                assertSame(first.getTextArea().getDocument(), second.getTextArea().getDocument());
                assertEquals(after, editor.getCurrentBuffer().getContent());
                editor.getCurrentBuffer().getUndoManager().undo(); assertEquals(text, editor.getCurrentBuffer().getContent());
                editor.getCurrentBuffer().getUndoManager().redo(); assertEquals(after, editor.getCurrentBuffer().getContent()); return null;
            });
            assertTrue(awaitUiState(() -> {
                try { assertCurrentMarkdownSyntax(editor); return true; }
                catch (AssertionError pending) { return false; }
            }));
            assertTrue(awaitUiState(() -> {
                try { var journal = RecoveryJournal.read(editor.recoveryStoreDir.toPath());
                    return journal != null && journal.entries().stream().anyMatch(entry -> entry.content().equals(after));
                } catch (java.io.IOException pending) { return false; }
            }));
            assertEquals(text, Files.readString(file));
        } finally { disposeEditor(editor); }
    }

    @Test
    void markdownSelectedLinkUsesLabelAndSelectsItsDestination() throws Exception {
        assumeSwingAvailable();
        Path file = Files.writeString(tempDir.resolve("selected-link.md"), "Important documentation");
        Texteditor editor = createEditor(tempDir.resolve("selected-link-home"), file);
        try {
            onEdt(() -> {
                editor.configManager.set("keymap.profile", "plain");
                editor.writingArea.select(0, editor.writingArea.getDocument().getLength());
                editor.commandHandler.execute("markdown link insert");
                assertEquals("[Important documentation](url)", editor.getCurrentBuffer().getContent());
                assertEquals("url", editor.writingArea.getSelectedText()); return null;
            });
        } finally { disposeEditor(editor); }
    }

    @Test
    void markdownReferenceCommandsPreserveEmptyBehaviorAndHandleDirectedSelectionsInEveryProfile() throws Exception {
        assumeSwingAvailable();
        Path file = Files.writeString(tempDir.resolve("reference-selection.md"), "label");
        Texteditor editor = createEditor(tempDir.resolve("reference-selection-home"), file);
        try {
            onEdt(() -> {
                FileBuffer buffer = editor.getCurrentBuffer();
                for (String profile : List.of("plain", "vim", "emacs")) for (boolean image : List.of(false, true)) {
                    editor.configManager.set("keymap.profile", profile); editor.setMode(EditorMode.INSERT);
                    String command = image ? "image" : "link", template = image ? "![alt](path)" : "[text](url)", destination = image ? "path" : "url";
                    buffer.setContent("body", false); editor.writingArea.setCaretPosition(2);
                    editor.commandHandler.execute(command);
                    assertEquals("bo" + template + "dy", buffer.getContent()); assertEquals(2 + template.length(), editor.writingArea.getCaretPosition());
                    assertEquals(null, editor.writingArea.getSelectedText());
                    for (boolean reverse : List.of(false, true)) for (String before : List.of("label", "before label after", "[old](x)label**tail**")) {
                        buffer.setContent(before, false); int start = before.indexOf("label"), end = start + 5;
                        editor.writingArea.setCaretPosition(reverse ? end : start); editor.writingArea.moveCaretPosition(reverse ? start : end);
                        int dot = editor.writingArea.getCaret().getDot(), mark = editor.writingArea.getCaret().getMark();
                        var history = (BoundedUndoManager) buffer.getUndoManager(); history.discardAllEdits();
                        String inserted = (image ? "![label](path)" : "[label](url)"), after = before.substring(0, start) + inserted + before.substring(end);
                        editor.commandHandler.execute(PaletteController.surfaceActionCommand(image ? "Insert Image" : "Insert Link"));
                        assertEquals(after, buffer.getContent(), profile); assertEquals(destination, editor.writingArea.getSelectedText());
                        assertEquals(1, history.retainedEditCount());
                        history.undo(); assertEquals(before, buffer.getContent()); assertEquals(dot, editor.writingArea.getCaret().getDot()); assertEquals(mark, editor.writingArea.getCaret().getMark());
                        history.redo(); assertEquals(after, buffer.getContent()); assertEquals(destination, editor.writingArea.getSelectedText());
                    }
                    KeyEvent typed = new KeyEvent(editor.writingArea, KeyEvent.KEY_PRESSED, 0, 0, KeyEvent.VK_N, 'n'); editor.keyPressed(typed);
                    if (!typed.isConsumed()) editor.writingArea.getActionMap().get(javax.swing.text.DefaultEditorKit.defaultKeyTypedAction)
                        .actionPerformed(new ActionEvent(editor.writingArea, ActionEvent.ACTION_PERFORMED, "n"));
                    assertEquals("[old](x)" + (image ? "![label](n)" : "[label](n)") + "**tail**", buffer.getContent(), profile);
                }
                return null;
            });
        } finally { disposeEditor(editor); }
    }

    @Test
    void markdownSelectedReferenceRejectionsPreserveAllDocumentAndHistoryState() throws Exception {
        assumeSwingAvailable();
        Path file = Files.writeString(tempDir.resolve("reference-reject.md"), "label");
        Texteditor editor = createEditor(tempDir.resolve("reference-reject-home"), file);
        try {
            onEdt(() -> {
                for (String[] example : new String[][]{{"line one\nline two", "0", "17"}, {"a\tb", "0", "3"}, {"   ", "0", "3"},
                        {"[label]", "1", "6"}, {"\\label", "1", "6"}, {"😀", "1", "2"}, {"a".repeat(4097), "0", "4097"}}) {
                    editor.getCurrentBuffer().setContent(example[0], false); editor.writingArea.select(Integer.parseInt(example[1]), Integer.parseInt(example[2]));
                    assertMarkdownRejectedUnchanged(editor, () -> List.of(editor.insertLink(), editor.insertImage()));
                }
                editor.getCurrentBuffer().setContent("label", false); editor.writingArea.select(0, 5); editor.writingArea.setEditable(false);
                editor.configManager.set("keymap.profile", "plain");
                assertMarkdownRejectedUnchanged(editor, () -> List.of(editor.insertLink(), editor.insertImage()));
                return null;
            });
        } finally { disposeEditor(editor); }
    }

    @Test
    void selectedImageUsesActualPaletteAcceptanceAndCancellationWithoutNewUi() throws Exception {
        assumeSwingAvailable();
        Path file = Files.writeString(tempDir.resolve("image-palette.md"), "Architecture diagram");
        Texteditor editor = createEditor(tempDir.resolve("image-palette-home"), file);
        try {
            assertTrue(awaitUiState(() -> !editor.stagedStartupPending));
            for (boolean accept : List.of(true, false)) {
                var failure = new java.util.concurrent.atomic.AtomicReference<Throwable>();
                onEdt(() -> {
                    editor.configManager.set("keymap.profile", "plain"); editor.setMode(EditorMode.INSERT);
                    var buffer = editor.getCurrentBuffer(); buffer.setContent("Architecture diagram", false); editor.writingArea.select(0, 20);
                    buffer.getUndoManager().discardAllEdits(); var snapshot = buffer.textSnapshot();
                    var driver = new javax.swing.Timer(10, null);
                    var watchdog = new javax.swing.Timer(15000, event -> {
                        failure.set(new AssertionError("Command palette did not complete"));
                        for (Window w : editor.getOwnedWindows()) if (w instanceof javax.swing.JDialog dialog) dialog.dispose();
                    }); watchdog.setRepeats(false);
                    driver.addActionListener(event -> {
                        var dialog = Arrays.stream(editor.getOwnedWindows()).filter(w -> w instanceof javax.swing.JDialog d && d.isShowing()
                            && d.getTitle().equals("Command Palette")).map(w -> (javax.swing.JDialog) w).findFirst().orElse(null);
                        if (dialog == null) return; driver.stop();
                        try {
                            var list = descendants(dialog, javax.swing.JList.class).getFirst();
                            assertEquals(editor.resolveUiFont(), list.getFont());
                            if (accept) {
                                var filter = descendants(dialog, javax.swing.JTextField.class).getFirst(); filter.setText("Insert Image"); filter.postActionEvent();
                            } else dialog.getRootPane().getActionMap().get("dismiss").actionPerformed(new ActionEvent(dialog, 0, "escape"));
                        } catch (Throwable error) { failure.set(error); dialog.dispose(); }
                    }); driver.start(); watchdog.start();
                    try { editor.showCommandPalette(); } finally { driver.stop(); watchdog.stop(); }
                    if (failure.get() != null) throw new AssertionError(failure.get());
                    if (accept) { assertEquals("![Architecture diagram](path)", buffer.getContent()); assertEquals("path", editor.writingArea.getSelectedText()); }
                    else { assertSame(snapshot, buffer.textSnapshot()); assertEquals("Architecture diagram", editor.writingArea.getSelectedText()); assertFalse(buffer.getUndoManager().canUndo()); }
                    return null;
                });
            }
        } finally { disposeEditor(editor); }
    }

    @Test
    void selectedReferenceSharesSourceHistoryAndKeepsPaneViewportAndRecoveryOwnership() throws Exception {
        assumeSwingAvailable(); String text = "label\n" + "body\n".repeat(1000), after = "[label](url)\n" + "body\n".repeat(1000);
        Path file = Files.writeString(tempDir.resolve("insert-split.md"), text);
        Texteditor editor = createEditor(tempDir.resolve("insert-split-home"), file);
        try {
            assertTrue(awaitUiState(() -> !editor.stagedStartupPending));
            onEdt(() -> { editor.configManager.set("keymap.profile", "plain"); editor.setMode(EditorMode.INSERT); editor.splitWindow(true); editor.validate(); return null; });
            flushEdt();
            onEdt(() -> {
                var first = editor.editorPanes.getFirst(); var second = editor.editorPanes.getLast();
                first.getTextArea().setCaretPosition(100); editor.activateEditorPane(second); second.getTextArea().select(0, 5);
                first.surface().scrollTo(new EditorSurface.Viewport(0, 800)); var viewport = first.surface().viewport();
                assertEquals("Link inserted", editor.insertLink()); assertSame(second, editor.getActivePane());
                assertEquals(viewport, first.surface().viewport()); assertEquals("url", second.getTextArea().getSelectedText());
                assertSame(first.getTextArea().getDocument(), second.getTextArea().getDocument()); assertEquals(after, first.getTextArea().getText());
                var history = editor.getCurrentBuffer().getUndoManager(); history.undo(); assertEquals(text, first.getTextArea().getText());
                assertEquals("label", second.getTextArea().getSelectedText()); history.redo(); assertEquals("url", second.getTextArea().getSelectedText()); return null;
            });
            assertTrue(awaitUiState(() -> {
                try { var journal = RecoveryJournal.read(editor.recoveryStoreDir.toPath()); return journal != null && journal.entries().stream().anyMatch(e -> e.content().equals(after)); }
                catch (java.io.IOException pending) { return false; }
            }));
            assertEquals(text, Files.readString(file));
        } finally { disposeEditor(editor); }
    }

    @Test
    void markdownRangeCommandsPreserveEofAndAtomicUndoRedo() throws Exception {
        assumeSwingAvailable();
        Path file = Files.writeString(tempDir.resolve("markdown-edits.md"), "# start");
        Texteditor editor = createEditor(tempDir.resolve("markdown-edits-home"), file);
        try {
            String indent = onEdt(() -> editor.effectiveExpandTab() ? " ".repeat(editor.writingArea.getTabSize()) : "\t");
            for (String eof : List.of("", "\n")) {
                assertMarkdownCommandUndo(editor, "- [ ] café" + eof, "- [x] café" + eof,
                    () -> editor.commandHandler.execute("markdown checkbox toggle"));
                assertMarkdownCommandUndo(editor, "## café" + eof, "# café" + eof, () -> editor.markdownHeadingShift(false));
                assertMarkdownCommandUndo(editor, "# café" + eof, "## café" + eof, () -> editor.markdownHeadingShift(true));
                assertMarkdownCommandUndo(editor, "# parent\nbody\n## child" + eof, "## parent\nbody\n### child" + eof,
                    () -> editor.markdownSubtreeShift(true));
                assertMarkdownCommandUndo(editor, "## parent\nbody\n### child" + eof, "# parent\nbody\n## child" + eof,
                    () -> editor.markdownSubtreeShift(false));
                assertMarkdownCommandUndo(editor, "```py" + eof, "```python" + eof, editor::completeCodeFenceLanguage);
                assertMarkdownCommandUndo(editor, "  ```py" + eof, "  ```python" + eof, editor::completeCodeFenceLanguage);
                String table = "| a | b |\n| --- | --- |\n| 2 | z |\n| 1 | y |";
                String aligned = "| a   | b   |\n| --- | --- |\n| 2   | z   |\n| 1   | y   |";
                assertMarkdownCommandUndo(editor, table + eof, aligned + eof, editor::alignMarkdownTable);
                assertMarkdownCommandUndo(editor, table + eof,
                    "| a   | b   |\n| --- | --- |\n| 1   | y   |\n| 2   | z   |" + eof,
                    () -> editor.sortMarkdownTable("1 asc"));
                assertMarkdownCommandUndo(editor, table + eof,
                    "| a   |     | b   |\n| --- | --- | --- |\n| 2   |     | z   |\n| 1   |     | y   |" + eof,
                    () -> editor.insertTableColumn(""));
                assertMarkdownCommandUndo(editor, table + eof,
                    "| b   |\n| --- |\n| z   |\n| y   |" + eof, () -> editor.deleteTableColumn("1"));
                assertMarkdownCommandUndo(editor, "préface" + eof,
                    editor.markdownService.insertLinkTemplate() + "préface" + eof, editor::insertLink);
                assertMarkdownCommandUndo(editor, "préface" + eof,
                    editor.markdownService.insertImageTemplate() + "préface" + eof, editor::insertImage);
                assertMarkdownCommandUndo(editor, "" + eof,
                    editor.markdownService.createTableTemplate(1, 1) + eof, () -> editor.insertTableTemplate("1x1"));
                assertMarkdownCommandUndo(editor, "body" + eof,
                    indent + "body" + eof,
                    () -> editor.markdownHeadingShift(true));
                assertMarkdownCommandUndo(editor, "préface" + eof,
                    "p[ré](url)face" + eof, () -> {
                        editor.writingArea.select(1, 3);
                        return editor.insertLink();
                    });
                assertMarkdownCommandUndo(editor, "# title" + eof, "## title" + eof, () -> {
                    enableVim(editor); press(editor, '>'); press(editor, '>'); return "Vim heading shift";
                });
            }
        } finally { disposeEditor(editor); }
    }

    private static void assertMarkdownCommandUndo(Texteditor editor, String before, String after, Callable<String> command) throws Exception {
        if (!SwingUtilities.isEventDispatchThread()) {
            onEdt(() -> { assertMarkdownCommandUndo(editor, before, after, command); return null; });
            return;
        }
        FileBuffer buffer = editor.getCurrentBuffer();
        buffer.setContent(before, false);
        editor.setMode(EditorMode.INSERT);
        editor.writingArea.setCaretPosition(0);
        BoundedUndoManager history = (BoundedUndoManager) buffer.getUndoManager();
        history.discardAllEdits();
        String saved = buffer.getSavedContent();
        String message = command.call();
        awaitPendingMarkdownHeadingEdit(editor);
        assertEquals(after, editor.writingArea.getText(), message);
        assertEquals(after, buffer.textSnapshot().text());
        assertEquals(saved, buffer.getSavedContent());
        assertTrue(buffer.isModified());
        assertEquals(1, history.retainedEditCount(), message);
        history.undo(); assertEquals(before, editor.writingArea.getText()); assertFalse(history.canUndo());
        history.redo(); assertEquals(after, editor.writingArea.getText()); assertFalse(history.canRedo());
    }

    private static void awaitPendingMarkdownHeadingEdit(Texteditor editor) throws Exception {
        var field = MarkdownController.class.getDeclaredField("pendingHeadingEdit"); field.setAccessible(true);
        if (field.get(editor.markdownController) == null) return;
        var loop = java.awt.Toolkit.getDefaultToolkit().getSystemEventQueue().createSecondaryLoop();
        boolean[] timedOut = {false};
        var timeout = new javax.swing.Timer(10000, e -> { timedOut[0] = true; loop.exit(); }); timeout.setRepeats(false);
        var source = MarkdownSemanticSnapshot.Source.capture(editor.documentStore.id(editor.getCurrentBuffer()), editor.getCurrentBuffer());
        // An observer of this exact in-flight revision exits after the completion dispatch. It
        // remains current even when the edit's own callback changes the source first.
        editor.markdownController.headingAnalysis().request(new Object(), source, () -> true, result -> loop.exit());
        timeout.start();
        try { loop.enter(); assertFalse(timedOut[0], "Heading edit completion timed out"); }
        finally { timeout.stop(); }
    }

    private static void warmMarkdownKeyboardContext(Texteditor editor) throws Exception {
        // Test-only synchronous preparation of the existing metadata; production uses its normal worker.
        @SuppressWarnings("unchecked") var caches = (java.util.Map<FileBuffer, GrammarHighlightService.MarkdownCache>) syntaxField(editor, "markdownCaches");
        var buffer = editor.getCurrentBuffer(); var source = buffer.textSnapshot();
        var cache = caches.computeIfAbsent(buffer, ignored -> new GrammarHighlightService.MarkdownCache());
        new GrammarHighlightService().highlightMarkdown(source, 0, source.length(), cache, cache.bind(source), () -> false);
    }

    private static void markdownEnterWithDefault(Texteditor editor) {
        KeyEvent event = new KeyEvent(editor.writingArea, KeyEvent.KEY_PRESSED, 0, 0, KeyEvent.VK_ENTER, '\n');
        editor.keyPressed(event);
        if (!event.isConsumed()) editor.writingArea.getActionMap().get(javax.swing.text.DefaultEditorKit.insertBreakAction)
            .actionPerformed(new java.awt.event.ActionEvent(editor.writingArea, 0, "\n"));
    }

    @Test
    void markdownNestedQuotesUseAtomicEnterAcrossProfilesAndDirectedSelections() throws Exception {
        assumeSwingAvailable();
        Path file = Files.writeString(tempDir.resolve("nested-quotes.md"), "> > nested");
        Texteditor editor = createEditor(tempDir.resolve("nested-quotes-home"), file);
        try {
            onEdt(() -> {
                for (String profile : List.of("plain", "vim", "emacs")) {
                    editor.configManager.set("keymap.profile", profile);
                    for (String[] row : new String[][] {{"> quote", "> "}, {"> > nested", "> > "}, {"> > > 深😀é", "> > > "},
                        {"  > > nested", "  > > "}, {">   > text", ">   > "}, {"> - item", "> - "},
                        {"> > - [x] task", "> > - [ ] "}, {"> > 3. item", "> > 4. "}})
                        for (String eof : List.of("", "\n")) assertMarkdownCommandUndo(editor, row[0] + eof,
                            row[0] + "\n" + row[1] + eof, () -> {
                                editor.writingArea.setCaretPosition(row[0].length()); markdownEnterWithDefault(editor);
                                assertEquals(row[0].length() + 1 + row[1].length(), editor.writingArea.getCaretPosition()); return profile;
                            });
                    for (String empty : List.of("> ", "> > ", ">>>"))
                        assertMarkdownCommandUndo(editor, empty, "\n", () -> {
                            editor.writingArea.setCaretPosition(empty.length()); markdownEnterWithDefault(editor); return profile;
                        });
                    for (boolean reversed : List.of(false, true))
                        assertMarkdownCommandUndo(editor, "> > abcd tail", "> > ab\n> >  tail", () -> {
                            editor.writingArea.setCaretPosition(reversed ? 8 : 6); editor.writingArea.moveCaretPosition(reversed ? 6 : 8);
                            markdownEnterWithDefault(editor); assertEquals(11, editor.writingArea.getCaretPosition()); return profile;
                        });
                }
                return null;
            });
        } finally { disposeEditor(editor); }
    }

    @Test
    void markdownFenceCompletionVerifiesOpeningsAndTheExistingVimShortcut() throws Exception {
        assumeSwingAvailable();
        Path file = Files.writeString(tempDir.resolve("fence-context.md"), "```py");
        Texteditor editor = createEditor(tempDir.resolve("fence-context-home"), file);
        try {
            onEdt(() -> {
                for (String opening : List.of("```py", "  ```py", "`````py", "``` py"))
                    for (int caret : new int[]{0, opening.length()}) assertMarkdownCommandUndo(editor, opening,
                        opening.replace("py", "python"), () -> { editor.writingArea.setCaretPosition(caret); return editor.completeCodeFenceLanguage(); });
                String multiple = "```java\nbody\n```\n```py";
                assertMarkdownCommandUndo(editor, multiple, multiple.substring(0, multiple.length() - 2) + "python", () -> {
                    editor.writingArea.setCaretPosition(multiple.length()); warmMarkdownKeyboardContext(editor); return editor.completeCodeFenceLanguage();
                });
                for (String source : List.of("```java\nbody\n```", "````java\n```py", "~~~java\n```py", "> ```py", "- ```py",
                    "    ```py", "\t```py", "\\```py", "~~~py", "<pre>\n```py", "- item\n  ```py")) {
                    editor.getCurrentBuffer().setContent(source, false); editor.writingArea.setCaretPosition(source.length());
                    warmMarkdownKeyboardContext(editor);
                    assertMarkdownRejectedUnchanged(editor, () -> List.of(editor.completeCodeFenceLanguage()));
                }
                editor.configManager.set("keymap.profile", "vim");
                assertMarkdownCommandUndo(editor, "```py", "```python", () -> {
                    editor.writingArea.setCaretPosition(5);
                    editor.keyPressed(new KeyEvent(editor.writingArea, KeyEvent.KEY_PRESSED, 0, KeyEvent.CTRL_DOWN_MASK, KeyEvent.VK_J, 'j'));
                    return "existing Vim Ctrl+J";
                });
                editor.getCurrentBuffer().setContent("```java\n```", false); editor.writingArea.setCaretPosition(11); warmMarkdownKeyboardContext(editor);
                assertMarkdownRejectedUnchanged(editor, () -> {
                    editor.keyPressed(new KeyEvent(editor.writingArea, KeyEvent.KEY_PRESSED, 0, KeyEvent.CTRL_DOWN_MASK, KeyEvent.VK_J, 'j'));
                    return List.of("closing fence shortcut");
                });
                return null;
            });
        } finally { disposeEditor(editor); }
    }

    @Test
    void markdownKeyboardContextRejectsCodeStaleAndReadOnlyTargetsAndSharesSplits() throws Exception {
        assumeSwingAvailable();
        Path file = Files.writeString(tempDir.resolve("keyboard-context.md"), "````markdown\n```\n> > example\n````\n> > body");
        Texteditor editor = createEditor(tempDir.resolve("keyboard-context-home"), file);
        try {
            onEdt(() -> { editor.applySyntaxHighlighting(); return null; });
            assertTrue(awaitUiState(() -> markdownSyntaxSettled(editor)));
            onEdt(() -> {
                for (String profile : List.of("plain", "vim", "emacs")) {
                    editor.configManager.set("keymap.profile", profile);
                    for (String prefix : List.of("```markdown\n", "~~~~markdown\n", "> ```markdown\n", "<pre>\n")) {
                        String source = prefix + "> > example";
                        assertMarkdownCommandUndo(editor, source, source + "\n", () -> {
                            warmMarkdownKeyboardContext(editor); editor.writingArea.setCaretPosition(source.length()); markdownEnterWithDefault(editor); return profile;
                        });
                    }
                    assertMarkdownCommandUndo(editor, "body\n> > text", "body\n> > text\n> > ", () -> {
                        warmMarkdownKeyboardContext(editor); editor.writingArea.setCaretPosition(13); markdownEnterWithDefault(editor); return profile;
                    });
                }
                var buffer = editor.getCurrentBuffer(); buffer.setContent("> > shared", false); editor.writingArea.setCaretPosition(10);
                editor.splitWindow(true); var owner = editor.getActivePane();
                markdownEnterWithDefault(editor); assertEquals("> > shared\n> > ", buffer.getContent());
                for (var pane : editor.editorPanes) assertSame(buffer.getDocument(), pane.getTextArea().getDocument());
                buffer.getUndoManager().undo(); assertEquals("> > shared", buffer.getContent()); buffer.getUndoManager().redo();
                editor.activateEditorPane(owner); editor.writingArea.setEditable(false); editor.configManager.set("keymap.profile", "plain");
                assertMarkdownRejectedUnchanged(editor, () -> List.of(editor.handleMarkdownEnter(), editor.completeCodeFenceLanguage()));
                editor.writingArea.setEditable(true);
                buffer.setContent("> > 😀", false); editor.writingArea.select(5, 6);
                assertMarkdownRejectedUnchanged(editor, () -> List.of(editor.handleMarkdownEnter()));
                buffer.setContent("```java\n> > source", false); warmMarkdownKeyboardContext(editor);
                buffer.getDocument().remove(0, 8); editor.writingArea.setCaretPosition(buffer.getDocument().getLength());
                markdownEnterWithDefault(editor); assertEquals("> > source\n> > ", buffer.getContent());
                var other = FileBuffer.createScratch("other.md", "untouched"); editor.buffers.add(other); editor.loadBufferIntoEditor(other);
                return null;
            });
            flushEdt(); assertEquals("untouched", onEdt(() -> editor.buffers.stream().filter(b -> "other.md".equals(b.getDisplayName())).findFirst().orElseThrow().getContent()));
        } finally { disposeEditor(editor); }
    }

    @Test
    void markdownKeyboardEditsPreserveBomNewlinesSavingAndRecovery() throws Exception {
        assumeSwingAvailable();
        Texteditor editor = createEditor(tempDir.resolve("keyboard-persistence-home"));
        try {
            for (String separator : List.of("\n", "\r\n")) for (String eof : List.of("", separator)) {
                String original = "\ufeffintro" + separator + "> > é😀é" + eof;
                Path file = Files.write(tempDir.resolve("quote-" + separator.length() + "-" + eof.length() + ".md"), original.getBytes(StandardCharsets.UTF_8));
                FileBuffer buffer = new FileBuffer(file.toFile());
                onEdt(() -> {
                    editor.buffers.add(buffer); editor.loadBufferIntoEditor(buffer); editor.configManager.set("keymap.profile", "plain");
                    warmMarkdownKeyboardContext(editor); editor.writingArea.setCaretPosition("intro\n> > é😀é".length()); markdownEnterWithDefault(editor);
                    String expected = "intro\n> > é😀é\n> > " + (eof.isEmpty() ? "" : "\n");
                    assertEquals(expected, buffer.getContent()); assertTrue(buffer.isModified());
                    buffer.getUndoManager().undo(); assertEquals("intro\n> > é😀é" + (eof.isEmpty() ? "" : "\n"), buffer.getContent());
                    buffer.getUndoManager().redo(); assertEquals(expected, buffer.getContent()); return null;
                });
                var save = onEdt(() -> buffer.captureSaveSnapshot(null)); buffer.writeSaveSnapshot(save);
                onEdt(() -> { buffer.completeSaveSnapshot(save); return null; });
                org.junit.jupiter.api.Assertions.assertArrayEquals(("\ufeffintro" + separator + "> > é😀é" + separator + "> > " + eof).getBytes(StandardCharsets.UTF_8), Files.readAllBytes(file));
                assertEquals(onEdt(buffer::getContent), new FileBuffer(file.toFile()).getContent());
            }
            String recovery = onEdt(() -> {
                editor.writingArea.setCaretPosition(editor.writingArea.getDocument().getLength()); markdownEnterWithDefault(editor);
                editor.persistRecoverySnapshots(); return editor.getCurrentBuffer().getContent();
            });
            assertTrue(awaitUiState(() -> {
                try { var journal = RecoveryJournal.read(editor.recoveryStoreDir.toPath());
                    return journal != null && journal.entries().stream().anyMatch(entry -> entry.content().equals(recovery));
                } catch (java.io.IOException error) { return false; }
            }));
        } finally { disposeEditor(editor); }
    }

    @Test
    void markdownClosingFenceCompletionDoesNotInsertALanguage() throws Exception {
        assumeSwingAvailable();
        Path file = Files.writeString(tempDir.resolve("closing-fence.md"), "```java\nbody\n```");
        Texteditor editor = createEditor(tempDir.resolve("closing-fence-home"), file);
        try {
            onEdt(() -> {
                editor.writingArea.setCaretPosition(editor.writingArea.getDocument().getLength());
                assertMarkdownRejectedUnchanged(editor, () -> List.of(editor.completeCodeFenceLanguage()));
                return null;
            });
        } finally { disposeEditor(editor); }
    }

    @Test
    void markdownEnterUsesOneCapturedEditAcrossPlainVimAndEmacs() throws Exception {
        assumeSwingAvailable();
        Path file = Files.writeString(tempDir.resolve("markdown-enter.md"), "- item");
        Texteditor editor = createEditor(tempDir.resolve("markdown-enter-home"), file);
        try {
            onEdt(() -> {
                for (String profile : List.of("plain", "vim", "emacs")) {
                    editor.configManager.set("keymap.profile", profile);
                    for (String[] example : new String[][] {
                        {"- item", "- item\n- "}, {"1. item", "1. item\n2. "},
                        {"  - nested item", "  - nested item\n  - "},
                        {"- [ ] task", "- [ ] task\n- [ ] "}, {"- [x] task", "- [x] task\n- [ ] "},
                        {"> quoted text", "> quoted text\n> "},
                        {"- ", "\n"}, {"1. ", "\n"}, {"- [ ] ", "\n"}, {"- [x] ", "\n"}, {"> ", "\n"}
                    }) {
                        assertMarkdownCommandUndo(editor, example[0], example[1], () -> {
                            editor.writingArea.setCaretPosition(example[0].length());
                            KeyEvent event = new KeyEvent(editor.writingArea, KeyEvent.KEY_PRESSED, 0, 0, KeyEvent.VK_ENTER, '\n');
                            editor.keyPressed(event);
                            assertTrue(event.isConsumed(), profile);
                            assertEquals(example[1].length(), editor.writingArea.getCaretPosition());
                            return profile;
                        });
                    }
                    assertMarkdownCommandUndo(editor, "- item\nnext", "- item\n- \nnext", () -> {
                        editor.writingArea.setCaretPosition(6);
                        editor.keyPressed(new KeyEvent(editor.writingArea, KeyEvent.KEY_PRESSED, 0, 0, KeyEvent.VK_ENTER, '\n'));
                        assertEquals(9, editor.writingArea.getCaretPosition());
                        return profile;
                    });
                    assertMarkdownCommandUndo(editor, "- \nnext", "\nnext", () -> {
                        editor.writingArea.setCaretPosition(2);
                        editor.keyPressed(new KeyEvent(editor.writingArea, KeyEvent.KEY_PRESSED, 0, 0, KeyEvent.VK_ENTER, '\n'));
                        assertEquals(1, editor.writingArea.getCaretPosition());
                        return profile;
                    });
                    assertMarkdownCommandUndo(editor, "- abcd tail", "- ab\n-  tail", () -> {
                        editor.writingArea.select(4, 6);
                        editor.keyPressed(new KeyEvent(editor.writingArea, KeyEvent.KEY_PRESSED, 0, 0, KeyEvent.VK_ENTER, '\n'));
                        assertEquals(7, editor.writingArea.getCaretPosition());
                        return profile;
                    });
                }
                editor.configManager.set("keymap.profile", "vim");
                editor.configManager.set("auto.indent", "true");
                assertMarkdownCommandUndo(editor, "  text", "  text\n  ", () -> {
                    editor.writingArea.setCaretPosition(6);
                    KeyEvent enter = new KeyEvent(editor.writingArea, KeyEvent.KEY_PRESSED, 0, 0, KeyEvent.VK_ENTER, '\n');
                    editor.keyPressed(enter); assertTrue(enter.isConsumed());
                    return "Vim ordinary Markdown Enter";
                });
                return null;
            });
            // Flushing the queue proves there is no deferred prefix that can edit a newly activated buffer.
            onEdt(() -> {
                FileBuffer previous = editor.getCurrentBuffer();
                FileBuffer other = FileBuffer.createScratch("other.md", "untouched");
                editor.buffers.add(other); editor.loadBufferIntoEditor(other);
                assertEquals("untouched", other.getContent());
                assertEquals("  text\n  ", previous.getContent());
                return null;
            });
            flushEdt();
            assertEquals("untouched", onEdt(() -> editor.writingArea.getText()));
        } finally { disposeEditor(editor); }
    }

    @Test
    void markdownTablesHandleEscapesAndRejectAmbiguousSyntaxAndDimensionsWithoutEvents() throws Exception {
        assumeSwingAvailable();
        Path file = Files.writeString(tempDir.resolve("markdown-tables.md"), "# start");
        Texteditor editor = createEditor(tempDir.resolve("markdown-tables-home"), file);
        try {
            onEdt(() -> {
                assertMarkdownCommandUndo(editor, "| a\\|b | c |\n| --- | --- |",
                    "| a\\|b | c   |\n| ---- | --- |", editor::alignMarkdownTable);
                for (String source : List.of("| `a|b` | c |\n| --- | --- |", "| a | b |\n| --- | --- |\n| c |",
                    "  | a |\n  | --- |", "| a |\n| -- |")) {
                    editor.getCurrentBuffer().setContent(source, false);
                    editor.writingArea.setCaretPosition(0);
                    assertMarkdownRejectedUnchanged(editor, () -> List.of(editor.alignMarkdownTable(), editor.sortMarkdownTable("1"),
                        editor.insertTableColumn(""), editor.deleteTableColumn("1"), editor.markdownTableNextCell(false)));
                }
                for (String dimensions : List.of("-1x2", "1x-1", "0x1", "1x0", "2147483647x2147483647", "256x10000", "bad", "2x", "2x3x4", "x".repeat(100_000)))
                    assertMarkdownRejectedUnchanged(editor, () -> List.of(editor.insertTableTemplate(dimensions)));
                assertMarkdownCommandUndo(editor, "| a | b |\n| --- | --- |\n| c | d |",
                    "| a | b |\n| --- | --- |\n| c | d |\n|     |     |", () -> {
                        editor.writingArea.setCaretPosition(editor.writingArea.getDocument().getLength() - 2);
                        KeyEvent tab = new KeyEvent(editor.writingArea, KeyEvent.KEY_PRESSED, 0, 0, KeyEvent.VK_TAB, '\t');
                        editor.configManager.set("keymap.profile", "vim");
                        editor.keyPressed(tab); assertTrue(tab.isConsumed());
                        return "new table row at EOF";
                    });
                return null;
            });
        } finally { disposeEditor(editor); }
    }

    @Test
    void markdownMutationsRejectReadOnlyPreviewAlternateAndLargeFileSurfaces() throws Exception {
        assumeSwingAvailable();
        Path file = Files.writeString(tempDir.resolve("markdown-permissions.md"), "- [ ] task");
        Texteditor editor = createEditor(tempDir.resolve("markdown-permissions-home"), file);
        try {
            onEdt(() -> {
                editor.setMode(EditorMode.INSERT);
                editor.writingArea.setEditable(false);
                assertAllMarkdownMutationsRejected(editor);
                for (String profile : List.of("plain", "vim", "emacs")) {
                    editor.configManager.set("keymap.profile", profile);
                    assertMarkdownRejectedUnchanged(editor, () -> {
                        KeyEvent enter = new KeyEvent(editor.writingArea, KeyEvent.KEY_PRESSED, 0, 0, KeyEvent.VK_ENTER, '\n');
                        editor.keyPressed(enter); assertTrue(enter.isConsumed());
                        return List.of("read-only Enter rejected");
                    });
                }
                editor.writingArea.setEditable(true);
                EditorPane pane = editor.getActivePane();
                pane.setMarkdownPreviewComponent(new javax.swing.JPanel());
                assertAllMarkdownMutationsRejected(editor); pane.clearMarkdownPreviewComponent();
                pane.setAlternateComponent(new javax.swing.JPanel());
                assertAllMarkdownMutationsRejected(editor); pane.clearAlternateComponent();
                editor.writingArea.setEnabled(false);
                assertAllMarkdownMutationsRejected(editor); editor.writingArea.setEnabled(true);
                editor.getCurrentBuffer().getDocument().setDocumentFilter(new javax.swing.text.DocumentFilter());
                assertMarkdownRejectedUnchanged(editor, () -> List.of(editor.toggleCheckbox(), editor.insertLink(), editor.insertImage()));
                editor.getCurrentBuffer().getDocument().setDocumentFilter(null);
                return null;
            });
            Path large = Files.writeString(tempDir.resolve("markdown-projected.md"), "# Large\n" + "body\n".repeat(1001));
            onEdt(() -> { editor.configManager.set("large.file.line.threshold", "1000"); return null; });
            FileBuffer projected = new FileBuffer(large.toFile(), editor.configManager);
            onEdt(() -> {
                assertTrue(projected.isLargeFile());
                editor.buffers.add(projected); editor.loadBufferIntoEditor(projected);
                assertAllMarkdownMutationsRejected(editor);
                return null;
            });
        } finally { disposeEditor(editor); }
    }

    private static void assertAllMarkdownMutationsRejected(Texteditor editor) throws Exception {
        assertMarkdownRejectedUnchanged(editor, () -> Arrays.asList(editor.toggleCheckbox(), editor.markdownHeadingShift(true),
            editor.markdownSubtreeShift(true), editor.alignMarkdownTable(), editor.sortMarkdownTable("1"),
            editor.insertTableColumn(""), editor.deleteTableColumn("1"), editor.insertTableTemplate("2x2"),
            editor.insertLink(), editor.insertImage(), editor.completeCodeFenceLanguage(), editor.handleMarkdownEnter(),
            editor.markdownTableNextCell(false), editor.markdownTableNextCell(true), editor.expandSnippetAtCursor()));
    }

    private static void assertMarkdownRejectedUnchanged(Texteditor editor, Callable<List<String>> commands) throws Exception {
        FileBuffer buffer = editor.getCurrentBuffer();
        String source = editor.writingArea.getText(), saved = buffer.getSavedContent();
        VersionedTextSnapshot snapshot = buffer.textSnapshot();
        long revision = buffer.documentRevision();
        boolean dirty = buffer.isModified();
        BoundedUndoManager history = (BoundedUndoManager) buffer.getUndoManager();
        int count = history.retainedEditCount(), dot = editor.writingArea.getCaret().getDot(), mark = editor.writingArea.getCaret().getMark();
        long bytes = history.retainedBytes();
        boolean undo = history.canUndo(), redo = history.canRedo();
        int[] events = {0};
        var document = editor.writingArea.getDocument();
        javax.swing.event.DocumentListener listener = new javax.swing.event.DocumentListener() {
            public void insertUpdate(javax.swing.event.DocumentEvent event) { events[0]++; }
            public void removeUpdate(javax.swing.event.DocumentEvent event) { events[0]++; }
            public void changedUpdate(javax.swing.event.DocumentEvent event) { events[0]++; }
        };
        document.addDocumentListener(listener);
        try {
            for (String message : commands.call()) assertTrue(message != null && !message.isBlank());
            assertEquals(0, events[0]); assertEquals(source, editor.writingArea.getText());
            assertEquals(saved, buffer.getSavedContent()); assertSame(snapshot, buffer.textSnapshot());
            assertEquals(revision, buffer.documentRevision()); assertEquals(dirty, buffer.isModified());
            assertEquals(count, history.retainedEditCount()); assertEquals(bytes, history.retainedBytes());
            assertEquals(undo, history.canUndo()); assertEquals(redo, history.canRedo());
            assertEquals(dot, editor.writingArea.getCaret().getDot()); assertEquals(mark, editor.writingArea.getCaret().getMark());
        } finally { document.removeDocumentListener(listener); }
    }

    @Test
    void markdownCommandHistoryIsSharedAndVimNormalCommandsRemainWritable() throws Exception {
        assumeSwingAvailable();
        Path file = Files.writeString(tempDir.resolve("markdown-splits.md"), "- [ ] task");
        Texteditor editor = createEditor(tempDir.resolve("markdown-splits-home"), file);
        try {
            onEdt(() -> {
                FileBuffer buffer = editor.getCurrentBuffer();
                editor.splitWindow(true);
                enableVim(editor);
                assertFalse(editor.writingArea.isEditable());
                editor.writingArea.append("!");
                assertEquals("Checked", editor.toggleCheckbox());
                for (EditorPane pane : editor.editorPanes) assertEquals("- [x] task!", pane.getTextArea().getText());
                buffer.getUndoManager().undo(); assertEquals("- [ ] task!", editor.writingArea.getText());
                buffer.getUndoManager().undo(); assertEquals("- [ ] task", editor.writingArea.getText());
                buffer.getUndoManager().redo(); buffer.getUndoManager().redo();
                for (EditorPane pane : editor.editorPanes) assertEquals("- [x] task!", pane.getTextArea().getText());
                EditorPane capturedPane = editor.getActivePane();
                MarkdownEdit pending = new MarkdownEdit(buffer, editor.writingArea, capturedPane.surface(),
                    () -> editor.getActivePane() == capturedPane && editor.getCurrentBuffer() == buffer, 0, 5);
                FileBuffer other = FileBuffer.createScratch("other.md", "- [ ] other");
                editor.buffers.add(other); editor.loadBufferIntoEditor(other);
                String failure = pending.apply("oops", -1);
                assertFalse(failure.isEmpty());
                assertEquals("- [ ] other", editor.writingArea.getText());
                assertEquals("- [x] task!", buffer.getContent());
                editor.loadBufferIntoEditor(buffer);
                assertMarkdownFoldsUnchanged(editor);
                return null;
            });
        } finally { disposeEditor(editor); }
    }

    @Test
    void markdownEnterRespectsCompositionPrefixesAndUnrelatedKeys() throws Exception {
        assumeSwingAvailable();
        Path file = Files.writeString(tempDir.resolve("markdown-ime.md"), "- item");
        Texteditor editor = createEditor(tempDir.resolve("markdown-ime-home"), file);
        try {
            onEdt(() -> {
                for (String profile : List.of("plain", "vim", "emacs")) {
                    editor.configManager.set("keymap.profile", profile); editor.setMode(EditorMode.INSERT);
                    editor.writingArea.setCaretPosition(6);
                    var composition = new java.awt.event.InputMethodEvent(editor.writingArea,
                        java.awt.event.InputMethodEvent.INPUT_METHOD_TEXT_CHANGED,
                        new java.text.AttributedString("あ").getIterator(), 0, null, null);
                    for (var listener : editor.writingArea.getInputMethodListeners()) listener.inputMethodTextChanged(composition);
                    KeyEvent enter = new KeyEvent(editor.writingArea, KeyEvent.KEY_PRESSED, 0, 0, KeyEvent.VK_ENTER, '\n');
                    editor.keyPressed(enter);
                    assertFalse(enter.isConsumed()); assertEquals("- item", editor.writingArea.getText());
                    var committed = new java.awt.event.InputMethodEvent(editor.writingArea,
                        java.awt.event.InputMethodEvent.INPUT_METHOD_TEXT_CHANGED, null, 0, null, null);
                    for (var listener : editor.writingArea.getInputMethodListeners()) listener.inputMethodTextChanged(committed);
                    KeyEvent otherKey = new KeyEvent(editor.writingArea, KeyEvent.KEY_PRESSED, 0, 0, KeyEvent.VK_F2, KeyEvent.CHAR_UNDEFINED);
                    editor.keyPressed(otherKey); assertFalse(otherKey.isConsumed());
                }
                editor.configManager.set("keymap.profile", "emacs");
                editor.keyPressed(new KeyEvent(editor.writingArea, KeyEvent.KEY_PRESSED, 0, KeyEvent.CTRL_DOWN_MASK, KeyEvent.VK_X, 'x'));
                KeyEvent prefixed = new KeyEvent(editor.writingArea, KeyEvent.KEY_PRESSED, 0, 0, KeyEvent.VK_ENTER, '\n');
                editor.keyPressed(prefixed); assertTrue(prefixed.isConsumed());
                assertEquals("- item", editor.writingArea.getText());
                return null;
            });
        } finally { disposeEditor(editor); }
    }

    @Test
    void markdownCommandsPersistExactNewlineBomBytesAndCaptureDirtyRecovery() throws Exception {
        assumeSwingAvailable();
        Path initial = Files.writeString(tempDir.resolve("markdown-codec-start.md"), "# start");
        Texteditor editor = createEditor(tempDir.resolve("markdown-codec-home"), initial);
        try {
            for (String separator : List.of("\n", "\r\n", "\r\nbody\n")) {
                for (boolean eof : List.of(false, true)) {
                    Path file = tempDir.resolve("markdown-codec-" + Math.abs(separator.hashCode()) + "-" + eof + ".md");
                    String original = "\ufeff# Heading" + separator + "- [ ] café" + (eof ? "\r\n" : "");
                    Files.write(file, original.getBytes(StandardCharsets.UTF_8));
                    FileBuffer buffer = new FileBuffer(file.toFile());
                    onEdt(() -> {
                        editor.buffers.add(buffer); editor.loadBufferIntoEditor(buffer); editor.setMode(EditorMode.INSERT);
                        editor.writingArea.setCaretPosition(editor.writingArea.getText().indexOf("[ ]"));
                        assertEquals("Checked", editor.toggleCheckbox());
                        buffer.getUndoManager().undo();
                        assertEquals(original.substring(1).replace("\r\n", "\n"), editor.writingArea.getText());
                        buffer.getUndoManager().redo();
                        return null;
                    });
                    FileBuffer.SaveSnapshot save = onEdt(() -> buffer.captureSaveSnapshot(null));
                    buffer.writeSaveSnapshot(save);
                    onEdt(() -> { buffer.completeSaveSnapshot(save); return null; });
                    org.junit.jupiter.api.Assertions.assertArrayEquals(original.replace("[ ]", "[x]").getBytes(StandardCharsets.UTF_8), Files.readAllBytes(file));
                    assertEquals(onEdt(buffer::getContent), new FileBuffer(file.toFile()).getContent());
                }
            }
            String recoveryText = onEdt(() -> {
                FileBuffer buffer = editor.getCurrentBuffer();
                editor.writingArea.append(" draft");
                editor.writingArea.setCaretPosition(editor.writingArea.getText().indexOf("[x]"));
                assertEquals("Unchecked", editor.toggleCheckbox());
                assertTrue(buffer.isModified());
                editor.persistRecoverySnapshots();
                return editor.writingArea.getText();
            });
            Path recovery = editor.recoveryStoreDir.toPath();
            assertTrue(awaitUiState(() -> {
                try {
                    var journal = RecoveryJournal.read(recovery);
                    return journal != null && journal.entries().stream().anyMatch(entry -> entry.content().equals(recoveryText));
                } catch (java.io.IOException error) { return false; }
            }));
            var entry = RecoveryJournal.read(recovery).entries().stream().filter(e -> e.content().equals(recoveryText)).findFirst().orElseThrow();
            FileBuffer restored = FileBuffer.createScratch("restored.md", "");
            restored.setContent(entry.content(), true);
            assertEquals(recoveryText, restored.getContent()); assertTrue(restored.isModified());
        } finally { disposeEditor(editor); }
    }

    @Test
    void sourceWidthFastPathKeepsSplitHistorySnapshotsPersistenceAndThemeBinding() throws Exception {
        assumeSwingAvailable();
        String original = "# Source\n" + ("W".repeat(120) + "\n").repeat(20);
        Path file = Files.writeString(tempDir.resolve("source-width.md"), original);
        Texteditor editor = createEditor(tempDir.resolve("source-width-home"), file);
        try {
            assertTrue(awaitUiState(() -> !editor.stagedStartupPending));
            onEdt(() -> {
                FileBuffer buffer = editor.getCurrentBuffer();
                var document = buffer.getDocument();
                long revision = buffer.documentRevision();
                editor.splitWindow(true);
                for (EditorPane pane : editor.editorPanes) {
                    assertSame(document, pane.getTextArea().getDocument());
                    assertTrue(pane.getTextArea().getUI().getRootView(pane.getTextArea()).getView(0) instanceof SourcePlainView);
                }
                int[] events = {0};
                document.addDocumentListener(new javax.swing.event.DocumentListener() {
                    public void insertUpdate(javax.swing.event.DocumentEvent e) { events[0]++; }
                    public void removeUpdate(javax.swing.event.DocumentEvent e) { events[0]++; }
                    public void changedUpdate(javax.swing.event.DocumentEvent e) { events[0]++; }
                });
                int offset = original.indexOf('W');
                editor.writingArea.insert("x", offset);
                assertTrue(buffer.isModified());
                assertEquals(editor.writingArea.getText(), buffer.textSnapshot().text());
                buffer.getUndoManager().undo();
                assertEquals(original, buffer.getContent());
                buffer.getUndoManager().redo();
                buffer.getUndoManager().undo();
                assertEquals(4, events[0]);
                assertEquals(revision + 4, buffer.documentRevision());
                for (EditorPane pane : editor.editorPanes) assertEquals(original, pane.getTextArea().getText());
                var snapshot = buffer.textSnapshot();
                editor.configManager.set("theme", "shed-light");
                editor.sessionConfigController.applyRuntimeConfigFromSettings();
                for (EditorPane pane : editor.editorPanes) {
                    var area = pane.getTextArea();
                    assertTrue(area.getUI().getRootView(area).getView(0) instanceof SourcePlainView);
                    area.setLineWrap(true);
                    assertTrue(area.getUI().getRootView(area).getView(0) instanceof javax.swing.text.WrappedPlainView);
                    area.setLineWrap(false);
                    assertTrue(area.getUI().getRootView(area).getView(0) instanceof SourcePlainView);
                }
                assertSame(snapshot, buffer.textSnapshot());
                assertEquals(revision + 4, buffer.documentRevision());
                assertEquals(4, events[0]);
                return null;
            });
            FileBuffer buffer = onEdt(editor::getCurrentBuffer);
            FileBuffer.SaveSnapshot save = onEdt(() -> buffer.captureSaveSnapshot(null));
            buffer.writeSaveSnapshot(save);
            onEdt(() -> { buffer.completeSaveSnapshot(save); return null; });
            assertEquals(original, Files.readString(file));
            assertEquals(original, new FileBuffer(file.toFile()).getContent());
            onEdt(() -> { editor.writingArea.insert("draft", 0); editor.persistRecoverySnapshots(); return null; });
            assertTrue(awaitUiState(() -> {
                try {
                    var journal = RecoveryJournal.read(editor.recoveryStoreDir.toPath());
                    return journal != null && journal.entries().stream().anyMatch(e -> e.content().equals("draft" + original));
                } catch (java.io.IOException error) { return false; }
            }));
        } finally { disposeEditor(editor); }
    }

    @Test
    void markdownHighlightCoalescesSaturatedWorkersAndRejectsSupersededBuffers() throws Exception {
        assumeSwingAvailable();
        Path file = Files.writeString(tempDir.resolve("highlight-first.md"), "# First `code`\n".repeat(1000));
        Texteditor editor = createEditor(tempDir.resolve("highlight-queue-home"), file);
        var release = new java.util.concurrent.CountDownLatch(1);
        var started = new java.util.concurrent.CountDownLatch(1);
        var executor = java.util.concurrent.Executors.newSingleThreadExecutor();
        try {
            assertTrue(awaitUiState(() -> !editor.stagedStartupPending));
            executor.submit(() -> { started.countDown(); release.await(); return null; });
            assertTrue(started.await(5, TimeUnit.SECONDS));
            onEdt(() -> {
                editor.shutdownSyntaxUi(); editor.asyncJobService.shutdownNow();
                editor.asyncJobService = new AsyncJobService(executor, 200, null);
                editor.syntaxUiController = new SyntaxUiController(editor);
                FileBuffer original = editor.getCurrentBuffer();
                long revision = original.documentRevision(); var source = original.textSnapshot();
                for (int i = 0; i < 50; i++) editor.applySyntaxHighlighting();
                assertEquals(1, editor.asyncJobService.list().stream().filter(j -> j.getDescription().equals("Syntax highlighting")).count());
                FileBuffer other = new FileBuffer(Files.writeString(tempDir.resolve("highlight-other.md"), "# Second\n`other`\n").toFile());
                editor.buffers.add(other); editor.loadBufferIntoEditor(other);
                for (int i = 0; i < 50; i++) editor.applySyntaxHighlighting();
                for (int i = 0; i < 12; i++) {
                    FileBuffer extra = new FileBuffer(Files.writeString(tempDir.resolve("highlight-extra-" + i + ".md"), "# Extra " + i + "\n").toFile());
                    editor.buffers.add(extra); editor.loadBufferIntoEditor(extra); editor.applySyntaxHighlighting();
                    assertTrue(((java.util.Map<?, ?>) syntaxField(editor, "markdownCaches")).size() <= 8);
                }
                assertEquals(1, editor.asyncJobService.list().stream().filter(j -> j.getDescription().equals("Syntax highlighting")).count());
                assertSame(source, original.textSnapshot()); assertEquals(revision, original.documentRevision());
                return null;
            });
            release.countDown();
            assertTrue(awaitUiState(() -> markdownSyntaxSettled(editor)));
            onEdt(() -> {
                assertCurrentMarkdownSyntax(editor);
                editor.shutdownSyntaxUi();
                assertTrue(((java.util.Map<?, ?>) syntaxField(editor, "markdownCaches")).isEmpty());
                assertFalse(editor.syntaxUiController.markdownSyntaxIsCurrent(editor.writingArea.getDocument()));
                return null;
            });
        } finally { release.countDown(); disposeEditor(editor); executor.shutdownNow(); }
    }

    @Test
    void markdownHighlightSharesCheckpointsAcrossSplitsEditsUndoAndBufferReopening() throws Exception {
        assumeSwingAvailable();
        Path file = Files.writeString(tempDir.resolve("highlight-splits.md"), "# Header `code`\nplain\n".repeat(2000));
        Texteditor editor = createEditor(tempDir.resolve("highlight-split-home"), file);
        try {
            assertTrue(awaitUiState(() -> !editor.stagedStartupPending));
            FileBuffer buffer = onEdt(editor::getCurrentBuffer);
            String original = onEdt(buffer::getContent);
            onEdt(() -> {
                editor.writingArea.setCaretPosition(editor.writingArea.getDocument().getLength());
                editor.splitWindow(true); editor.applySyntaxHighlighting(); return null;
            });
            assertTrue(awaitUiState(() -> markdownSyntaxSettled(editor)));
            onEdt(() -> {
                assertCurrentMarkdownSyntax(editor);
                assertMarkdownViewportPixelsMatchFullScan(editor);
                assertEquals(1, ((java.util.Map<?, ?>) syntaxField(editor, "markdownCaches")).size());
                editor.writingArea.insert("```java\n/* open\n", 0);
                assertFalse(editor.syntaxUiController.markdownSyntaxIsCurrent(editor.writingArea.getDocument()));
                editor.applySyntaxHighlighting(); return null;
            });
            assertTrue(awaitUiState(() -> markdownSyntaxSettled(editor)));
            onEdt(() -> {
                assertCurrentMarkdownSyntax(editor); buffer.getUndoManager().undo(); editor.applySyntaxHighlighting(); return null;
            });
            assertTrue(awaitUiState(() -> markdownSyntaxSettled(editor)));
            onEdt(() -> {
                assertEquals(original, buffer.getContent()); assertCurrentMarkdownSyntax(editor);
                buffer.getUndoManager().redo(); editor.applySyntaxHighlighting(); return null;
            });
            assertTrue(awaitUiState(() -> markdownSyntaxSettled(editor)));
            onEdt(() -> {
                assertCurrentMarkdownSyntax(editor); buffer.getUndoManager().undo();
                editor.activateEditorPane(editor.editorPanes.get(0)); editor.applySyntaxHighlighting(); return null;
            });
            assertTrue(awaitUiState(() -> markdownSyntaxSettled(editor)));
            onEdt(() -> {
                assertCurrentMarkdownSyntax(editor);
                assertSame(buffer.getDocument(), editor.editorPanes.get(1).getTextArea().getDocument());
                assertEquals(original, buffer.getContent());
                FileBuffer other = new FileBuffer(Files.writeString(tempDir.resolve("other.md"), "# Other\n").toFile());
                editor.buffers.add(other);
                editor.commandHandler.execute("bd!");
                assertFalse(((java.util.Map<?, ?>) syntaxField(editor, "markdownCaches")).containsKey(buffer));
                FileBuffer reopened = new FileBuffer(file.toFile());
                editor.buffers.add(reopened); editor.loadBufferIntoEditor(reopened); editor.applySyntaxHighlighting(); return null;
            });
            assertTrue(awaitUiState(() -> markdownSyntaxSettled(editor)));
            onEdt(() -> { assertCurrentMarkdownSyntax(editor); assertEquals(original, editor.getCurrentBuffer().getContent()); return null; });
        } finally { disposeEditor(editor); }
    }

    private static boolean markdownSyntaxSettled(Texteditor editor) throws Exception {
        if ((int) syntaxField(editor, "syntaxJobId") != -1 || syntaxField(editor, "pendingSyntax") != null) return false;
        var timer = (javax.swing.Timer) syntaxField(editor, "syntaxHighlightTimer");
        if (timer != null && timer.isRunning()) return false;
        try { assertCurrentMarkdownSyntax(editor); return true; }
        catch (AssertionError intermediateViewportOrRevision) { return false; }
    }

    private static void assertMarkdownViewportPixelsMatchFullScan(Texteditor editor) throws Exception {
        var method = SyntaxUiController.class.getDeclaredMethod("syntaxColors"); method.setAccessible(true);
        @SuppressWarnings("unchecked") var colors = (java.util.Map<GrammarHighlightService.Scope, Color>) method.invoke(editor.syntaxUiController);
        var original = new ArrayList<>(editor.syntaxForegroundSpans);
        int[] bounded = markdownViewportPixels(editor.writingArea);
        try {
            editor.syntaxForegroundSpans.clear();
            for (var token : new GrammarHighlightService().highlightSnapshot(editor.getCurrentBuffer().textSnapshot().text(), FileType.MARKDOWN)) {
                if (colors.get(token.scope()) != null) editor.syntaxForegroundSpans.add(new SyntaxSpan(token.start(), token.end(), colors.get(token.scope())));
            }
            org.junit.jupiter.api.Assertions.assertArrayEquals(bounded, markdownViewportPixels(editor.writingArea));
        } finally {
            editor.syntaxForegroundSpans.clear(); editor.syntaxForegroundSpans.addAll(original);
        }
    }

    private static int[] markdownViewportPixels(JTextArea area) {
        Rectangle visible = area.getVisibleRect();
        var image = new java.awt.image.BufferedImage(visible.width, visible.height, java.awt.image.BufferedImage.TYPE_INT_ARGB);
        var graphics = image.createGraphics();
        try { graphics.translate(-visible.x, -visible.y); area.paint(graphics); }
        finally { graphics.dispose(); }
        return image.getRGB(0, 0, visible.width, visible.height, null, 0, visible.width);
    }

    private static Object syntaxField(Texteditor editor, String name) throws Exception {
        var field = SyntaxUiController.class.getDeclaredField(name); field.setAccessible(true);
        return field.get(editor.syntaxUiController);
    }

    private static void assertCurrentMarkdownSyntax(Texteditor editor) throws Exception {
        assertTrue(editor.syntaxUiController.markdownSyntaxIsCurrent(editor.writingArea.getDocument()));
        var snapshot = editor.getCurrentBuffer().textSnapshot();
        var viewport = SyntaxUiController.class.getDeclaredMethod("syntaxViewport", VersionedTextSnapshot.class); viewport.setAccessible(true);
        Object range = viewport.invoke(editor.syntaxUiController, snapshot);
        var start = range.getClass().getDeclaredMethod("start"); start.setAccessible(true);
        var end = range.getClass().getDeclaredMethod("end"); end.setAccessible(true);
        int first = (int) start.invoke(range), last = (int) end.invoke(range);
        var colorsMethod = SyntaxUiController.class.getDeclaredMethod("syntaxColors"); colorsMethod.setAccessible(true);
        @SuppressWarnings("unchecked") var colors = (java.util.Map<GrammarHighlightService.Scope, java.awt.Color>) colorsMethod.invoke(editor.syntaxUiController);
        List<String> expected = new GrammarHighlightService().highlightSnapshot(snapshot.text(), FileType.MARKDOWN).stream()
            .filter(t -> t.start() >= first && t.end() <= last && colors.get(t.scope()) != null)
            .map(t -> t.start() + ":" + t.end() + ":" + colors.get(t.scope()).getRGB()).toList();
        assertEquals(expected, editor.syntaxForegroundSpans.stream().map(s -> s.start + ":" + s.end + ":" + s.color.getRGB()).toList());
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
