package shed;

import java.util.*;
import java.util.function.Consumer;
import javax.swing.*;

/** Shared presentation state for both prototypes; all validation/persistence/document changes
 * go through the production application ports. No replacement editor model is introduced. */
public final class LabModel implements AutoCloseable {
    public record Doc(DocumentWorkspace.Id id, String name, boolean dirty, boolean active) { }
    public record Style(int surface, int raised, int field, int foreground, int muted, int border,
                        int accent, int selection, double zoom, float fontSize, String fontFamily) { }
    public record State(List<Doc> documents, List<SettingsApplication.Setting> settings, Style style,
                        String query, String feedback, boolean error, String status, boolean detached) { }
    private static final Set<String> KEYS = Set.of("theme", "font.family", "font.size", "ui.font.family", "ui.font.size", "ui.zoom");
    private final Texteditor editor;
    private final List<Consumer<State>> listeners = new java.util.concurrent.CopyOnWriteArrayList<>();
    private final Map<String,String> drafts = new java.util.concurrent.ConcurrentHashMap<>();
    private final javax.swing.Timer refresh;
    private volatile State state;
    private String query = "", feedback = "Apply a change to save it.";
    private boolean error;
    private Runnable show = () -> {}, dismiss = () -> {}, detach = () -> {};
    private boolean detached;
    private volatile boolean closed;
    private final Set<String> painted = java.util.concurrent.ConcurrentHashMap.newKeySet();

    LabModel(Texteditor editor) {
        this.editor = editor;
        publish();
        refresh = new javax.swing.Timer(200, event -> publish());
        refresh.start();
    }
    public static String title(String key) {
        return switch(key) {
            case "theme" -> "Theme"; case "font.family" -> "Editor font"; case "font.size" -> "Editor font size";
            case "ui.font.family" -> "Interface font"; case "ui.font.size" -> "Interface font size"; case "ui.zoom" -> "Interface scale";
            default -> key;
        };
    }
    public State state() { return state; }
    public AutoCloseable listen(Consumer<State> listener) {
        if(closed)return () -> {};
        listeners.add(listener); listener.accept(state);
        return () -> listeners.remove(listener);
    }
    public String draft(SettingsApplication.Setting setting) { return drafts.getOrDefault(setting.key(), setting.currentValue()); }
    public void draft(String key, String value) { if(!closed)drafts.put(key, value); }
    public void query(String value) { if(closed)return; if(!SwingUtilities.isEventDispatchThread()){SwingUtilities.invokeLater(()->query(value));return;} query = value; publish(); }
    public void update(String key, String value) {
        if(closed)return;
        if (!SwingUtilities.isEventDispatchThread()) { SwingUtilities.invokeLater(() -> update(key, value)); return; }
        // Keep the fixture client area equivalent when production rebuilds its menu.
        System.setProperty("apple.laf.useScreenMenuBar","false");
        feedback = editor.settingsApplication.update(key, value);
        error = feedback.startsWith("Error");
        if (!error) drafts.remove(key);
        editor.openEditorsBar.setVisible(false);
        publish();
    }
    public void activate(DocumentWorkspace.Id id) {
        if(closed)return;
        if(!SwingUtilities.isEventDispatchThread()){SwingUtilities.invokeLater(()->activate(id));return;}
        editor.documents.activate(id); focusEditor(); publish();
    }
    public void focusEditor() { if(closed)return; var surface = editor.documents.activeSurface(); if (surface != null) surface.focus(); }
    public void show() { show.run(); }
    void detached(boolean value) { detached = value; publish(); }
    public void dismiss() { dismiss.run(); }
    public void detach() { detach.run(); }
    void actions(Runnable show, Runnable dismiss, Runnable detach) { this.show = show; this.dismiss = dismiss; this.detach = detach; }
    public void painted(String slot) { painted.add(slot); }
    boolean ready() { return painted.containsAll(Set.of("navigation", "settings")); }
    int subscriptions() {return listeners.size();}
    public void publish() {
        if(closed)return;
        var active = editor.documents.activeDocument();
        var docs = editor.documents.documents().stream().map(d -> new Doc(d.id(), d.name(), d.modified(), active != null && active.id().equals(d.id()))).toList();
        var settings = editor.settingsApplication.search(query).stream().filter(d -> KEYS.contains(d.key())).toList();
        var c = editor.configManager;
        Style style = new Style(c.getNormalColor().getRGB(), c.getRaisedSurfaceColor().getRGB(), c.getFloatingSurfaceColor().getRGB(),
            c.getEditorForeground().getRGB(), c.getMutedForegroundColor().getRGB(), c.getSubtleBorderColor().getRGB(),
            c.getFocusColor().getRGB(), c.getSelectionColor().getRGB(), c.getUiZoom(), editor.resolveUiFont().getSize2D() / (float)c.getUiZoom(), editor.resolveUiFont().getFamily());
        String status = active == null ? "No document" : (active.modified() ? "Unsaved changes" : "All changes saved")
            + "  ·  " + docs.size() + " open documents" + (active.readOnly() ? "  ·  Read only" : "");
        State next = new State(docs, settings, style, query, feedback, error, status, detached);
        if (!next.equals(state)) { state = next; for (var listener : List.copyOf(listeners)) listener.accept(state); }
    }
    @Override public void close() { closed=true; refresh.stop(); listeners.clear(); drafts.clear(); show = dismiss = detach = () -> {}; }
}
