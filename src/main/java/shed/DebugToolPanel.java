package shed;

import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.awt.GridLayout;
import java.util.List;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.DefaultListModel;

final class DebugToolPanel implements ToolWindowHost.ToolSurface {
    private final Texteditor editor;
    private final JPanel panel = new JPanel(new BorderLayout(6, 6));
    private final JComboBox<String> configurations = new JComboBox<>();
    private final JLabel state = new JLabel("No debug session selected.");
    private final DefaultListModel<DebugInspection.Frame> frames = new DefaultListModel<>();
    private final JList<DebugInspection.Frame> frameList = new JList<>(frames);
    private final DefaultListModel<DebugInspection.Watch> watches = new DefaultListModel<>();
    private final JList<DebugInspection.Watch> watchList = new JList<>(watches);
    private final DefaultListModel<BreakpointStore.Breakpoint> breakpoints = new DefaultListModel<>();
    private final JList<BreakpointStore.Breakpoint> breakpointList = new JList<>(breakpoints);
    private final DefaultListModel<DebugSessionController.ExceptionBreakpointView> exceptionBreakpoints = new DefaultListModel<>();
    private final JList<DebugSessionController.ExceptionBreakpointView> exceptionBreakpointList = new JList<>(exceptionBreakpoints);
    private final JCheckBox breakpointEnabled = new JCheckBox("Enabled", true);
    private final JTextField breakpointCondition = new JTextField();
    private final JTextField breakpointHitCondition = new JTextField();
    private final JTextField breakpointLogMessage = new JTextField();
    private final JCheckBox exceptionBreakpointEnabled = new JCheckBox("Enabled", true);
    private final JTextArea inspector = textArea();
    private final JTextArea console = textArea();
    private final JTextField watchInput = new JTextField();
    private boolean refreshing;

    DebugToolPanel(Texteditor editor, ToolWindowHost host) {
        this.editor = editor;
        panel.setBorder(BorderFactory.createEmptyBorder(5, 7, 7, 7));
        panel.add(toolbar(), BorderLayout.NORTH);
        panel.add(content(), BorderLayout.CENTER);
        AccessibilitySupport.describe(frameList, "Debug call stack", "Select a paused stack frame to inspect variables.");
        AccessibilitySupport.describe(watchList, "Debug watches", "Session-local watch expressions.");
        AccessibilitySupport.describe(breakpointList, "Source breakpoints", "Configure enabled, condition, hit-count, or log-message settings for a source breakpoint.");
        AccessibilitySupport.describe(exceptionBreakpointList, "Exception breakpoints", "Enable or disable exception breakpoint filters advertised by the active debug adapter.");
        breakpointList.addListSelectionListener(event -> {
            if (!event.getValueIsAdjusting()) loadBreakpoint(breakpointList.getSelectedValue());
        });
        exceptionBreakpointList.addListSelectionListener(event -> {
            if (!event.getValueIsAdjusting()) loadExceptionBreakpoint(exceptionBreakpointList.getSelectedValue());
        });
    }

    @Override public JPanel component() { return panel; }

    private JPanel toolbar() {
        JPanel panel = new JPanel(new BorderLayout(6, 0));
        JPanel controls = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        controls.add(new JLabel("Configuration"));
        configurations.addActionListener(event -> {
            if (!refreshing && configurations.getSelectedItem() != null) message(editor.debugSessionController.selectForPanel(String.valueOf(configurations.getSelectedItem())));
        });
        controls.add(configurations);
        JButton start = button("Start", () -> message(editor.debugSessionController.startForPanel()));
        JButton stop = button("Stop", () -> message(editor.debugSessionController.stopForPanel()));
        JButton restart = button("Restart", () -> message(editor.debugSessionController.restartForPanel()));
        JButton resume = button("Continue", () -> message(editor.debugSessionController.continueForPanel()));
        JButton next = button("Next", () -> message(editor.debugSessionController.nextForPanel()));
        JButton stepIn = button("Step In", () -> message(editor.debugSessionController.stepInForPanel()));
        JButton stepOut = button("Step Out", () -> message(editor.debugSessionController.stepOutForPanel()));
        JButton pause = button("Pause", () -> message(editor.debugSessionController.pauseForPanel()));
        JButton runToCursor = button("Run to Cursor", () -> message(editor.debugSessionController.runToCursorForPanel()));
        JButton inspect = button("Refresh", () -> message(editor.debugSessionController.refreshInspectionForPanel()));
        controls.add(start); controls.add(stop); controls.add(restart); controls.add(resume); controls.add(next); controls.add(stepIn); controls.add(stepOut); controls.add(pause); controls.add(runToCursor); controls.add(inspect);
        panel.add(controls, BorderLayout.WEST);
        panel.add(state, BorderLayout.CENTER);
        return panel;
    }

    private java.awt.Component content() {
        JPanel framesPanel = new JPanel(new BorderLayout(3, 3));
        framesPanel.setBorder(BorderFactory.createTitledBorder("Call Stack"));
        framesPanel.add(new JScrollPane(frameList), BorderLayout.CENTER);
        JButton selectFrame = button("Inspect Frame", this::selectFrame);
        JButton openFrameSource = button("Open Source", this::openFrameSource);
        JPanel frameActions = new JPanel(new GridLayout(1, 2, 3, 0));
        frameActions.add(selectFrame);
        frameActions.add(openFrameSource);
        framesPanel.add(frameActions, BorderLayout.SOUTH);

        JPanel watchPanel = new JPanel(new BorderLayout(3, 3));
        watchPanel.setBorder(BorderFactory.createTitledBorder("Watches"));
        watchPanel.add(new JScrollPane(watchList), BorderLayout.CENTER);
        JPanel watchActions = new JPanel(new BorderLayout(3, 0));
        watchActions.add(watchInput, BorderLayout.CENTER);
        JPanel buttons = new JPanel(new GridLayout(1, 2, 3, 0));
        buttons.add(button("Add", this::addWatch));
        buttons.add(button("Remove", this::removeWatch));
        watchActions.add(buttons, BorderLayout.EAST);
        watchPanel.add(watchActions, BorderLayout.SOUTH);

        JPanel breakpointPanel = new JPanel(new BorderLayout(3, 3));
        breakpointPanel.setBorder(BorderFactory.createTitledBorder("Source Breakpoints"));
        breakpointPanel.add(new JScrollPane(breakpointList), BorderLayout.CENTER);
        JPanel breakpointForm = new JPanel(new GridLayout(4, 2, 3, 3));
        breakpointForm.add(breakpointEnabled); breakpointForm.add(new JLabel(""));
        breakpointForm.add(new JLabel("Condition")); breakpointForm.add(breakpointCondition);
        breakpointForm.add(new JLabel("Hit count")); breakpointForm.add(breakpointHitCondition);
        breakpointForm.add(new JLabel("Log message")); breakpointForm.add(breakpointLogMessage);
        JPanel breakpointActions = new JPanel(new FlowLayout(FlowLayout.RIGHT, 3, 0));
        breakpointActions.add(button("Apply", this::applyBreakpoint));
        breakpointActions.add(button("Remove", this::removeBreakpoint));
        JPanel breakpointBottom = new JPanel(new BorderLayout(3, 3));
        breakpointBottom.add(breakpointForm, BorderLayout.CENTER);
        breakpointBottom.add(breakpointActions, BorderLayout.SOUTH);
        breakpointPanel.add(breakpointBottom, BorderLayout.SOUTH);

        JPanel exceptionBreakpointPanel = new JPanel(new BorderLayout(3, 3));
        exceptionBreakpointPanel.setBorder(BorderFactory.createTitledBorder("Exception Breakpoints"));
        exceptionBreakpointPanel.add(new JScrollPane(exceptionBreakpointList), BorderLayout.CENTER);
        JPanel exceptionBreakpointActions = new JPanel(new FlowLayout(FlowLayout.RIGHT, 3, 0));
        exceptionBreakpointActions.add(exceptionBreakpointEnabled);
        exceptionBreakpointActions.add(button("Apply", this::applyExceptionBreakpoint));
        exceptionBreakpointPanel.add(exceptionBreakpointActions, BorderLayout.SOUTH);

        JPanel left = new JPanel(new GridLayout(4, 1, 4, 4));
        left.add(framesPanel); left.add(watchPanel); left.add(breakpointPanel); left.add(exceptionBreakpointPanel);
        inspector.setBorder(BorderFactory.createTitledBorder("Variables and Scopes"));
        console.setBorder(BorderFactory.createTitledBorder("Debug Console"));
        JSplitPane right = new JSplitPane(JSplitPane.VERTICAL_SPLIT, new JScrollPane(inspector), new JScrollPane(console));
        right.setResizeWeight(0.56);
        JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, left, right);
        split.setResizeWeight(0.28);
        return split;
    }

    @Override public void refresh() {
        if (refreshing) return;
        refreshing = true;
        try {
            List<String> available = editor.debugSessionController.configurationNamesForPanel();
            String selected = configurations.getSelectedItem() == null ? "" : String.valueOf(configurations.getSelectedItem());
            configurations.removeAllItems();
            for (String value : available) configurations.addItem(value);
            DebugSessionService.Snapshot session = editor.debugSessionController.snapshotForPanel();
            if (!session.configuration().isBlank()) configurations.setSelectedItem(session.configuration());
            else configurations.setSelectedItem(selected);
            state.setText(session.lifecycle().name().toLowerCase() + " — " + session.detail());
            DebugInspection.Snapshot snapshot = editor.debugSessionController.inspectionForPanel();
            frames.clear();
            for (DebugInspection.Frame frame : snapshot.frames()) frames.addElement(frame);
            watches.clear();
            for (DebugInspection.Watch watch : snapshot.watches()) watches.addElement(watch);
            BreakpointStore.Breakpoint selectedBreakpoint = breakpointList.getSelectedValue();
            breakpoints.clear();
            for (BreakpointStore.Breakpoint breakpoint : editor.debugSessionController.breakpointsForPanel()) breakpoints.addElement(breakpoint);
            if (selectedBreakpoint != null) {
                for (int index = 0; index < breakpoints.size(); index++) {
                    BreakpointStore.Breakpoint breakpoint = breakpoints.get(index);
                    if (breakpoint.source().equals(selectedBreakpoint.source()) && breakpoint.line() == selectedBreakpoint.line()) {
                        breakpointList.setSelectedIndex(index);
                        break;
                    }
                }
            }
            if (breakpointList.getSelectedIndex() < 0) loadBreakpoint(null);
            DebugSessionController.ExceptionBreakpointView selectedExceptionBreakpoint = exceptionBreakpointList.getSelectedValue();
            exceptionBreakpoints.clear();
            for (DebugSessionController.ExceptionBreakpointView breakpoint : editor.debugSessionController.exceptionBreakpointsForPanel()) {
                exceptionBreakpoints.addElement(breakpoint);
            }
            if (selectedExceptionBreakpoint != null) {
                for (int index = 0; index < exceptionBreakpoints.size(); index++) {
                    DebugSessionController.ExceptionBreakpointView breakpoint = exceptionBreakpoints.get(index);
                    if (breakpoint.filter().id().equals(selectedExceptionBreakpoint.filter().id())) {
                        exceptionBreakpointList.setSelectedIndex(index);
                        break;
                    }
                }
            }
            if (exceptionBreakpointList.getSelectedIndex() < 0) loadExceptionBreakpoint(null);
            inspector.setText(renderInspection(snapshot));
            DebugConsole.Snapshot output = editor.debugSessionController.consoleForPanel();
            console.setText(output.output());
            console.setCaretPosition(0);
        } finally { refreshing = false; }
    }

    private void selectFrame() {
        DebugInspection.Frame frame = frameList.getSelectedValue();
        if (frame == null) { message("Select a stack frame."); return; }
        message(editor.debugSessionController.selectFrameForPanel(frame.id()));
    }

    private void openFrameSource() {
        DebugInspection.Frame frame = frameList.getSelectedValue();
        message(editor.debugSessionController.openFrameSourceForPanel(frame));
    }

    private void addWatch() { message(editor.debugSessionController.addWatchForPanel(watchInput.getText())); watchInput.setText(""); }
    private void removeWatch() {
        DebugInspection.Watch watch = watchList.getSelectedValue();
        message(watch == null ? "Select a watch." : editor.debugSessionController.removeWatchForPanel(watch.expression()));
    }
    private void applyBreakpoint() {
        BreakpointStore.Breakpoint breakpoint = breakpointList.getSelectedValue();
        message(editor.debugSessionController.configureBreakpointForPanel(breakpoint, breakpointEnabled.isSelected(), breakpointCondition.getText(),
            breakpointHitCondition.getText(), breakpointLogMessage.getText()));
    }
    private void removeBreakpoint() { message(editor.debugSessionController.removeBreakpointForPanel(breakpointList.getSelectedValue())); }
    private void applyExceptionBreakpoint() {
        message(editor.debugSessionController.configureExceptionBreakpointForPanel(exceptionBreakpointList.getSelectedValue(), exceptionBreakpointEnabled.isSelected()));
    }
    private void loadBreakpoint(BreakpointStore.Breakpoint breakpoint) {
        breakpointEnabled.setSelected(breakpoint == null || breakpoint.enabled());
        breakpointCondition.setText(breakpoint == null ? "" : breakpoint.condition());
        breakpointHitCondition.setText(breakpoint == null ? "" : breakpoint.hitCondition());
        breakpointLogMessage.setText(breakpoint == null ? "" : breakpoint.logMessage());
    }
    private void loadExceptionBreakpoint(DebugSessionController.ExceptionBreakpointView breakpoint) {
        exceptionBreakpointEnabled.setSelected(breakpoint == null || breakpoint.enabled());
    }

    private void message(String text) { editor.showMessage(text); refresh(); }
    private static JButton button(String text, Runnable action) { JButton button = new JButton(text); button.addActionListener(event -> action.run()); return button; }
    private static JTextArea textArea() { JTextArea area = new JTextArea(); area.setEditable(false); area.setLineWrap(false); return area; }
    private static String renderInspection(DebugInspection.Snapshot snapshot) {
        StringBuilder text = new StringBuilder(snapshot.state().name()).append(" — ").append(snapshot.detail()).append('\n');
        for (DebugInspection.Scope scope : snapshot.scopes()) {
            text.append('\n').append(scope.name()).append('\n');
            for (DebugInspection.Variable variable : scope.variables()) text.append("  ").append(variable.name()).append(" = ").append(variable.value())
                .append(variable.type().isBlank() ? "" : " : " + variable.type()).append('\n');
        }
        return text.toString();
    }
}
