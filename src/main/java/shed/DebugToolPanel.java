package shed;

import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.awt.GridLayout;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
import javax.swing.JTree;
import javax.swing.DefaultListModel;
import javax.swing.event.TreeExpansionEvent;
import javax.swing.event.TreeWillExpandListener;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.DefaultTreeModel;
import javax.swing.tree.TreePath;

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
    private final DefaultListModel<FunctionBreakpointStore.Breakpoint> functionBreakpoints = new DefaultListModel<>();
    private final JList<FunctionBreakpointStore.Breakpoint> functionBreakpointList = new JList<>(functionBreakpoints);
    private final DefaultListModel<DebugSessionController.ExceptionBreakpointView> exceptionBreakpoints = new DefaultListModel<>();
    private final JList<DebugSessionController.ExceptionBreakpointView> exceptionBreakpointList = new JList<>(exceptionBreakpoints);
    private final JCheckBox breakpointEnabled = new JCheckBox("Enabled", true);
    private final JTextField breakpointCondition = new JTextField();
    private final JTextField breakpointHitCondition = new JTextField();
    private final JTextField breakpointLogMessage = new JTextField();
    private final JTextField functionBreakpointName = new JTextField();
    private final JCheckBox functionBreakpointEnabled = new JCheckBox("Enabled", true);
    private final JTextField functionBreakpointCondition = new JTextField();
    private final JTextField functionBreakpointHitCondition = new JTextField();
    private final JCheckBox exceptionBreakpointEnabled = new JCheckBox("Enabled", true);
    private final DefaultMutableTreeNode variableRoot = new DefaultMutableTreeNode("Variables");
    private final DefaultTreeModel variableModel = new DefaultTreeModel(variableRoot);
    private final JTree variableTree = new JTree(variableModel);
    private final JTextArea console = textArea();
    private final JTextField watchInput = new JTextField();
    private final JTextField evaluationInput = new JTextField();
    private final JTextField variableValue = new JTextField();
    private final Set<Integer> loadingVariableReferences = new HashSet<>();
    private boolean refreshing;

    private record VariableNode(int variablesReference, DebugInspection.Variable variable) {
        @Override public String toString() {
            if (variable == null) return "";
            StringBuilder text = new StringBuilder(variable.name()).append(" = ").append(variable.value());
            if (!variable.type().isBlank()) text.append(" : ").append(variable.type());
            return text.toString();
        }
    }

    DebugToolPanel(Texteditor editor, ToolWindowHost host) {
        this.editor = editor;
        panel.setBorder(BorderFactory.createEmptyBorder(5, 7, 7, 7));
        panel.add(toolbar(), BorderLayout.NORTH);
        panel.add(content(), BorderLayout.CENTER);
        variableTree.setRootVisible(false);
        AccessibilitySupport.describe(frameList, "Debug call stack", "Select a paused stack frame to inspect variables.");
        AccessibilitySupport.describe(variableTree, "Debug variables", "Expand a structured variable to inspect its nested values.");
        AccessibilitySupport.describe(watchList, "Debug watches", "Session-local watch expressions.");
        AccessibilitySupport.describe(breakpointList, "Source breakpoints", "Configure enabled, condition, hit-count, or log-message settings for a source breakpoint.");
        AccessibilitySupport.describe(functionBreakpointList, "Function breakpoints", "Configure enabled, condition, or hit-count settings for a DAP function breakpoint.");
        AccessibilitySupport.describe(exceptionBreakpointList, "Exception breakpoints", "Enable or disable exception breakpoint filters advertised by the active debug adapter.");
        breakpointList.addListSelectionListener(event -> {
            if (!event.getValueIsAdjusting()) loadBreakpoint(breakpointList.getSelectedValue());
        });
        functionBreakpointList.addListSelectionListener(event -> {
            if (!event.getValueIsAdjusting()) loadFunctionBreakpoint(functionBreakpointList.getSelectedValue());
        });
        exceptionBreakpointList.addListSelectionListener(event -> {
            if (!event.getValueIsAdjusting()) loadExceptionBreakpoint(exceptionBreakpointList.getSelectedValue());
        });
        variableTree.addTreeWillExpandListener(new TreeWillExpandListener() {
            @Override public void treeWillExpand(TreeExpansionEvent event) { requestVariableExpansion(event); }
            @Override public void treeWillCollapse(TreeExpansionEvent event) { }
        });
        variableTree.addTreeSelectionListener(event -> loadVariableValue());
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

        JPanel functionBreakpointPanel = new JPanel(new BorderLayout(3, 3));
        functionBreakpointPanel.setBorder(BorderFactory.createTitledBorder("Function Breakpoints"));
        functionBreakpointPanel.add(new JScrollPane(functionBreakpointList), BorderLayout.CENTER);
        JPanel functionBreakpointForm = new JPanel(new GridLayout(4, 2, 3, 3));
        functionBreakpointForm.add(new JLabel("Name")); functionBreakpointForm.add(functionBreakpointName);
        functionBreakpointForm.add(functionBreakpointEnabled); functionBreakpointForm.add(new JLabel(""));
        functionBreakpointForm.add(new JLabel("Condition")); functionBreakpointForm.add(functionBreakpointCondition);
        functionBreakpointForm.add(new JLabel("Hit count")); functionBreakpointForm.add(functionBreakpointHitCondition);
        JPanel functionBreakpointActions = new JPanel(new FlowLayout(FlowLayout.RIGHT, 3, 0));
        functionBreakpointActions.add(button("Add", this::addFunctionBreakpoint));
        functionBreakpointActions.add(button("Apply", this::applyFunctionBreakpoint));
        functionBreakpointActions.add(button("Remove", this::removeFunctionBreakpoint));
        JPanel functionBreakpointBottom = new JPanel(new BorderLayout(3, 3));
        functionBreakpointBottom.add(functionBreakpointForm, BorderLayout.CENTER);
        functionBreakpointBottom.add(functionBreakpointActions, BorderLayout.SOUTH);
        functionBreakpointPanel.add(functionBreakpointBottom, BorderLayout.SOUTH);

        JPanel exceptionBreakpointPanel = new JPanel(new BorderLayout(3, 3));
        exceptionBreakpointPanel.setBorder(BorderFactory.createTitledBorder("Exception Breakpoints"));
        exceptionBreakpointPanel.add(new JScrollPane(exceptionBreakpointList), BorderLayout.CENTER);
        JPanel exceptionBreakpointActions = new JPanel(new FlowLayout(FlowLayout.RIGHT, 3, 0));
        exceptionBreakpointActions.add(exceptionBreakpointEnabled);
        exceptionBreakpointActions.add(button("Apply", this::applyExceptionBreakpoint));
        exceptionBreakpointPanel.add(exceptionBreakpointActions, BorderLayout.SOUTH);

        JPanel left = new JPanel(new GridLayout(5, 1, 4, 4));
        left.add(framesPanel); left.add(watchPanel); left.add(breakpointPanel); left.add(functionBreakpointPanel); left.add(exceptionBreakpointPanel);
        JPanel variablesPanel = new JPanel(new BorderLayout());
        variablesPanel.setBorder(BorderFactory.createTitledBorder("Variables and Scopes"));
        variablesPanel.add(new JScrollPane(variableTree), BorderLayout.CENTER);
        JPanel variableActions = new JPanel(new BorderLayout(3, 0));
        variableActions.add(variableValue, BorderLayout.CENTER);
        variableActions.add(button("Set", this::setVariable), BorderLayout.EAST);
        variablesPanel.add(variableActions, BorderLayout.SOUTH);
        JPanel consolePanel = new JPanel(new BorderLayout(3, 3));
        consolePanel.setBorder(BorderFactory.createTitledBorder("Debug Console"));
        consolePanel.add(new JScrollPane(console), BorderLayout.CENTER);
        JPanel evaluate = new JPanel(new BorderLayout(3, 0));
        evaluate.add(evaluationInput, BorderLayout.CENTER);
        evaluate.add(button("Evaluate", this::evaluate), BorderLayout.EAST);
        consolePanel.add(evaluate, BorderLayout.SOUTH);
        JSplitPane right = new JSplitPane(JSplitPane.VERTICAL_SPLIT, variablesPanel, consolePanel);
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
            FunctionBreakpointStore.Breakpoint selectedFunctionBreakpoint = functionBreakpointList.getSelectedValue();
            functionBreakpoints.clear();
            for (FunctionBreakpointStore.Breakpoint breakpoint : editor.debugSessionController.functionBreakpointsForPanel()) {
                functionBreakpoints.addElement(breakpoint);
            }
            if (selectedFunctionBreakpoint != null) {
                for (int index = 0; index < functionBreakpoints.size(); index++) {
                    if (functionBreakpoints.get(index).name().equals(selectedFunctionBreakpoint.name())) {
                        functionBreakpointList.setSelectedIndex(index);
                        break;
                    }
                }
            }
            if (functionBreakpointList.getSelectedIndex() < 0) loadFunctionBreakpoint(null);
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
            refreshVariableTree(snapshot);
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
    private void evaluate() { message(editor.debugSessionController.evaluateForPanel(evaluationInput.getText())); evaluationInput.setText(""); }
    private void setVariable() {
        VariableNode variable = selectedVariable();
        message(variable == null ? "Select a displayed variable." : editor.debugSessionController.setVariableForPanel(variable.variablesReference(),
            variable.variable().name(), variableValue.getText()));
    }
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
    private void addFunctionBreakpoint() { message(editor.debugSessionController.addFunctionBreakpointForPanel(functionBreakpointName.getText())); }
    private void applyFunctionBreakpoint() {
        FunctionBreakpointStore.Breakpoint breakpoint = functionBreakpointList.getSelectedValue();
        message(editor.debugSessionController.configureFunctionBreakpointForPanel(breakpoint, functionBreakpointEnabled.isSelected(),
            functionBreakpointCondition.getText(), functionBreakpointHitCondition.getText()));
    }
    private void removeFunctionBreakpoint() { message(editor.debugSessionController.removeFunctionBreakpointForPanel(functionBreakpointList.getSelectedValue())); }
    private void applyExceptionBreakpoint() {
        message(editor.debugSessionController.configureExceptionBreakpointForPanel(exceptionBreakpointList.getSelectedValue(), exceptionBreakpointEnabled.isSelected()));
    }
    private void loadBreakpoint(BreakpointStore.Breakpoint breakpoint) {
        breakpointEnabled.setSelected(breakpoint == null || breakpoint.enabled());
        breakpointCondition.setText(breakpoint == null ? "" : breakpoint.condition());
        breakpointHitCondition.setText(breakpoint == null ? "" : breakpoint.hitCondition());
        breakpointLogMessage.setText(breakpoint == null ? "" : breakpoint.logMessage());
    }
    private void loadFunctionBreakpoint(FunctionBreakpointStore.Breakpoint breakpoint) {
        functionBreakpointName.setText(breakpoint == null ? "" : breakpoint.name());
        functionBreakpointEnabled.setSelected(breakpoint == null || breakpoint.enabled());
        functionBreakpointCondition.setText(breakpoint == null ? "" : breakpoint.condition());
        functionBreakpointHitCondition.setText(breakpoint == null ? "" : breakpoint.hitCondition());
    }
    private void loadExceptionBreakpoint(DebugSessionController.ExceptionBreakpointView breakpoint) {
        exceptionBreakpointEnabled.setSelected(breakpoint == null || breakpoint.enabled());
    }

    private void loadVariableValue() {
        VariableNode variable = selectedVariable();
        variableValue.setText(variable == null ? "" : variable.variable().value());
    }

    private VariableNode selectedVariable() {
        Object selected = variableTree.getLastSelectedPathComponent();
        if (!(selected instanceof DefaultMutableTreeNode node) || !(node.getUserObject() instanceof VariableNode variable)) return null;
        return variable.variablesReference() < 1 ? null : variable;
    }

    private void requestVariableExpansion(TreeExpansionEvent event) {
        Object candidate = event == null || event.getPath() == null ? null : event.getPath().getLastPathComponent();
        if (!(candidate instanceof DefaultMutableTreeNode node) || !(node.getUserObject() instanceof VariableNode variable)) return;
        if (node.getChildCount() != 1 || !(node.getFirstChild() instanceof DefaultMutableTreeNode child)
            || !"Expand to inspect".equals(String.valueOf(child.getUserObject()))) return;
        int reference = variable.variable().variablesReference();
        if (reference < 1 || !loadingVariableReferences.add(reference)) return;
        editor.showMessage(editor.debugSessionController.expandVariablesForPanel(reference));
    }

    private void refreshVariableTree(DebugInspection.Snapshot snapshot) {
        variableRoot.removeAllChildren();
        loadingVariableReferences.clear();
        int scopeCount = snapshot == null ? 0 : snapshot.scopes().size();
        if (snapshot == null || snapshot.scopes().isEmpty()) {
            variableRoot.add(new DefaultMutableTreeNode("No paused variables."));
        } else {
            for (DebugInspection.Scope scope : snapshot.scopes()) {
                DefaultMutableTreeNode scopeNode = new DefaultMutableTreeNode(scope.name() + (scope.expensive() ? " (expensive)" : ""));
                variableRoot.add(scopeNode);
                for (DebugInspection.Variable variable : scope.variables()) {
                    appendVariable(scopeNode, scope.variablesReference(), variable, snapshot.expandedVariables(), Set.of(), 0);
                }
            }
        }
        variableModel.reload();
        for (int row = 0; row < scopeCount; row++) variableTree.expandRow(row);
        expandLoadedVariables(variableRoot, snapshot == null ? Map.of() : snapshot.expandedVariables());
    }

    private void expandLoadedVariables(DefaultMutableTreeNode node, Map<Integer, List<DebugInspection.Variable>> expanded) {
        if (node == null) return;
        Object value = node.getUserObject();
        if (value instanceof VariableNode variable && expanded.containsKey(variable.variable().variablesReference())) {
            variableTree.expandPath(new TreePath(node.getPath()));
        }
        for (int index = 0; index < node.getChildCount(); index++) {
            Object child = node.getChildAt(index);
            if (child instanceof DefaultMutableTreeNode childNode) expandLoadedVariables(childNode, expanded);
        }
    }

    private static void appendVariable(DefaultMutableTreeNode parent, int variablesReference, DebugInspection.Variable variable,
                                       Map<Integer, List<DebugInspection.Variable>> expanded,
                                       Set<Integer> ancestors, int depth) {
        DefaultMutableTreeNode node = new DefaultMutableTreeNode(new VariableNode(variablesReference, variable));
        parent.add(node);
        int reference = variable == null ? 0 : variable.variablesReference();
        if (reference < 1 || depth >= 12) return;
        if (ancestors.contains(reference)) {
            node.add(new DefaultMutableTreeNode("Cyclic variable reference"));
            return;
        }
        List<DebugInspection.Variable> children = expanded.get(reference);
        if (children == null) {
            node.add(new DefaultMutableTreeNode("Expand to inspect"));
            return;
        }
        Set<Integer> childAncestors = new HashSet<>(ancestors);
        childAncestors.add(reference);
        for (DebugInspection.Variable child : children) appendVariable(node, reference, child, expanded, Set.copyOf(childAncestors), depth + 1);
    }

    private void message(String text) { editor.showMessage(text); refresh(); }
    private static JButton button(String text, Runnable action) { JButton button = new JButton(text); button.addActionListener(event -> action.run()); return button; }
    private static JTextArea textArea() { JTextArea area = new JTextArea(); area.setEditable(false); area.setLineWrap(false); return area; }
}
