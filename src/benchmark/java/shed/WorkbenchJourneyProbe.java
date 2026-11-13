package shed;

import java.awt.*;
import java.awt.event.*;
import java.awt.image.BufferedImage;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import javax.imageio.ImageIO;
import javax.swing.*;

/** Actual production journeys against one supplied JAR, with app-owned captures and isolated state. */
public final class WorkbenchJourneyProbe {
    private final Path output;
    private final List<Map<String, Object>> captures = new ArrayList<>();
    private final List<String> checks = new ArrayList<>();
    private final AtomicReference<Throwable> asynchronousFailure = new AtomicReference<>();
    private Texteditor editor;
    private Path fixture, project, source, target;
    private String nativeInput = "not attempted";

    private WorkbenchJourneyProbe(Path output) { this.output = output; }

    public static void main(String[] args) {
        int status = 0;
        WorkbenchJourneyProbe probe = new WorkbenchJourneyProbe(Path.of(args[0]).toAbsolutePath());
        try { probe.run(); }
        catch (Throwable failure) { failure.printStackTrace(); status = 1; }
        finally {
            try {
                probe.report(status);
                if (probe.editor != null) edt(() -> {
                    probe.editor.closingDown = true;
                    if (probe.editor.asyncJobService != null) probe.editor.asyncJobService.shutdownNow();
                    if (probe.editor.fileWatcherService != null) probe.editor.fileWatcherService.stop();
                    if (probe.editor.testController != null) probe.editor.testController.shutdown();
                    if (probe.editor.backupScheduler != null) probe.editor.backupScheduler.close();
                    if (probe.editor.toolWindowHost != null) probe.editor.toolWindowHost.close();
                    for (Window window : probe.editor.getOwnedWindows()) window.dispose();
                    probe.editor.dispose(); return null;
                });
                if (probe.fixture != null) try (var paths = Files.walk(probe.fixture)) {
                    for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
                }
            } catch (Throwable failure) { failure.printStackTrace(); status = 1; }
        }
        System.exit(status);
    }

    private void run() throws Exception {
        Files.createDirectories(output);
        fixture = Files.createTempDirectory("shed-workbench-journey-").toRealPath();
        Path home = Files.createDirectories(fixture.resolve("home/.shed"));
        Files.writeString(home.resolve("config.toml"), "schema_version = 1\n\"keymap.profile\" = \"plain\"\n\"session.restore.on.start\" = false\n");
        System.setProperty("user.home", home.getParent().toString());
        project = Files.createDirectories(fixture.resolve("a-workspace-with-a-long-name"));
        source = Files.writeString(Files.createDirectories(project.resolve("src/service")).resolve("Source.txt"), "alpha needle\nbeta needle\nlast\n");
        target = Files.writeString(Files.createDirectories(project.resolve("tests/service")).resolve("Source.txt"), "one needle\ntwo\nthree\n");
        editor = edt(() -> new Texteditor(new String[] {source.toString()}));
        await("production startup", () -> !editor.stagedStartupPending && current(source));
        if (Boolean.getBoolean("shed.journey.traceNative")) Toolkit.getDefaultToolkit().addAWTEventListener(event -> {
            if (event instanceof KeyEvent key && key.getID() == KeyEvent.KEY_PRESSED) System.out.println("SHED_NATIVE_KEY " + key.getKeyCode() + " mods=" + key.getModifiersEx() + " source=" + key.getComponent().getClass().getName() + " path=" + editor.getCurrentBuffer().getFilePath());
        }, AWTEvent.KEY_EVENT_MASK);
        edt(() -> { if (!Boolean.parseBoolean(System.getProperty("shed.journey.nativeInput", "false"))) editor.setAutoRequestFocus(false);
            editor.setSize(1120, 820); foreground(); return null; });
        openWorkspace();
        editorJourney();
        searchJourney();
        appearanceAndLayoutJourney();
        reviewJourney();
        taskFeedbackJourney();
        check("all four production journeys plus explicit task failure/cancellation");
    }

    private void openWorkspace() throws Exception {
        begin(() -> editor.openFolder());
        await("folder chooser", () -> visibleDialogWith(JFileChooser.class) != null);
        JDialog chooser = edt(() -> visibleDialogWith(JFileChooser.class));
        edt(() -> { JFileChooser fileChooser = descendants(chooser, JFileChooser.class).getFirst(); fileChooser.setSelectedFile(project.toFile()); fileChooser.approveSelection(); return null; });
        await("workspace active", () -> project.equals(editor.workspaceController.activeRoot()));
        check("open workspace through actual folder chooser");
    }

    private void editorJourney() throws Exception {
        begin(() -> namedAccessible(editor.workbenchHeader, JButton.class, "Find file").doClick());
        JDialog picker = dialog("Find File");
        edt(() -> { require(WorkbenchAccessibilityProbe.edit(picker, "Find File filter", "tests/service/Source.txt:2:1"), "Accessible picker filter unavailable"); return null; });
        await("file query", () -> !descendants(picker, JList.class).getFirst().isSelectionEmpty());
        capture(picker, "file-finder-location");
        edt(() -> { descendants(picker, JTextField.class).getFirst().postActionEvent(); return null; });
        await("target opened", () -> current(target));
        String original = edt(() -> editor.getCurrentBuffer().getContent());
        edt(() -> { editor.writingArea.setCaretPosition(0); foreground(); if (Boolean.parseBoolean(System.getProperty("shed.journey.nativeInput", "false"))) editor.toFront(); editor.requestActivePaneFocus(); return null; });
        Robot robot = null;
        try {
            if (!Boolean.parseBoolean(System.getProperty("shed.journey.nativeInput", "false"))) throw new AWTException("native injection disabled for this functional run");
            if (!OwnedWindowCapture.nativeInputAuthorized()) throw new AWTException("macOS accessibility permission is unavailable; no native keys sent");
            robot = new Robot();
            robot.setAutoDelay(40);
            long focusDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            while (!edt(() -> editor.writingArea.isFocusOwner() && KeyboardFocusManager.getCurrentKeyboardFocusManager().getFocusedWindow() == editor)
                && System.nanoTime() < focusDeadline) Thread.sleep(30);
            if (!edt(() -> editor.writingArea.isFocusOwner())) nativeInput = "unavailable: desktop did not grant owned editor focus";
        } catch (AWTException | IllegalStateException unavailable) { nativeInput = "unavailable: " + unavailable.getMessage(); }
        boolean nativeReady = edt(() -> editor.writingArea.isFocusOwner()) && robot != null && !nativeInput.startsWith("unavailable");
        edt(() -> { require(editor.writingArea.isEditable() && !(editor.getActivePane().getAlternateComponent() instanceof FileTreePanel), "File finder did not return to an editable source surface"); return null; });
        if (nativeReady) {
            key(robot, KeyEvent.VK_X, false, false);
            long inputDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
            while (edt(() -> editor.getCurrentBuffer().getContent().equals(original)) && System.nanoTime() < inputDeadline) Thread.sleep(30);
            if (edt(() -> editor.getCurrentBuffer().getContent().equals(original))) {
                nativeReady = false; nativeInput = "unavailable: owned editor focus observed but Robot ASCII injection was not observed";
            } else require(edt(() -> editor.getCurrentBuffer().getContent().equals("x" + original)), "Native ASCII produced unexpected source text");
        }
        if (nativeReady) {
            key(robot, KeyEvent.VK_Z, true, false);
            await("native undo", () -> editor.getCurrentBuffer().getContent().equals(original));
            key(robot, KeyEvent.VK_Z, true, true);
            await("native redo", () -> editor.getCurrentBuffer().getContent().equals("x" + original));
            key(robot, KeyEvent.VK_S, true, false);
            nativeInput = "observed: ASCII x, platform undo, redo and save shortcuts with owned-focus checks";
        } else {
            edt(() -> { var surface = editor.documents.activeSurface(); surface.replace(0, 0, "x"); editor.inputController.performMenuAction(PlainKeymap.Action.UNDO); require(editor.getCurrentBuffer().getContent().equals(original), "Undo failed"); editor.inputController.performMenuAction(PlainKeymap.Action.REDO); editor.inputController.performMenuAction(PlainKeymap.Action.SAVE); return null; });
        }
        await("save completion", () -> !editor.getCurrentBuffer().isModified() && Files.readString(target).equals("x" + original));
        capture(editor, "edited-saved-requested-focus");
        edt(() -> {
            var list = namedAccessible(editor.openEditorsBar, JList.class, "Open editors");
            list.requestFocusInWindow();
            int index = index(source); list.setSelectedIndex(index);
            require(current(source), "Document list did not activate the source");
            list.setSelectedIndex(index(target));
            require(current(target), "Document list did not reactivate the target");
            editor.documents.activeSurface().replace(0, 0, "discarded draft "); return null;
        });
        begin(() -> namedAccessible(editor.openEditorsBar, JButton.class, "Close selected editor").doClick());
        JDialog dirty = dialog("Unsaved Changes");
        edt(() -> { captureRoot(dirty, "dirty-close-confirmation"); button(dirty, "No").doClick(); return null; });
        await("close cancelled", () -> !dirty.isShowing() && current(target));
        edt(() -> { require(editor.getCurrentBuffer().isModified(), "Cancel lost dirty document"); return null; });
        begin(() -> namedAccessible(editor.openEditorsBar, JButton.class, "Close selected editor").doClick());
        JDialog discard = dialog("Unsaved Changes");
        edt(() -> { button(discard, "Yes").doClick(); return null; });
        await("dirty document closed", () -> !editor.documents.isOpen(target));
        require(Files.readString(target).equals("x" + original), "Discard changed the saved file");
        check("workspace/file query/location/native-or-port edit/undo/redo/save/document selection/dirty close with cancel and discard");
    }

    private void searchJourney() throws Exception {
        edt(() -> { editor.showToolWindow(ToolWindowHost.Tab.SEARCH); var query = descendants(editor.toolWindowHost.searchPanel().component(), JTextField.class).getFirst(); query.setText("needle"); button(editor.toolWindowHost.searchPanel().component(), "Search").doClick(); return null; });
        await("workspace search", () -> !editor.paletteController.workspaceSearch().snapshot().running() && editor.paletteController.workspaceSearch().snapshot().matches().size() >= 2);
        edt(() -> { var results = descendants(editor.toolWindowHost.searchPanel().component(), JList.class).getFirst(); results.setSelectedIndex(0); results.requestFocusInWindow(); invokeKey(results, KeyEvent.VK_ENTER); return null; });
        capture(editor, "search-result-navigation");
        edt(() -> {
            editor.problemsController.recordQuickfixEntries(List.of(new QuickfixService.Entry(target.toString(), 2, 2, "Fixture diagnostic with a long explanation that remains available in the tooltip", "journey", QuickfixService.Severity.ERROR)));
            editor.showToolWindow(ToolWindowHost.Tab.PROBLEMS);
            descendants(editor.toolWindowHost.problemsPanel().component(), JTextField.class).getFirst().setText("Fixture diagnostic");
            var table = descendants(editor.toolWindowHost.problemsPanel().component(), JTable.class).getFirst();
            require(table.getRowCount() == 1, "Diagnostic missing"); table.setRowSelectionInterval(0, 0); table.requestFocusInWindow(); invokeKey(table, KeyEvent.VK_ENTER); return null;
        });
        await("diagnostic source", () -> current(target) && editor.writingArea.getCaretPosition() == "xone needle\n".length() + 1);
        capture(editor, "problem-navigation");
        edt(() -> { namedAccessible(editor.toolWindowHost, JButton.class, "Hide tools").doClick(); return null; });
        await("tools hidden", () -> !editor.toolWindowHost.isVisible());
        check("search result Enter, retained diagnostic Enter, explicit tool hide");
    }

    private void appearanceAndLayoutJourney() throws Exception {
        edt(() -> { namedAccessible(editor.workbenchHeader, JButton.class, "Settings").doClick(); return null; });
        JDialog settings = dialog("Settings");
        edt(() -> { require(WorkbenchAccessibilityProbe.edit(settings, "Search settings", "ui.zoom"), "Settings search unavailable"); return null; });
        await("scale setting", () -> descendants(settings, JTextField.class).stream().anyMatch(field -> "ui.zoom".equals(field.getClientProperty("shed.setting.key"))));
        edt(() -> {
            var scale = descendants(settings, JTextField.class).stream().filter(field -> "ui.zoom".equals(field.getClientProperty("shed.setting.key"))).findFirst().orElseThrow();
            scale.setText("1.5"); scale.postActionEvent(); return null;
        });
        await("scale applied", () -> editor.configManager.getUiZoom() == 1.5);
        capture(settings, "settings-scale-150");
        edt(() -> { settings.dispose(); editor.settingsApplication.update("theme", "shed-light"); editor.setSize(760, 780); editor.showToolWindow(ToolWindowHost.Tab.PROBLEMS); return null; });
        capture(editor, "narrow-light-150");
        edt(() -> { editor.settingsApplication.update("ui.zoom", "1.0"); editor.setSize(1120, 820); editor.showToolWindow(ToolWindowHost.Tab.SEARCH); editor.editorToolSplit.setDividerLocation(310); namedAccessible(editor.toolWindowHost, JButton.class, "Detach selected tool").doClick(); return null; });
        JDialog floating = dialog("Search");
        edt(() -> { floating.setBounds(60, 70, 820, 500); return null; });
        capture(floating, "detached-search-light");
        System.out.println("SHED_FLOATING_BEFORE_SAVE " + edt(() -> floating.getBounds() + " " + editor.workbenchPlacementState.toList()));
        Path session = fixture.resolve("journey-session.json");
        edt(() -> { require(editor.sessionConfigController.saveSession(session.toString()).startsWith("Session saved:"), "Session save failed"); floating.dispose(); return null; });
        await("reattached search", () -> !floating.isShowing() && editor.toolWindowHost.isSelected(ToolWindowHost.Tab.SEARCH));
        edt(() -> { editor.settingsApplication.update("theme", "shed-dark"); require(editor.sessionConfigController.loadSession(session.toString(), true).startsWith("Restored session:"), "Session restore failed"); return null; });
        JDialog restored = dialog("Search");
        await("restored floating bounds after native window-open events", () -> restored.getBounds().equals(new Rectangle(60, 70, 820, 500)));
        edt(() -> { require(restored.getBounds().equals(new Rectangle(60, 70, 820, 500)), "Detached bounds changed: " + restored.getBounds() + " screen=" + GraphicsEnvironment.getLocalGraphicsEnvironment().getMaximumWindowBounds() + " placements=" + editor.workbenchPlacementState.toList()); require(editor.configManager.get("theme").equals("shed-light"), "Session theme not restored"); editor.toolWindowHost.reattachAll(); return null; });
        await("all tools docked", () -> !restored.isShowing());
        edt(() -> { editor.settingsApplication.update("theme", "shed-dark"); editor.documents.split(true); editor.requestActivePaneFocus(); return null; });
        capture(editor, "restored-session-active-split");
        edt(() -> { editor.documents.closeWindow(true); editor.hideToolWindow(); return null; });
        check("settings control applies/persists scale, light/dark, narrow window, detach/close/reattach, actual session restore and active split");
    }

    private void reviewJourney() throws Exception {
        edt(() -> { editor.documents.activate(editor.documents.documents().stream().filter(document -> target.toString().equals(document.path())).findFirst().orElseThrow().id()); return null; });
        String original = edt(() -> editor.getCurrentBuffer().getContent());
        var scope = new AgentReviewPanel.Scope(project, Path.of(editor.configManager.getSessionDirectory()), editor.workspaceDocuments());
        var queries = new WorkspaceQueryService(project, AgentPolicy.readOnly(), scope.documents());
        scope.service().propose("Explicit UI journey", "workbench", "review", List.of(new WorkspaceProposalService.Input("tests/service/Source.txt", queries.snapshot(queries.workspaceId(), "tests/service/Source.txt").version(), original.replace("three", "THREE"))));
        edt(() -> { editor.toolWindowHost.showAgentReview(); return null; });
        await("human review ready", () -> named(editor.toolWindowHost, JButton.class, "agent.apply").isEnabled());
        require(Files.readString(target).equals(original), "Opening review changed disk");
        edt(() -> { require(editor.getCurrentBuffer().getContent().equals(original), "Opening review changed buffer"); named(editor.toolWindowHost, JButton.class, "agent.apply").requestFocusInWindow(); return null; });
        capture(editor, "review-pending-requested-focus");
        edt(() -> { editor.settingsApplication.update("theme", "shed-light"); editor.settingsApplication.update("ui.zoom", "1.5"); editor.setSize(760, 780); return null; });
        edt(() -> {
            editor.validate();
            for (String action : List.of("agent.apply", "agent.reject")) {
                JButton button = named(editor.toolWindowHost, JButton.class, action);
                Rectangle full = new Rectangle(0, 0, button.getWidth(), button.getHeight());
                reveal(button, full);
                require(button.getVisibleRect().contains(full), action + " cannot be fully reached by scrolling");
                Rectangle bounds = SwingUtilities.convertRectangle(button.getParent(), button.getBounds(), editor.getRootPane());
                require(editor.getRootPane().getVisibleRect().contains(bounds), action + " is clipped in narrow/enlarged review: " + bounds + " root=" + editor.getRootPane().getVisibleRect());
            }
            return null;
        });
        capture(editor, "review-narrow-light-150");
        edt(() -> { var proposed = named(editor.toolWindowHost, JTextArea.class, "agent.proposed"); proposed.append("human draft\n"); require(!named(editor.toolWindowHost, JButton.class, "agent.apply").isEnabled(), "Unsaved review must disable Apply"); return null; });
        capture(editor, "review-local-draft");
        edt(() -> { named(editor.toolWindowHost, JButton.class, "agent.discard").doClick(); return null; });
        await("review draft discarded", () -> named(editor.toolWindowHost, JButton.class, "agent.apply").isEnabled());
        edt(() -> { editor.settingsApplication.update("theme", "shed-dark"); editor.settingsApplication.update("ui.zoom", "1.0"); editor.setSize(1120, 820); named(editor.toolWindowHost, JButton.class, "agent.apply").doClick(); return null; });
        await("review applied", () -> named(editor.toolWindowHost, JButton.class, "agent.undo").isEnabled() && editor.getCurrentBuffer().getContent().contains("THREE"));
        require(Files.readString(target).equals(original), "Open-buffer approval unexpectedly saved disk");
        capture(editor, "review-applied-unsaved");
        edt(() -> { named(editor.toolWindowHost, JButton.class, "agent.undo").doClick(); return null; });
        await("review undo", () -> editor.getCurrentBuffer().getContent().equals(original));
        check("normal human proposal review; opening has no mutation, explicit Apply changes open buffer only, explicit Undo restores it");
    }

    private void taskFeedbackJourney() throws Exception {
        if (System.getProperty("os.name").startsWith("Windows")) { check("POSIX task visual fixture unavailable on Windows"); return; }
        Files.writeString(project.resolve(".shedtasks"), "schema_version = 1\n[task.check]\ncommand = \"while [ ! -f release ]; do sleep 0.05; done; printf finished; exit 2\"\npresentation = \"always\"\n");
        edt(() -> {
            editor.showToolWindow(ToolWindowHost.Tab.TASKS);
            var tasks = namedAccessible(editor.toolWindowHost, JList.class, "Workspace tasks"); tasks.setSelectedValue("check", true);
            named(editor.toolWindowHost, JButton.class, "tasks.run").doClick(); return null;
        });
        int id = edt(() -> editor.asyncJobService.list().stream().filter(job -> job.getDescription().startsWith("task ")).mapToInt(AsyncJobService.JobSnapshot::getId).max().orElseThrow());
        revealTaskOutput();
        capture(editor, "task-running");
        edt(() -> { namedAccessible(editor.toolWindowHost, JButton.class, "Hide tools").doClick(); return null; });
        Files.writeString(project.resolve("release"), "finish");
        await("task command failed", () -> Integer.valueOf(2).equals(editor.jobQuickfixController.taskExitCodeForPanel(id)));
        edt(() -> { require(current(target), "Background task completion replaced source"); editor.showToolWindow(ToolWindowHost.Tab.TASKS); return null; });
        revealTaskOutput();
        capture(editor, "task-failed-retained-source");
        Files.delete(project.resolve("release"));
        edt(() -> { named(editor.toolWindowHost, JButton.class, "tasks.run").doClick(); named(editor.toolWindowHost, JButton.class, "tasks.cancel").doClick(); return null; });
        await("task cancelled", () -> namedAccessible(editor.toolWindowHost, JTextArea.class, "Task output").getText().contains("Task cancelled"));
        revealTaskOutput();
        capture(editor, "task-cancelled");
        check("explicit task Run, nonzero exit, hidden-panel completion retains source, explicit Cancel and retained output state");
    }

    private static void foreground() {
        if (!Boolean.parseBoolean(System.getProperty("shed.journey.nativeInput", "false"))) return;
        if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.APP_REQUEST_FOREGROUND)) Desktop.getDesktop().requestForeground(true);
    }

    private static void reveal(JComponent component, Rectangle area) {
        component.scrollRectToVisible(area);
        for (Container parent = component.getParent(); parent != null; parent = parent.getParent()) if (parent instanceof JViewport view)
            view.scrollRectToVisible(SwingUtilities.convertRectangle(component, area, view));
    }

    private void key(Robot robot, int key, boolean mod, boolean shift) throws Exception {
        edt(() -> { foreground(); editor.toFront(); editor.requestActivePaneFocus(); return null; });
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (!edt(() -> editor.writingArea.isFocusOwner() && KeyboardFocusManager.getCurrentKeyboardFocusManager().getFocusedWindow() == editor)
            && System.nanoTime() < deadline) Thread.sleep(20);
        require(edt(() -> editor.writingArea.isFocusOwner() && KeyboardFocusManager.getCurrentKeyboardFocusManager().getFocusedWindow() == editor), "Refusing native key without owned editor focus");
        int modifier = System.getProperty("os.name").startsWith("Mac") ? KeyEvent.VK_META : KeyEvent.VK_CONTROL;
        try { if (mod) robot.keyPress(modifier); if (shift) robot.keyPress(KeyEvent.VK_SHIFT); robot.keyPress(key); robot.keyRelease(key); }
        finally { if (shift) robot.keyRelease(KeyEvent.VK_SHIFT); if (mod) robot.keyRelease(modifier); }
        robot.waitForIdle();
        System.out.println("SHED_NATIVE_AFTER " + key + " " + edt(() -> editor.getCurrentBuffer().getFilePath() + " focus=" + editor.writingArea.isFocusOwner() + " text=" + editor.getCurrentBuffer().getContent().replace("\n", "|")));
    }

    private boolean current(Path path) { return editor.getCurrentBuffer() != null && path.toString().equals(editor.getCurrentBuffer().getFilePath()); }
    private int index(Path path) { var documents = editor.documents.documents(); for (int i = 0; i < documents.size(); i++) if (path.toString().equals(documents.get(i).path())) return i; throw new IllegalStateException("Document missing: " + path); }
    private void begin(Runnable action) { SwingUtilities.invokeLater(() -> { try { action.run(); } catch (Throwable failure) { asynchronousFailure.set(failure); } }); }
    private JDialog dialog(String title) throws Exception { await("dialog " + title, () -> visibleDialog(title) != null); return edt(() -> visibleDialog(title)); }
    private JDialog visibleDialog(String title) { return Arrays.stream(editor.getOwnedWindows()).filter(window -> window instanceof JDialog dialog && dialog.isShowing() && title.equals(dialog.getTitle())).map(JDialog.class::cast).findFirst().orElse(null); }
    private JDialog visibleDialogWith(Class<? extends Component> type) { return Arrays.stream(editor.getOwnedWindows()).filter(window -> window instanceof JDialog dialog && dialog.isShowing() && !descendants(dialog, type).isEmpty()).map(JDialog.class::cast).findFirst().orElse(null); }
    private void await(String description, Callable<Boolean> condition) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(60);
        while (System.nanoTime() < deadline) { if (asynchronousFailure.get() != null) throw new IllegalStateException("UI action failed", asynchronousFailure.get()); if (edt(condition)) return; Thread.sleep(30); }
        throw new IllegalStateException("Timed out: " + description);
    }
    private void captureRoot(Window window, String name) throws Exception {
        Path path = output.resolve(String.format(Locale.ROOT, "%02d-%s.png", captures.size() + 1, name));
        var root = ((RootPaneContainer) window).getRootPane();
        BufferedImage image = new BufferedImage(root.getWidth(), root.getHeight(), BufferedImage.TYPE_INT_RGB);
        var graphics = image.createGraphics(); root.printAll(graphics); graphics.dispose(); ImageIO.write(image, "png", path.toFile());
        Component focus = KeyboardFocusManager.getCurrentKeyboardFocusManager().getFocusOwner();
        String focused = focus == null ? "none" : focus.getAccessibleContext() == null ? focus.getClass().getSimpleName() : String.valueOf(focus.getAccessibleContext().getAccessibleName());
        Map<String, Object> row = new LinkedHashMap<>(); row.put("file", path.getFileName().toString());
        row.put("method", "actual production Swing root paint; native window decorations excluded"); row.put("focus", focused);
        row.put("width", root.getWidth()); row.put("height", root.getHeight()); row.put("active_document", editor.getCurrentBuffer().getFilePath());
        captures.add(row); System.out.println("SHED_JOURNEY_CAPTURE " + MiniJson.stringify(row));
    }
    private void capture(Window window, String name) throws Exception {
        Thread.sleep(150);
        edt(() -> { window.validate(); captureRoot(window, name); return null; });
        if (Boolean.getBoolean("shed.journey.nativeCapture")) {
            Rectangle bounds = edt(window::getBounds);
            Path nativePath = output.resolve(String.format(Locale.ROOT, "%02d-%s-native-attempt.png", captures.size(), name));
            try {
                edt(() -> { var root = ((RootPaneContainer) window).getRootPane(); root.paintImmediately(root.getVisibleRect()); Toolkit.getDefaultToolkit().sync(); return null; });
                OwnedWindowCapture.capture(window instanceof Frame frame ? edt(frame::getTitle) : edt(((Dialog) window)::getTitle), bounds.width, bounds.height, nativePath);
                captures.getLast().put("native_attempt", nativePath.getFileName().toString());
                captures.getLast().put("native_limit", "Background native backing-store freshness is unverified; root paint is the state evidence.");
            } catch (Exception failure) { captures.getLast().put("native_capture_unavailable", failure.getMessage()); }
        }
    }
    private void revealTaskOutput() throws Exception {
        edt(() -> {
            JTextArea area = namedAccessible(editor.toolWindowHost, JTextArea.class, "Task output");
            editor.validate();
            Rectangle readable = new Rectangle(0, 0, Math.min(100, area.getWidth()), area.getInsets().top + area.getFontMetrics(area.getFont()).getHeight() * 3);
            reveal(area, readable);
            require(area.getVisibleRect().contains(readable), "Selected task output has no readable rows");
            return null;
        });
    }
    private void check(String value) { checks.add(value); System.out.println("SHED_JOURNEY_OK " + value); }
    private void report(int status) throws Exception {
        Files.createDirectories(output); Map<String, Object> report = new LinkedHashMap<>(); report.put("exit_code", status); report.put("checks", checks); report.put("native_input", nativeInput); report.put("captures", captures);
        Path jar = Path.of(Texteditor.class.getProtectionDomain().getCodeSource().getLocation().toURI()); report.put("jar_sha256", HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(jar))));
        report.put("limits", "Local macOS production journeys with isolated fixtures; accessible actions exercise Swing's real bridge, not an external screen reader. Native input is ASCII plus shortcuts only; no IME, Windows or Linux native verification. No external agent process, download or network service is started.");
        if (editor != null) {
            report.put("accessibility", edt(() -> WorkbenchAccessibilityProbe.describe(editor)));
            report.put("source_state", edt(() -> Map.of("editable", editor.writingArea.isEditable(), "mode", editor.editorState.mode.name(),
                "focus", editor.writingArea.isFocusOwner(), "text", editor.getCurrentBuffer().getContent(), "focus_owner", String.valueOf(KeyboardFocusManager.getCurrentKeyboardFocusManager().getFocusOwner()))));
        }
        Files.writeString(output.resolve("journeys.json"), MiniJson.stringify(report) + "\n");
    }
    private static void require(boolean condition, String description) { if (!condition) throw new IllegalStateException(description); }
    private static JButton button(Container root, String title) { return descendants(root, JButton.class).stream().filter(button -> title.equals(button.getText()) && button.isShowing()).findFirst().orElseThrow(); }
    private static <T extends JComponent> T named(Container root, Class<T> type, String name) { return descendants(root, type).stream().filter(value -> name.equals(value.getName())).findFirst().orElseThrow(); }
    private static <T extends JComponent> T namedAccessible(Container root, Class<T> type, String name) { return descendants(root, type).stream().filter(value -> name.equals(value.getAccessibleContext().getAccessibleName())).findFirst().orElseThrow(); }
    private static void invokeKey(JComponent target, int key) { Object binding = target.getInputMap().get(KeyStroke.getKeyStroke(key, 0)); require(binding != null, "Missing key binding"); target.getActionMap().get(binding).actionPerformed(new ActionEvent(target, ActionEvent.ACTION_PERFORMED, "journey")); }
    private static <T extends Component> List<T> descendants(Container root, Class<T> type) { List<T> result = new ArrayList<>(); for (Component child : root.getComponents()) { if (type.isInstance(child)) result.add(type.cast(child)); if (child instanceof Container container) result.addAll(descendants(container, type)); } return result; }
    private static <T> T edt(Callable<T> action) throws Exception { if (SwingUtilities.isEventDispatchThread()) return action.call(); FutureTask<T> task = new FutureTask<>(action); SwingUtilities.invokeLater(task); return task.get(75, TimeUnit.SECONDS); }
}
