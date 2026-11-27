package shed;

import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.Window;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTabbedPane;

/** Shared docked tool area; controllers remain the source of truth. */
final class ToolWindowHost extends JPanel implements AutoCloseable {
    enum Tab { GIT, DEBUG, TASKS, TESTS, PROBLEMS, REPLACE, SEARCH, LANGUAGE, QUICKFIX, AGENT }

    private final Texteditor editor;
    private final JTabbedPane tabs = new JTabbedPane();
    private JPanel headerPanel;
    private final JPanel chatDock=new JPanel(new BorderLayout());
    private final JTabbedPane rightTabs=new JTabbedPane();
    private final Map<Tab,ToolReportPanel> reports=new EnumMap<>(Tab.class);
    private final Map<Tab, ToolSurface> surfaces = new EnumMap<>(Tab.class);
    private final Map<Tab, ToolViewport> presentations = new EnumMap<>(Tab.class);
    private final java.beans.PropertyChangeListener toolFocus = event -> {
        if (event.getNewValue() instanceof javax.swing.JComponent component) {
            for (ToolViewport view : presentations.values()) if (javax.swing.SwingUtilities.isDescendingFrom(component, view)) {
                revealFocusedControl(component); break;
            }
        }
    };
    private final Map<Tab, JDialog> detached = new EnumMap<>(Tab.class);
    private final EnumSet<Tab> hiddenDetachedForFocusMode = EnumSet.noneOf(Tab.class);
    private boolean closed;
    private boolean agentToolsRequested;
    private final Map<Tab, Runnable> dockTransitions = new EnumMap<>(Tab.class);
    private static final java.util.List<Tab> TAB_ORDER = java.util.List.of(Tab.SEARCH, Tab.GIT, Tab.DEBUG, Tab.TASKS, Tab.TESTS, Tab.PROBLEMS, Tab.REPLACE, Tab.LANGUAGE, Tab.QUICKFIX, Tab.AGENT);

    ToolWindowHost(Texteditor editor) {
        super(new BorderLayout());
        this.editor = editor;
        tabs.setName("workbench.tools");
        tabs.setTabLayoutPolicy(JTabbedPane.SCROLL_TAB_LAYOUT);
        AccessibilitySupport.describe(tabs, "Tool panels", "Select a tool. Scroll the strip for more tools, or use the workbench Tools menu. Escape returns to the editor.");
        KeyboardFocusSupport.installEscape(this, editor::requestActivePaneFocus);
        setBorder(BorderFactory.createMatteBorder(1, 0, 0, 0, editor.configManager.getStatusBarBackground()));
        setMinimumSize(new Dimension(0, 160));
        headerPanel = header();
        // Put dock actions in the tab strip, reserving their space in TabsUI.
        javax.swing.JLayeredPane body = new javax.swing.JLayeredPane() {
            @Override public Dimension getPreferredSize() { return tabs.getPreferredSize(); }
            @Override public void doLayout() {
                tabs.setBounds(0, 0, getWidth(), getHeight());
                Dimension actions = headerPanel.getPreferredSize();
                headerPanel.setBounds(Math.max(0, getWidth() - actions.width), 0, actions.width,
                    headerHeight());
            }
        };
        body.add(tabs, javax.swing.JLayeredPane.DEFAULT_LAYER);
        body.add(headerPanel, javax.swing.JLayeredPane.PALETTE_LAYER);
        add(body, BorderLayout.CENTER);
        addSurface(Tab.SEARCH, "Search", new WorkspaceSearchPanel(editor));
        addSurface(Tab.GIT, "Source Control", new SourceControlPanel(editor, this));
        addSurface(Tab.DEBUG, "Debug", new DebugToolPanel(editor, this));
        addSurface(Tab.TASKS, "Tasks", new TasksToolPanel(editor, this));
        addSurface(Tab.TESTS, "Tests", new TestsToolPanel(editor, this));
        addSurface(Tab.PROBLEMS, "Problems", new ProblemsToolPanel(editor, this));
        addSurface(Tab.REPLACE, "Replace", new ProjectReplaceToolPanel(editor, this));
        addSurface(Tab.QUICKFIX, "Results", new QuickfixToolPanel(editor));
        addSurface(Tab.LANGUAGE, "Language", new ToolSurface(){private final JPanel panel=new JPanel(new BorderLayout());{panel.add(new JLabel("Use :lsp status, :lsp servers or :lsp log to inspect language services."));}public JPanel component(){return panel;}public void refresh(){}});
        addSurface(Tab.AGENT, "Chat", new AgentWorkbenchPanel(editor));
        chatDock.setName("workbench.rightDock");rightTabs.setName("workbench.rightTools");rightTabs.setTabLayoutPolicy(JTabbedPane.SCROLL_TAB_LAYOUT);
        rightTabs.addTab("Source Control",presentations.get(Tab.GIT));rightTabs.addTab("Chat",presentations.get(Tab.AGENT));
        var close=new JButton(WorkbenchIcons.CLOSE);close.setName("workbench.right.close");WorkbenchStyle.quiet(close);AccessibilitySupport.describe(close,"Hide sidebar","Hide Source Control and Chat without closing their state.");close.addActionListener(e->{editor.workbenchDocks.showRight(false);editor.requestActivePaneFocus();});
        var rightBody=new javax.swing.JLayeredPane(){public Dimension getPreferredSize(){return rightTabs.getPreferredSize();}public void doLayout(){rightTabs.setBounds(0,0,getWidth(),getHeight());close.setBounds(Math.max(0,getWidth()-headerHeight()),0,headerHeight(),headerHeight());}};
        rightBody.add(rightTabs,javax.swing.JLayeredPane.DEFAULT_LAYER);rightBody.add(close,javax.swing.JLayeredPane.PALETTE_LAYER);chatDock.add(rightBody);
        rightTabs.addChangeListener(e->{if(!closed)for(var tab:java.util.List.of(Tab.GIT,Tab.AGENT))if(rightTabs.getSelectedComponent()==presentations.get(tab))refresh(tab);});
        KeyboardFocusSupport.installEscape(chatDock,editor::requestActivePaneFocus);
        java.awt.KeyboardFocusManager.getCurrentKeyboardFocusManager().addPropertyChangeListener("permanentFocusOwner", toolFocus);
        tabs.addChangeListener(event -> selectedTabChanged());
    }

    private JPanel header() {
        JPanel panel = new JPanel(new java.awt.GridLayout(1, 2, 0, 0));
        JButton close = new JButton(WorkbenchIcons.CLOSE);
        close.addActionListener(event -> { editor.workbenchDocks.showBottom(false); editor.requestActivePaneFocus(); });
        JButton detach = new JButton(WorkbenchIcons.DETACH);
        detach.addActionListener(event -> detachSelected());
        WorkbenchStyle.quiet(close); WorkbenchStyle.quiet(detach);
        close.setToolTipText("Hide tools"); detach.setToolTipText("Detach selected tool");
        AccessibilitySupport.describe(close, "Hide tools", "Hide the docked tool panel.");
        AccessibilitySupport.describe(detach, "Detach selected tool", "Open this tool in its own window. Closing it returns the tool to this dock.");
        panel.add(detach); panel.add(close);
        return panel;
    }

    private void addSurface(Tab tab, String title, ToolSurface surface) {
        surfaces.put(tab, surface);
        JPanel component=surface.component();
        if(tab!=Tab.AGENT){var report=new ToolReportPanel(component,title,editor::showScratchBuffer);reports.put(tab,report);component=report;}
        ToolViewport view = new ToolViewport(component, () -> UiZoom.scale(switch (tab) {
            case AGENT, DEBUG, REPLACE -> 480;
            case GIT, TESTS -> 380;
            default -> 320;
        }, editor.configManager.getUiZoom()));
        presentations.put(tab, view);
        if(tab!=Tab.AGENT&&tab!=Tab.GIT)tabs.addTab(title, view);
    }

    void showTab(Tab tab) {
        if (closed) return;
        ToolSurface surface = surfaces.get(tab);
        if (surface == null) return;
        if(tab==Tab.AGENT)agentToolsRequested=true;
        JDialog dialog = detached.get(tab);
        if (dialog != null && dialog.isDisplayable()) {
            dialog.setVisible(true);
            dialog.toFront();
            surface.refresh();
            return;
        }
        boolean right=tab==Tab.AGENT||tab==Tab.GIT;
        if(right){rightTabs.setSelectedComponent(presentations.get(tab));editor.workbenchDocks.showRight(true);}
        else {tabs.setSelectedComponent(presentations.get(tab));editor.workbenchDocks.preferBottomHeight(preferredHeight(tab));editor.showToolWindow();}
        if(reports.containsKey(tab))reports.get(tab).primary();
        editor.editorUiController.applyUiTheme(chatDock);editor.editorUiController.applyUiFont(chatDock);surface.refreshAppearance();surface.refresh();
    }
    GitChangesToolPanel gitPanel(){return sourceControlPanel().git();}
    SourceControlPanel sourceControlPanel(){return (SourceControlPanel)surfaces.get(Tab.GIT);}
    void toggleRight(Tab tab){if(isSelected(tab)&&(tab==Tab.GIT||tab==Tab.AGENT))editor.workbenchDocks.showRight(false);else showTab(tab);}
    JPanel rightDock(){return chatDock;}
    boolean rightVisible(){return editor.workbenchDocks!=null&&editor.workbenchDocks.rightVisible();}
    void showReport(Tab tab,String title,String text){showTab(tab);var report=reports.get(tab);if(report!=null){report.show(title,text);report.appearance(editor.resolveUiFont(),editor.resolveEditorFont());}}
    void addSelectionToChat(EditorPane pane){
        if(pane==null||pane.isDisposed()||!editor.editorPanes.contains(pane)||pane.getBuffer()==null||pane.getBuffer().isLargeFile()||pane.getAlternateComponent()!=null||pane.isMarkdownPreview()||pane.getTerminalPane()!=null||editor.agentOwnsEditor())return;
        var area=pane.getTextArea();int start=area.getSelectionStart(),end=area.getSelectionEnd();
        if(start==end||end-start>8192){editor.showMessage("Select 1–8,192 UTF-16 units to add to chat.");return;}
        var root=editor.workspaceController.activeRoot();if(root==null){editor.showMessage("Open a project before adding chat context.");return;}
        var buffer=pane.getBuffer();String path=buffer.getDisplayName();
        if(buffer.hasFilePath()){var file=buffer.getFile().toPath().toAbsolutePath().normalize();if(!file.startsWith(root)){editor.showMessage("Selection belongs to another project.");return;}path=root.relativize(file).toString();}
        try{var context=new AgentCliChatPanel.SelectionContext(root,path,buffer.documentRevision(),start,end,area.getDocument().getText(start,end-start));
            if(surfaces.get(Tab.AGENT) instanceof AgentWorkbenchPanel agents&&agents.addSelection(context))showAgentChat();
        }catch(javax.swing.text.BadLocationException|IllegalArgumentException invalid){editor.showMessage("Selection is unavailable; select the source again.");}
    }

    void observeAgentFiles(java.nio.file.Path root,java.util.List<ExternalChangeSnapshot.Change> changes,java.util.Set<String> recent){
        var marks=FileChangeMarks.from(root,changes);var pulses=FileChangeMarks.recent(root,recent);
        if(editor.fileExplorer!=null)editor.fileExplorer.observeChanges(marks,pulses);
        for(var pane:editor.editorPanes)if(pane.getAlternateComponent() instanceof FileTreePanel tree)tree.observeChanges(marks,pulses);
        if(surfaces.get(Tab.GIT) instanceof SourceControlPanel scm)scm.observeExternalChanges(root,recent);
    }
    void observedSourceReady(){if(surfaces.get(Tab.AGENT) instanceof AgentWorkbenchPanel agents)agents.observedSourceReady();}
    EditorPane followedAgentSource(FileBuffer buffer){return surfaces.get(Tab.AGENT) instanceof AgentWorkbenchPanel agents?agents.followedSource(buffer):null;}
    void pauseAgentFollowing(){if(surfaces.get(Tab.AGENT) instanceof AgentWorkbenchPanel agents)agents.pauseFollowing();}
    void retireAgentPresentation(){if(surfaces.get(Tab.AGENT) instanceof AgentWorkbenchPanel agents)agents.retirePresentation();}
    void showObservedChange(java.nio.file.Path path){if(surfaces.get(Tab.AGENT) instanceof AgentWorkbenchPanel agents&&agents.selectObserved(path))showTab(Tab.AGENT);}
    void decorateExplorer(FileTreePanel tree){if(surfaces.get(Tab.AGENT) instanceof AgentWorkbenchPanel agents)agents.decorateExplorer(tree);}
    void showAgentReview() {
        if (surfaces.get(Tab.AGENT) instanceof AgentWorkbenchPanel agents) agents.showReview();
        showTab(Tab.AGENT);
    }
    void showAgentChat(){showTab(Tab.AGENT);if(surfaces.get(Tab.AGENT) instanceof AgentWorkbenchPanel agents)agents.showChat();}
    void showAgentChanges(){if(surfaces.get(Tab.AGENT) instanceof AgentWorkbenchPanel agents)agents.showLiveChanges();showTab(Tab.AGENT);}

    java.util.Map<String,Object> watchAgentChanges(String action,String name){
        if(!(surfaces.get(Tab.AGENT) instanceof AgentWorkbenchPanel agents))throw WorkspaceApiException.invalidRequest("Agents unavailable");
        return agents.watch(action,name);
    }
    void showAgentProposal(String proposal) {
        if (surfaces.get(Tab.AGENT) instanceof AgentWorkbenchPanel agents) agents.showReview(proposal);
        editor.showMessage("Agent changes ready for review · :agent proposals");
    }

    /** Focus is an explicit user-navigation action, never a side effect of refresh/completion. */
    void focus(Tab tab) {
        ToolSurface surface = surfaces.get(tab);
        if (surface != null) javax.swing.SwingUtilities.invokeLater(() -> {
            if (!closed && surface.component().isShowing()) surface.focus();
        });
    }

    void showAgentAction(AgentAction action) {
        if (surfaces.get(Tab.AGENT) instanceof AgentWorkbenchPanel agents) agents.showAction(action);
        showTab(Tab.AGENT);
    }

    private int preferredHeight(Tab tab){return UiZoom.scale(switch(tab){case DEBUG,REPLACE -> 520;case TESTS -> 420;default -> 360;},editor.configManager.getUiZoom());}
    private void selectedTabChanged() {if(editor.workbenchDocks!=null&&isVisible())for(var tab:surfaces.keySet())if(tabs.getSelectedComponent()==presentations.get(tab))editor.workbenchDocks.preferBottomHeight(preferredHeight(tab));refreshActive();}

    boolean isSelected(Tab tab) {
        ToolSurface surface = surfaces.get(tab);
        JDialog dialog = detached.get(tab);
        if (dialog != null && dialog.isDisplayable() && dialog.isVisible()) return true;
        if(tab==Tab.AGENT||tab==Tab.GIT)return rightVisible()&&rightTabs.getSelectedComponent()==presentations.get(tab);
        return surface != null && tabs.getSelectedComponent() == presentations.get(tab) && isVisible();
    }

    void refresh(Tab tab) {
        if (closed) return;
        ToolSurface surface = surfaces.get(tab);
        if (surface != null) surface.refresh();
    }

    ProjectReplaceToolPanel replacePanel() { return (ProjectReplaceToolPanel) surfaces.get(Tab.REPLACE); }

    WorkspaceSearchPanel searchPanel() {
        ToolSurface surface = surfaces.get(Tab.SEARCH);
        return surface instanceof WorkspaceSearchPanel panel ? panel : null;
    }

    ProblemsToolPanel problemsPanel() {
        ToolSurface surface = surfaces.get(Tab.PROBLEMS);
        return surface instanceof ProblemsToolPanel panel ? panel : null;
    }

    void refreshAppearance() {
        headerPanel.setBackground(editor.configManager.getStatusBarBackground());
        tabs.setFont(editor.resolveUiFont());
        int size = headerHeight();
        for (var action : headerPanel.getComponents()) action.setPreferredSize(new Dimension(size, size));
        tabs.setUI(new WorkbenchStyle.TabsUI() {
            @Override protected java.awt.Insets getTabAreaInsets(int placement) {
                return new java.awt.Insets(0, 0, 0, headerHeight() * 2);
            }
        });
        rightTabs.setFont(editor.resolveUiFont());rightTabs.setUI(new WorkbenchStyle.TabsUI(){protected java.awt.Insets getTabAreaInsets(int placement){return new java.awt.Insets(0,0,0,headerHeight());}});
        for(var report:reports.values())report.appearance(editor.resolveUiFont(),editor.resolveEditorFont());
        for (ToolSurface surface : surfaces.values()) surface.refreshAppearance();
        for (ToolViewport view : presentations.values()) view.revalidate();
    }

    private int headerHeight() {
        UiDensity density = editor.configManager.getUiDensity();
        return density == UiDensity.DEFAULT ? UiZoom.scale(40, editor.configManager.getUiZoom())
            : density.controlHeight(tabs.getFontMetrics(tabs.getFont()), editor.configManager.getUiZoom());
    }

    void refreshDensity() {
        int size = headerHeight();
        for (var action : headerPanel.getComponents()) action.setPreferredSize(new Dimension(size, size));
        if (tabs.getUI() instanceof WorkbenchStyle.TabsUI ui) ui.refreshDensity();
        revalidate(); repaint();
    }

    void refreshActive() {
        if (closed) return;
        for(var tab:java.util.List.of(Tab.GIT,Tab.AGENT))if(isSelected(tab))surfaces.get(tab).refresh();
        for (Map.Entry<Tab, ToolSurface> entry : surfaces.entrySet()) {
            if (tabs.getSelectedComponent() == presentations.get(entry.getKey())) {
                entry.getValue().refresh();
                return;
            }
        }
    }

    record FocusState(Map<String,Object> dockState) { }
    FocusState hideForFocusMode(){
        var state=new FocusState(captureDockState());hiddenDetachedForFocusMode.clear();
        for(var entry:detached.entrySet())if(entry.getValue().isVisible()){hiddenDetachedForFocusMode.add(entry.getKey());entry.getValue().setVisible(false);}
        editor.hideToolWindow();return state;
    }
    void restoreAfterFocusMode(FocusState state){
        restoreDockSelections(state.dockState(),false);
        for(Tab tab:EnumSet.copyOf(hiddenDetachedForFocusMode)){var dialog=detached.get(tab);if(dialog!=null&&dialog.isDisplayable())dialog.setVisible(true);}hiddenDetachedForFocusMode.clear();
    }

    private void detachSelected() {
        for (Map.Entry<Tab, ToolSurface> entry : surfaces.entrySet()) {
            if (tabs.getSelectedComponent() == presentations.get(entry.getKey())) {
                detach(entry.getKey(), entry.getValue());
                return;
            }
        }
    }

    private void detach(Tab tab, ToolSurface surface) { detach(tab, surface, false); }

    private void detach(Tab tab, ToolSurface surface, boolean restoring) {
        if (closed || detached.containsKey(tab) || tab==Tab.AGENT || tab==Tab.GIT) return;
        tabs.remove(presentations.get(tab));
        JDialog dialog = new JDialog(editor, title(tab), false);
        dialog.setDefaultCloseOperation(JDialog.DISPOSE_ON_CLOSE);
        KeyboardFocusSupport.installEscape(dialog.getRootPane(), dialog::dispose);
        dialog.setLayout(new BorderLayout());
        dialog.add(presentations.get(tab), BorderLayout.CENTER);
        editor.editorUiController.prepareDialog(dialog, 820, 500);
        dialog.pack();
        dockTransitions.put(tab, WorkbenchToolWindowPlacement.restore(editor, dialog, surfaceType(tab), "panel"));
        if (restoring) dialog.setAutoRequestFocus(false);
        detached.put(tab, dialog);
        dialog.addWindowListener(new java.awt.event.WindowAdapter() {
            @Override public void windowClosed(java.awt.event.WindowEvent event) { reattach(tab, surface, dialog); }
        });
        if (!editor.goyoModeEnabled) dialog.setVisible(true);
        else hiddenDetachedForFocusMode.add(tab);
        surface.refresh();
        if (!restoring) focus(tab);
    }

    private void reattach(Tab tab, ToolSurface surface, Window dialog) {
        if (detached.get(tab) != dialog) return;
        detached.remove(tab);
        Runnable dock = dockTransitions.remove(tab);
        if (closed || editor.closingDown || !editor.isDisplayable()) return;
        if (dock != null) dock.run();
        ToolViewport presentation = presentations.get(tab);
        if (presentation.getParent() != null) presentation.getParent().remove(presentation);
        int index = 0;
        for (Tab candidate : TAB_ORDER) {
            if (candidate == tab) break;
            if (candidate!=Tab.AGENT&&candidate!=Tab.GIT&&!detached.containsKey(candidate)) index++;
        }
        tabs.insertTab(title(tab), null, presentation, null, Math.min(index, tabs.getTabCount()));
        tabs.setSelectedComponent(presentations.get(tab));
        tabs.revalidate();
        if (!editor.goyoModeEnabled) showTab(tab);
    }

    static String title(Tab tab) {
        return switch (tab) { case GIT -> "Source Control"; case DEBUG -> "Debug"; case TASKS -> "Tasks"; case TESTS -> "Tests"; case PROBLEMS -> "Problems"; case REPLACE -> "Replace"; case SEARCH -> "Search"; case LANGUAGE -> "Language"; case QUICKFIX -> "Results"; case AGENT -> "Chat"; };
    }

    private static WorkbenchLayout.SurfaceType surfaceType(Tab tab) {
        return switch (tab) {
            case GIT -> WorkbenchLayout.SurfaceType.GIT;
            case DEBUG -> WorkbenchLayout.SurfaceType.DEBUGGER;
            case TASKS -> WorkbenchLayout.SurfaceType.TASKS;
            case TESTS -> WorkbenchLayout.SurfaceType.TESTS;
            case PROBLEMS -> WorkbenchLayout.SurfaceType.PROBLEMS;
            case REPLACE -> WorkbenchLayout.SurfaceType.REPLACE;
            case SEARCH -> WorkbenchLayout.SurfaceType.SEARCH;
            case LANGUAGE -> WorkbenchLayout.SurfaceType.LANGUAGE;
            case QUICKFIX -> WorkbenchLayout.SurfaceType.QUICKFIX;
            case AGENT -> WorkbenchLayout.SurfaceType.AGENT;
        };
    }

    @Override public void close() {
        if (closed) return;
        closed = true;
        java.awt.KeyboardFocusManager.getCurrentKeyboardFocusManager().removePropertyChangeListener("permanentFocusOwner", toolFocus);
        for (ToolSurface surface : surfaces.values()) surface.close();
        for (JDialog dialog : java.util.List.copyOf(detached.values())) dialog.dispose();
        detached.clear(); dockTransitions.clear(); hiddenDetachedForFocusMode.clear();
    }

    void reattachAll() {
        for (var entry : java.util.List.copyOf(detached.entrySet())) {
            JDialog dialog = entry.getValue(); reattach(entry.getKey(), surfaces.get(entry.getKey()), dialog); dialog.dispose();
        }
    }

    Map<String,Object> captureDockState(){
        var state=new java.util.HashMap<String,Object>(editor.workbenchDocks.snapshot());
        Tab bottom=Tab.SEARCH;for(var tab:surfaces.keySet())if(tabs.getSelectedComponent()==presentations.get(tab))bottom=tab;
        int extent=editor.editorToolSplit.getHeight();state.put("ratio",extent<=0?.6:Math.max(.1,Math.min(.9,1.0-((Number)state.get("bottomHeight")).doubleValue()/extent)));
        state.put("selected",bottom.name());state.put("visible",isVisible());state.put("rightSelected",rightTabs.getSelectedComponent()==presentations.get(Tab.GIT)?"GIT":"AGENT");return Map.copyOf(state);
    }
    private void restoreDockSelections(Map<String,Object> dock,boolean startup){
        if(dock==null)return;
        var state=new java.util.HashMap<String,Object>(dock);
        try{Tab selected=Tab.valueOf(String.valueOf(dock.get("selected")));if(selected==Tab.AGENT||selected==Tab.GIT){state.putIfAbsent("rightSelected",selected.name());state.putIfAbsent("rightVisible",Boolean.TRUE.equals(dock.get("visible")));state.putIfAbsent("bottomVisible",false);}else{state.putIfAbsent("bottomVisible",Boolean.TRUE.equals(dock.get("visible")));if(!detached.containsKey(selected))tabs.setSelectedComponent(presentations.get(selected));}}catch(IllegalArgumentException ignored){}
        try{Tab right=Tab.valueOf(String.valueOf(state.get("rightSelected")));if(right==Tab.AGENT||right==Tab.GIT){rightTabs.setSelectedComponent(presentations.get(right));if(startup&&right==Tab.AGENT&&!agentToolsRequested)state.put("rightVisible",false);}}catch(IllegalArgumentException ignored){}
        if(!dock.containsKey("bottomHeight")&&dock.get("ratio") instanceof Number ratio&&Double.isFinite(ratio.doubleValue())&&ratio.doubleValue()>=.1&&ratio.doubleValue()<=.9){boolean legacyRight="AGENT".equals(dock.get("selected"))||"GIT".equals(dock.get("selected"));int extent=legacyRight?editor.workbenchDocks.getWidth():editor.editorToolSplit.getHeight();state.put(legacyRight?"rightWidth":"bottomHeight",(int)Math.round(extent*(1-ratio.doubleValue())));}
        editor.workbenchDocks.restore(state);
    }
    void restoreWorkbench(Map<String,Object> dock){
        if(closed)return;String workspace=WorkbenchToolWindowPlacement.workspaceId(editor);var saved=new EnumMap<Tab,WorkbenchPlacementState.Entry>(Tab.class);
        for(Tab tab:TAB_ORDER){var entry=editor.workbenchPlacementState.get(workspace,surfaceType(tab),"panel");if(entry!=null)saved.put(tab,entry);}reattachAll();
        for(Tab tab:TAB_ORDER){var entry=saved.get(tab);if(entry!=null&&entry.mode()==WorkbenchPlacementState.Mode.DETACHED&&"primary".equals(entry.ownerWindowId()))detach(tab,surfaces.get(tab),true);}
        restoreDockSelections(dock,true);
    }

    private static void revealFocusedControl(javax.swing.JComponent component) {
        java.awt.Rectangle area = new java.awt.Rectangle(0, 0, component.getWidth(), component.getHeight());
        if (component instanceof javax.swing.text.JTextComponent text && !(component instanceof javax.swing.JTextField)) {
            try { var caret = text.modelToView2D(text.getCaretPosition()); if (caret != null) area = caret.getBounds(); }
            catch (javax.swing.text.BadLocationException ignored) { }
        } else if (component instanceof javax.swing.JTable table) {
            if (table.getRowCount() > 0 && table.getColumnCount() > 0) area = table.getCellRect(Math.max(0, table.getSelectedRow()), Math.max(0, table.getSelectedColumn()), true);
        } else if (component instanceof javax.swing.JList<?> list) {
            if (list.getModel().getSize() > 0) { var cell = list.getCellBounds(Math.max(0, list.getSelectedIndex()), Math.max(0, list.getSelectedIndex())); if (cell != null) area = cell; }
        } else if (component instanceof javax.swing.JTree tree && tree.getSelectionPath() != null) {
            var row = tree.getPathBounds(tree.getSelectionPath()); if (row != null) area = row;
        }
        ToolViewport.reveal(component, area);
    }

    interface ToolSurface {
        JPanel component();
        void refresh();
        default void refreshAppearance() { }
        default void focus() { }
        default void close() { }
    }


}
