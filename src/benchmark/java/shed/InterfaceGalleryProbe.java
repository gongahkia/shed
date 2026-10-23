package shed;

import java.awt.Component;
import java.awt.Container;
import java.awt.BorderLayout;
import java.awt.Desktop;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.GridLayout;
import java.awt.Rectangle;
import java.awt.Window;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import javax.imageio.ImageIO;
import javax.swing.AbstractButton;
import javax.swing.BorderFactory;
import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JFileChooser;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JProgressBar;
import javax.swing.JRadioButton;
import javax.swing.JRootPane;
import javax.swing.JScrollPane;
import javax.swing.JTabbedPane;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.JToggleButton;
import javax.swing.JViewport;
import javax.swing.RootPaneContainer;
import javax.swing.SwingUtilities;

/**
 * Standalone desktop visual verification against a built Shed JAR; writes only its output directory
 * and a temporary fixture. Run with a graphical desktop and one output-directory argument.
 * Captures Swing root panes at 2x, excluding native title bars that Swing cannot paint.
 * No notebook kernel, remote service, Git mutation, or user configuration is used.
 */
public final class InterfaceGalleryProbe {
    private static final int CAPTURE_SCALE = 2;
    private static final Duration STARTUP_TIMEOUT = Duration.ofSeconds(180);
    private static final AtomicReference<Throwable> ASYNC_FAILURE = new AtomicReference<>();

    private final Path output;
    private final List<Map<String, Object>> captures = new ArrayList<>();
    private final List<String> narrowToolChecks = new ArrayList<>();
    private final List<String> scrollableFormChecks = new ArrayList<>();
    private final List<Map<String, Object>> controlBoundsFailures = new ArrayList<>();
    private boolean keyboardFocusObserved;
    private Texteditor editor;
    private Path project;
    private Path source;

    private InterfaceGalleryProbe(Path output) { this.output = output; }

    public static void main(String[] args) {
        if (args.length != 1) throw new IllegalArgumentException("Usage: shed.InterfaceGalleryProbe OUTPUT_DIRECTORY");
        Thread.setDefaultUncaughtExceptionHandler((thread, failure) -> {
            ASYNC_FAILURE.compareAndSet(null, failure);
            failure.printStackTrace(System.err);
        });
        InterfaceGalleryProbe probe = new InterfaceGalleryProbe(Path.of(args[0]).toAbsolutePath().normalize());
        Path fixture = null;
        int status = 0;
        try {
            Files.createDirectories(probe.output);
            fixture = Files.createTempDirectory("shed-interface-gallery-");
            probe.run(fixture);
            checkAsyncFailure();
        } catch (Throwable failure) {
            failure.printStackTrace(System.err);
            status = 1;
        } finally {
            try {
                if (probe.editor != null) edt(() -> {
                    for (Window owned : probe.editor.getOwnedWindows()) owned.dispose();
                    probe.editor.dispose();
                    return null;
                });
                if (fixture != null) try (var paths = Files.walk(fixture)) {
                    for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
                }
            } catch (Exception failure) {
                failure.printStackTrace(System.err);
                status = 1;
            }
        }
        // The editor has background services. This process owns every created window and service.
        System.exit(status);
    }

    private void run(Path fixture) throws Exception {
        Path config = Files.createDirectories(fixture.resolve("home/.shed"));
        System.setProperty("user.home", config.getParent().toString());
        Files.writeString(config.resolve("config.toml"), "schema_version = 1\n\"keymap.profile\" = \"plain\"\n");
        project = Files.createDirectories(fixture.resolve("project"));
        source = Files.writeString(project.resolve("Renderer.java"), """
            package demo;

            /** A small rendering example. */
            public class Renderer {
                private final String theme = "dark";

                public void render() {
                    // Keep the workbench quiet and the code readable.
                    System.out.println(theme);
                }
            }
            """);
        Path other = Files.writeString(project.resolve("Theme.java"), "package demo;\n\nclass Theme {\n    String theme = \"dark\";\n}\n");
        Files.createDirectories(project.resolve("assets"));
        Path notebook = Files.writeString(project.resolve("Notes.ipynb"), notebookFixture().serialize());
        Path notes = Files.writeString(project.resolve("Workbench.md"), """
            # Workbench tour

            Keep editing, navigation, and tools in one consistent workspace.

            ## Editor
            Open files retain their selections and scroll positions.

            ## Search
            ### Find in the current file
            ### Search the workspace

            ## Keybindings
            Choose a profile and customize individual commands.

            ## Appearance
            Switch themes or adjust interface zoom.
            """);
        long startupDeadline = System.nanoTime() + STARTUP_TIMEOUT.toNanos();
        editor = edt(() -> new Texteditor(new String[] {source.toString(), other.toString()}));
        awaitUntil("staged startup", startupDeadline, () -> !editor.stagedStartupPending && hasBuffer(source) && hasBuffer(other));
        edt(() -> { editor.setSize(1120, 820); selectSource(); return null; });
        settle();
        capture(editor, "editor", true);

        edt(() -> {
            SearchPattern pattern = new SearchPattern("theme", false, false, true);
            editor.paletteController.showWorkspaceSearch(pattern);
            editor.paletteController.workspaceSearch().search(new WorkspaceSearchQuery(pattern, "*.java", ""));
            return null;
        });
        await("workspace search", () -> !editor.paletteController.workspaceSearch().snapshot().running()
            && !editor.paletteController.workspaceSearch().snapshot().matches().isEmpty());
        assertActiveTool(ToolWindowHost.Tab.SEARCH);
        capture(editor, "tool-search", true);
        for (ToolWindowHost.Tab tab : ToolWindowHost.Tab.values()) {
            if (tab == ToolWindowHost.Tab.SEARCH) continue;
            edt(() -> { editor.showToolWindow(tab); return null; });
            settle();
            assertActiveTool(tab);
            capture(editor, "tool-" + tab.name().toLowerCase(Locale.ROOT), true);
        }

        edt(() -> { editor.hideToolWindow(); editor.treeGitController.showFileTree(project.toString()); return null; });
        await("file explorer", () -> editor.treePane != null && editor.treePane.getAlternateComponent() instanceof FileTreePanel
            && editor.treePane.getAlternateComponent().isShowing());
        settle();
        capture(editor, "explorer", true);
        edt(() -> {
            editor.treeGitController.showFileTree("");
            selectSource();
            editor.openFile(notebook.toFile());
            editor.setSize(1120, 1000);
            return null;
        });
        await("notebook surface", () -> editor.getActivePane().getAlternateComponent() instanceof NotebookPanel);
        edt(() -> {
            NotebookPanel panel = (NotebookPanel) editor.getActivePane().getAlternateComponent();
            descendants(panel, JToggleButton.class).stream().filter(button -> "Preview".equals(button.getText())).findFirst().orElseThrow().doClick();
            return null;
        });
        settle();
        capture(editor, "notebook", false);
        edt(() -> { selectSource(); editor.setSize(1120, 820); return null; });
        edt(() -> { editor.openFile(notes.toFile()); return null; });
        dialog("markdown-outline", () -> MarkdownOutlineDialog.showFor(editor, editor.getCurrentBuffer(),
            editor.getCurrentBuffer().getContent().split("\n", -1)), ignored -> { }, true);
        edt(() -> { selectSource(); return null; });

        dialog("git-graph", () -> GitGraphDialog.showFor(editor, token -> graphFixture()), ignored -> { }, true);
        dialog("command-palette", () -> editor.paletteController.showCommandPalette(), dialog -> {
            descendants(dialog, JTextField.class).getFirst().setText("theme");
        }, true);
        dialog("settings", () -> SettingsEditorDialog.showFor(editor), ignored -> { }, true);
        dialog("keymaps", () -> KeymapInspectorDialog.showFor(editor), ignored -> { }, true);
        dialog("themes", () -> ThemeGalleryDialog.showFor(editor), ignored -> { }, true);
        dialog("help", () -> ShedHelpDialog.showFor(editor, ""), ignored -> { }, true);
        dialog("about", () -> ShedAboutDialog.showFor(editor), ignored -> { }, true);
        dialog("file-chooser", () -> {
            JFileChooser chooser = new JFileChooser(project.toFile());
            editor.editorUiController.applyUiTheme(chooser);
            chooser.showOpenDialog(editor);
        }, ignored -> { }, true);
        dialog("confirmation", () -> JOptionPane.showConfirmDialog(editor,
            "Save changes to Renderer.java before closing?", "Unsaved changes", JOptionPane.YES_NO_CANCEL_OPTION), ignored -> { }, true);
        dialog("control-states", this::showControlStates, dialog -> {
            dialog.toFront();
            JTextField field = descendants(dialog, JTextField.class).getFirst();
            field.selectAll();
            field.requestFocusInWindow();
        }, true);

        edt(() -> { editor.showToolWindow(ToolWindowHost.Tab.SEARCH); return null; });
        dialog("detached-search", () -> descendants(editor.toolWindowHost, JButton.class).stream()
            .filter(button -> "Detach selected tool".equals(button.getToolTipText())).findFirst().orElseThrow().doClick(), ignored -> { }, true);
        settle();
        edt(() -> {
            editor.configManager.set("theme", "shed-light");
            editor.configManager.set("ui.zoom", "1.5");
            editor.sessionConfigController.applyRuntimeConfigFromSettings();
            editor.setSize(1120, 900);
            editor.showToolWindow(ToolWindowHost.Tab.SEARCH);
            return null;
        });
        settle();
        assertActiveTool(ToolWindowHost.Tab.SEARCH);
        capture(editor, "light-150", true);
        dialog("settings-light-150", () -> SettingsEditorDialog.showFor(editor), ignored -> { }, true);
        edt(() -> {
            editor.configManager.set("theme", "shed-dark");
            editor.configManager.set("ui.zoom", "1.0");
            editor.sessionConfigController.applyRuntimeConfigFromSettings();
            editor.setSize(760, 780);
            return null;
        });
        settle();
        for (ToolWindowHost.Tab tab : ToolWindowHost.Tab.values()) {
            edt(() -> { editor.showToolWindow(tab); return null; });
            settle();
            assertActiveTool(tab);
            String name = "narrow-tool-" + tab.name().toLowerCase(Locale.ROOT);
            int failures = edt(() -> recordControlBounds(editor.getRootPane(), name));
            if (failures > 0) {
                capture(editor, name + "-clipping", false);
            } else {
                narrowToolChecks.add(tab.name());
                System.out.println("SHED_CONTROL_BOUNDS_OK " + name);
            }
            if (tab == ToolWindowHost.Tab.DEBUG) {
                for (String section : List.of("Source", "Functions")) {
                    JTabbedPane sections = edt(() -> {
                        JTabbedPane selectedSections = descendants(editor.toolWindowHost, JTabbedPane.class).stream()
                            .filter(tabs -> tabs.indexOfTab("Source") >= 0 && tabs.indexOfTab("Functions") >= 0)
                            .findFirst().orElseThrow();
                        selectedSections.setSelectedIndex(selectedSections.indexOfTab(section));
                        return selectedSections;
                    });
                    settle();
                    assertActiveTool(ToolWindowHost.Tab.DEBUG);
                    edt(() -> {
                        if (!section.equals(sections.getTitleAt(sections.getSelectedIndex())) || !sections.getSelectedComponent().isShowing())
                            throw new IllegalStateException("Expected the Debug " + section + " section to be visible");
                        return null;
                    });
                    capture(editor, "narrow-debug-" + section.toLowerCase(Locale.ROOT), true);
                    List<JComponent> formControls = edt(() -> descendants((Container) sections.getSelectedComponent(), JComponent.class).stream()
                        .filter(control -> (control instanceof JTextField || control instanceof AbstractButton)
                            && control.isShowing() && control.isFocusable()).toList());
                    for (JComponent control : formControls) {
                        boolean checkFocus = keyboardFocusObserved && control.isEnabled();
                        edt(() -> {
                            if (checkFocus) control.requestFocusInWindow();
                            else if (control.getParent() instanceof JComponent parent) parent.scrollRectToVisible(control.getBounds());
                            return null;
                        });
                        try {
                            awaitUntil("Debug " + section + " form control", System.nanoTime() + Duration.ofSeconds(3).toNanos(), () -> {
                                if (checkFocus && !control.isFocusOwner()) control.requestFocusInWindow();
                                Rectangle visible = control.getVisibleRect();
                                return (!checkFocus || control.isFocusOwner())
                                    && visible.width >= control.getWidth() - 2 && visible.height >= control.getHeight() - 2;
                            });
                        } catch (Exception failure) {
                            capture(editor, "narrow-debug-" + section.toLowerCase(Locale.ROOT) + "-scroll-failure", false);
                            throw new IllegalStateException(edt(() -> "Debug " + section + ": unreachable " + control.getClass().getName()
                                + " " + control.getAccessibleContext().getAccessibleName() + " bounds=" + control.getBounds()
                                + " visible=" + control.getVisibleRect() + " windowFocused=" + editor.isFocused()
                                + " focusOwner=" + java.awt.KeyboardFocusManager.getCurrentKeyboardFocusManager().getFocusOwner()), failure);
                        }
                    }
                    scrollableFormChecks.add(section);
                    capture(editor, "narrow-debug-" + section.toLowerCase(Locale.ROOT) + "-scrolled", true);
                }
            }
        }
        edt(() -> { editor.showToolWindow(ToolWindowHost.Tab.SEARCH); return null; });
        settle();
        assertActiveTool(ToolWindowHost.Tab.SEARCH);
        capture(editor, "narrow", true);
        checkAsyncFailure();
        int languageServers = edt(() -> editor.lspClients.size());
        if (languageServers != 0) throw new IllegalStateException("Isolated gallery unexpectedly started a language server");
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("capture_scale", CAPTURE_SCALE);
        report.put("java", System.getProperty("java.runtime.version"));
        report.put("os", System.getProperty("os.name") + " " + System.getProperty("os.version"));
        Path jar = Path.of(Texteditor.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        Path appearanceSource = Path.of(WorkbenchStyle.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        if (!Files.isRegularFile(jar) || !jar.equals(appearanceSource))
            throw new IllegalStateException("Run the gallery against one packaged JAR, without source overlays");
        report.put("jar_sha256", java.util.HexFormat.of().formatHex(
            java.security.MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(jar))));
        report.put("language_servers", languageServers);
        report.put("narrow_tool_control_bounds", narrowToolChecks);
        report.put("scrollable_debug_forms", scrollableFormChecks);
        report.put("debug_form_native_focus_checked", keyboardFocusObserved);
        report.put("control_bounds_failures", controlBoundsFailures);
        report.put("control_bounds_passed", controlBoundsFailures.isEmpty());
        report.put("keyboard_focus_observed", keyboardFocusObserved);
        report.put("captures", captures);
        report.put("boundaries", "Fixture data only; no kernel, live debugger, task execution, remote service, or OS-native title-bar capture.");
        Files.writeString(output.resolve("gallery.json"), MiniJson.stringify(report));
        if (!controlBoundsFailures.isEmpty()) throw new IllegalStateException(controlBoundsFailures.size()
            + " clipped controls detected; all captures and failures are recorded in " + output.resolve("gallery.json"));
        System.out.println("SHED_GALLERY_OK " + captures.size() + " captures in " + output);
    }

    private void selectSource() {
        for (int index = 0; index < editor.buffers.size(); index++) {
            FileBuffer buffer = editor.buffers.get(index);
            if (buffer.hasFilePath() && Path.of(buffer.getFilePath()).equals(source)) { editor.switchToBuffer(index); return; }
        }
        throw new IllegalStateException("Source fixture buffer is missing");
    }

    private boolean hasBuffer(Path file) {
        return editor.buffers.stream().anyMatch(buffer -> buffer.hasFilePath() && Path.of(buffer.getFilePath()).equals(file));
    }

    private void assertActiveTool(ToolWindowHost.Tab tab) throws Exception {
        edt(() -> {
            JTabbedPane tabs = descendants(editor.toolWindowHost, JTabbedPane.class).getFirst();
            if (!editor.toolWindowHost.isShowing() || tabs.getSelectedIndex() < 0
                || !tabs.getTitleAt(tabs.getSelectedIndex()).equalsIgnoreCase(tab.name()) || !tabs.getSelectedComponent().isShowing()) {
                throw new IllegalStateException("Expected the " + tab + " tool to be visible");
            }
            return null;
        });
    }

    /** Modal openers run independently while the EDT's modal event loop continues processing capture requests. */
    private void dialog(String name, Runnable opener, Consumer<JDialog> configure, boolean checkControls) throws Exception {
        Set<Window> previous = edt(() -> new HashSet<>(Arrays.asList(editor.getOwnedWindows())));
        FutureTask<Void> opened = new FutureTask<>(() -> { opener.run(); return null; });
        SwingUtilities.invokeLater(opened);
        AtomicReference<JDialog> found = new AtomicReference<>();
        await(name + " dialog", () -> {
            if (opened.isDone()) opened.get();
            for (Window owned : editor.getOwnedWindows()) {
                if (!previous.contains(owned) && owned instanceof JDialog dialog && dialog.isShowing()) { found.set(dialog); return true; }
            }
            return false;
        });
        JDialog dialog = found.get();
        try {
            edt(() -> { configure.accept(dialog); return null; });
            settle();
            if (name.equals("git-graph")) await("Git fixture graph", () -> descendants(dialog, JTextArea.class).stream()
                .anyMatch(area -> area.getText().startsWith("Commit: a12bc3456789")));
            if (name.equals("command-palette")) await("command palette results", () -> descendants(dialog, JList.class).stream()
                .anyMatch(list -> list.getModel().getSize() > 0));
            if (name.equals("control-states")) keyboardFocusObserved = focusControlStateField(dialog);
            capture(dialog, name, checkControls);
        } finally {
            edt(() -> { dialog.dispose(); return null; });
            opened.get(15, TimeUnit.SECONDS);
        }
    }

    private void capture(Window window, String name, boolean checkControls) throws Exception {
        checkAsyncFailure();
        edt(() -> {
            window.validate();
            JRootPane root = ((RootPaneContainer) window).getRootPane();
            BufferedImage image = new BufferedImage(root.getWidth() * CAPTURE_SCALE, root.getHeight() * CAPTURE_SCALE, BufferedImage.TYPE_INT_RGB);
            Graphics2D graphics = image.createGraphics();
            try { graphics.scale(CAPTURE_SCALE, CAPTURE_SCALE); root.paint(graphics); }
            finally { graphics.dispose(); }
            String filename = String.format(Locale.ROOT, "%02d-%s.png", captures.size() + 1, name);
            ImageIO.write(image, "png", output.resolve(filename).toFile());
            // Collect layout failures so one run still captures every screen; run() fails after writing the report.
            int failures = checkControls ? recordControlBounds(root, name) : 0;
            Map<String, Object> details = new LinkedHashMap<>();
            details.put("file", filename);
            details.put("window", window instanceof JDialog dialog ? dialog.getTitle() : "Shed editor");
            details.put("logical_width", root.getWidth());
            details.put("logical_height", root.getHeight());
            details.put("control_bounds_checked", checkControls);
            if (checkControls) details.put("control_bounds_failures", failures);
            Component focused = window.getFocusOwner();
            if (focused != null) details.put("focused_control", focused.getAccessibleContext() == null
                ? focused.getClass().getSimpleName() : focused.getAccessibleContext().getAccessibleName());
            details.put("editor_font", fontDescription(editor.writingArea));
            descendants(root, JButton.class).stream().filter(Component::isShowing).findFirst()
                .ifPresent(button -> details.put("button_font", fontDescription(button)));
            List<JLabel> labels = descendants(root, JLabel.class).stream().filter(Component::isShowing).toList();
            labels.stream().findFirst().ifPresent(label -> details.put("body_label_font", fontDescription(label)));
            labels.stream().max(Comparator.comparingDouble(label -> label.getFont().getSize2D()))
                .ifPresent(label -> details.put("largest_label_font", fontDescription(label)));
            captures.add(details);
            System.out.println("SHED_CAPTURE " + MiniJson.stringify(details));
            return null;
        });
    }

    /** Scrollable content may intentionally extend outside its viewport; fixed controls must remain reachable. */
    private int recordControlBounds(Container root, String name) {
        int failures = 0;
        for (JComponent component : descendants(root, JComponent.class)) {
            if (!(component instanceof AbstractButton || component instanceof JTextField || component instanceof JComboBox<?>)) continue;
            if (!component.isShowing() || !component.isFocusable() || insideViewport(component)) continue;
            Rectangle visible = component.getVisibleRect();
            if (component.getWidth() < 1 || component.getHeight() < 1 || visible.x > 1 || visible.y > 1
                || visible.width < component.getWidth() - 2 || visible.height < component.getHeight() - 2) {
                Map<String, Object> failure = new LinkedHashMap<>();
                failure.put("screen", name);
                failure.put("control", component.getAccessibleContext().getAccessibleName());
                failure.put("component", component.getClass().getSimpleName());
                failure.put("bounds", component.getBounds().toString());
                failure.put("visible", visible.toString());
                controlBoundsFailures.add(failure);
                failures++;
                System.out.println("SHED_CONTROL_BOUNDS_FAIL " + MiniJson.stringify(failure));
            }
        }
        return failures;
    }

    private static boolean insideViewport(Component component) {
        for (Container parent = component.getParent(); parent != null; parent = parent.getParent()) if (parent instanceof JViewport) return true;
        return false;
    }

    private static String fontDescription(Component component) {
        Font font = component.getFont();
        return font == null ? "none" : font.getFamily() + " / " + font.getSize2D() + " / style " + font.getStyle();
    }

    private static boolean focusControlStateField(JDialog dialog) throws Exception {
        JTextField field = edt(() -> descendants(dialog, JTextField.class).getFirst());
        if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.APP_REQUEST_FOREGROUND)) {
            Desktop.getDesktop().requestForeground(true);
        }
        // Background terminal launches may show a window without activating the Java application.
        for (int attempt = 0; attempt < 6; attempt++) {
            edt(() -> { dialog.toFront(); dialog.requestFocus(); field.requestFocusInWindow(); return null; });
            Thread.sleep(200);
            if (edt(field::isFocusOwner)) {
                edt(() -> { field.selectAll(); return null; });
                System.out.println("SHED_FOCUS_STATE_OK foreground request");
                return true;
            }
        }
        // The host may deny activation to a background Java process. Keep the other
        // captures useful, and explicitly report that native focus was not observed.
        edt(() -> {
            System.out.println("SHED_FOCUS_STATE_UNAVAILABLE showing=" + dialog.isShowing()
                + " focusable=" + dialog.isFocusableWindow() + " active=" + dialog.isActive()
                + " focused=" + dialog.isFocused());
            return null;
        });
        return false;
    }

    /** Exercises shared delegates without invoking editor actions or contacting an external service. */
    private void showControlStates() {
        JDialog dialog = new JDialog(editor, "Control states", false);
        dialog.setDefaultCloseOperation(JDialog.DISPOSE_ON_CLOSE);
        KeyboardFocusSupport.installEscape(dialog.getRootPane(), dialog::dispose);
        JPanel content = new JPanel(new BorderLayout(12, 12));
        content.setBorder(BorderFactory.createEmptyBorder(16, 16, 16, 16));
        JPanel controls = new JPanel(new GridLayout(0, 2, 12, 10));
        JButton enabled = new JButton("Enabled action");
        JButton disabled = new JButton("Disabled action");
        disabled.setEnabled(false);
        JButton rollover = new JButton("Hovered action");
        JButton pressed = new JButton("Pressed action");
        controls.add(new JLabel("Enabled")); controls.add(enabled);
        controls.add(new JLabel("Disabled")); controls.add(disabled);
        controls.add(new JLabel("Pointer hover")); controls.add(rollover);
        controls.add(new JLabel("Pointer press")); controls.add(pressed);
        JTextField focused = new JTextField("Keyboard focus and selection");
        focused.getAccessibleContext().setAccessibleName("Focused field");
        JLabel focusLabel = new JLabel("Keyboard focus");
        focusLabel.setLabelFor(focused);
        controls.add(focusLabel); controls.add(focused);
        JCheckBox check = new JCheckBox("Show line numbers", true);
        JRadioButton radio = new JRadioButton("Plain keymap", true);
        controls.add(check); controls.add(radio);
        JToggleButton selected = new JToggleButton("Selected toggle", true);
        controls.add(new JLabel("Selected action")); controls.add(selected);
        JProgressBar loading = new JProgressBar();
        loading.setIndeterminate(true);
        loading.getAccessibleContext().setAccessibleName("Loading workspace");
        controls.add(new JLabel("Loading workspace…")); controls.add(loading);
        content.add(controls, BorderLayout.NORTH);
        JList<String> empty = new JList<>(new DefaultListModel<>());
        WorkbenchStyle.emptyText(empty, "No items match this filter");
        JScrollPane emptyScroll = new JScrollPane(empty);
        emptyScroll.setBorder(BorderFactory.createTitledBorder("Empty state"));
        content.add(emptyScroll, BorderLayout.CENTER);
        JPanel footer = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        JLabel note = new JLabel("Fixture controls · no actions execute");
        note.putClientProperty("shed.muted", true);
        footer.add(note);
        JButton close = new JButton("Close");
        close.addActionListener(event -> dialog.dispose());
        footer.add(close);
        content.add(footer, BorderLayout.SOUTH);
        dialog.setContentPane(content);
        editor.editorUiController.prepareDialog(dialog, 800, 650);
        dialog.pack();
        dialog.setLocationRelativeTo(editor);
        // Setting button models enters these states without mouse events or action dispatch.
        rollover.setRolloverEnabled(true);
        rollover.getModel().setRollover(true);
        pressed.getModel().setArmed(true);
        pressed.getModel().setPressed(true);
        dialog.setVisible(true);
    }

    private static NotebookDocument notebookFixture() {
        return NotebookDocument.empty().withCells(List.of(
            new NotebookDocument.Cell("markdown", "# Rendering notes\n\nThe same theme follows **code, controls, and previews**.", Map.of()),
            new NotebookDocument.Cell("code", "palette = {\"surface\": \"#20242b\", \"accent\": \"#82aaff\"}\nprint(palette)", Map.of("execution_count", 1, "outputs", List.of(
                Map.of("output_type", "stream", "name", "stdout", "text", "{'surface': '#20242b', 'accent': '#82aaff'}\n"),
                Map.of("output_type", "display_data", "metadata", Map.of(), "data", Map.of("text/markdown", "### Preview result\n\nReadable output in the active theme.")))))));
    }

    private GitGraphModel.Snapshot graphFixture() {
        List<GitGraphModel.Commit> commits = List.of(
            new GitGraphModel.Commit("a12bc3456789", List.of("b23cd4567890", "c34de5678901"), "HEAD → main", "Unify workbench surfaces", "A. Example", "2026-10-23"),
            new GitGraphModel.Commit("c34de5678901", List.of("d45ef6789012"), "feature/search", "Keep search controls consistent", "B. Example", "2026-10-22"),
            new GitGraphModel.Commit("b23cd4567890", List.of("d45ef6789012"), "", "Preserve editor state across views", "A. Example", "2026-10-22"),
            new GitGraphModel.Commit("d45ef6789012", List.of("e56fa7890123"), "v2.0", "Add keyboard customization", "B. Example", "2026-10-21"),
            new GitGraphModel.Commit("e56fa7890123", List.of(), "", "Create the editor", "A. Example", "2026-10-20"));
        List<GitGraphModel.Row> rows = GitGraphModel.graphRows(commits);
        return new GitGraphModel.Snapshot(GitGraphModel.State.READY, project.toString(), rows, 2, "5 fixture commits · no repository commands executed");
    }

    private static <T> List<T> descendants(Container parent, Class<T> type) {
        List<T> result = new ArrayList<>();
        for (Component child : parent.getComponents()) {
            if (type.isInstance(child)) result.add(type.cast(child));
            if (child instanceof Container container) result.addAll(descendants(container, type));
        }
        return result;
    }

    private static <T> T edt(Callable<T> work) throws Exception {
        return edt(work, STARTUP_TIMEOUT.toNanos());
    }

    private static <T> T edt(Callable<T> work, long timeoutNanos) throws Exception {
        FutureTask<T> result = new FutureTask<>(work);
        SwingUtilities.invokeLater(result);
        return result.get(timeoutNanos, TimeUnit.NANOSECONDS);
    }

    private static void await(String description, Callable<Boolean> condition) throws Exception {
        awaitUntil(description, System.nanoTime() + Duration.ofSeconds(30).toNanos(), condition);
    }

    private static void awaitUntil(String description, long deadline, Callable<Boolean> condition) throws Exception {
        while (true) {
            checkAsyncFailure();
            long remaining = deadline - System.nanoTime();
            if (remaining <= 0) throw new IllegalStateException("Timed out waiting for " + description);
            if (edt(condition, remaining)) return;
            Thread.sleep(75);
        }
    }

    private static void settle() throws Exception {
        Thread.sleep(650);
        edt(() -> null);
        checkAsyncFailure();
    }

    private static void checkAsyncFailure() {
        if (ASYNC_FAILURE.get() != null) throw new IllegalStateException("Background or EDT failure", ASYNC_FAILURE.get());
    }
}
