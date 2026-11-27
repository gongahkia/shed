package shed;

import java.awt.Component;
import java.awt.Toolkit;
import java.awt.AWTEvent;
import java.awt.event.AWTEventListener;
import java.awt.event.KeyEvent;
import java.awt.event.MouseEvent;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.function.BooleanSupplier;
import javax.swing.*;
import javax.swing.text.JTextComponent;

/** Bounded presentation actions on existing controls, with no source-edit or command execution API. */
final class AgentUiActions implements AutoCloseable {
    static final int MAX_TEXT = 512;
    static final List<String> IDS = List.of("find.open", "find.query", "find.next", "find.previous", "find.case", "find.regex", "find.word", "find.close");
    interface Surface {
        Map<String,Object> state();
        Component target(String action);
        void invoke(String action);
        boolean current();
        boolean motion();
    }
    record Command(String action, long revision, String agent, String text) {
        Command {
            if (!IDS.contains(action) || revision < 0 || agent == null || agent.isBlank() || agent.length() > 40
                || agent.codePoints().anyMatch(Character::isISOControl) || text == null || text.length() > MAX_TEXT
                || text.codePoints().anyMatch(Character::isISOControl) || !validUtf16(text)
                || !action.equals("find.query") && !text.isEmpty()) throw failure(WorkspaceApiException.Code.INVALID_REQUEST);
        }
    }
    private final Surface surface;
    private final AgentUiPresentation presentation;
    private javax.swing.Timer timer;
    private AWTEventListener input;
    private CompletableFuture<Map<String,Object>> pending;
    private boolean closed;
    private AgentTextPulse pulse;

    AgentUiActions(Surface surface) { this(surface,new AgentUiPresentation()); }
    AgentUiActions(Surface surface,AgentUiPresentation presentation) { this.surface=surface;this.presentation=presentation; }

    Map<String,Object> catalog() {
        requireEdt();
        var actions = new ArrayList<Map<String,Object>>();
        for (String id : IDS) actions.add(Map.of("id", id, "kind", id.equals("find.query") ? "text" : "control", "enabled", enabled(id)));
        return Map.of("kind", "ui-actions", "schemaVersion", 1, "actions", actions);
    }
    Map<String,Object> state() { requireEdt(); return surface.state(); }
    private boolean enabled(String id) {
        var state = surface.state();
        if (closed || !surface.current()) return false;
        if (id.equals("find.open")) return true;
        if (!Boolean.TRUE.equals(state.get("visible")) || !"find".equals(state.get("mode"))) return false;
        Component control = surface.target(id);
        return control != null && control.isEnabled() && (id.equals("find.next") || id.equals("find.previous")
            ? "ready".equals(state.get("phase")) : true);
    }

    CompletableFuture<Map<String,Object>> invoke(Command command, BooleanSupplier authorized) {
        requireEdt();
        if (closed || !authorized.getAsBoolean()) throw failure(WorkspaceApiException.Code.AUTHORIZATION_REVOKED);
        if (pending != null) throw failure(WorkspaceApiException.Code.BUSY);
        if (!Objects.equals(surface.state().get("revision"), command.revision())) throw failure(WorkspaceApiException.Code.CONTEXT_CHANGED);
        if (!enabled(command.action())) throw failure(WorkspaceApiException.Code.BOUNDARY_UNAVAILABLE);
        if (command.action().equals("find.open")) surface.invoke(command.action());
        Component target = surface.target(command.action());
        if (target == null || !target.isShowing()) throw failure(WorkspaceApiException.Code.BOUNDARY_UNAVAILABLE);
        var completed = new CompletableFuture<Map<String,Object>>(); pending = completed;
        presentation.show(target, command.agent(), surface.motion());
        long started = System.nanoTime();
        long[] revision = { ((Number)surface.state().get("revision")).longValue() };
        int[] written = {-1}; boolean[] applied = {false};
        long[] finishing = {-1};
        var window = SwingUtilities.getWindowAncestor(target);
        input = event -> {
            if (event instanceof KeyEvent key && key.getID() == KeyEvent.KEY_PRESSED
                || event instanceof MouseEvent mouse && (mouse.getID() == MouseEvent.MOUSE_PRESSED || mouse.getID() == MouseEvent.MOUSE_WHEEL)) {
                if (event.getSource() instanceof Component component && SwingUtilities.getWindowAncestor(component) == window)
                    cancel(WorkspaceApiException.Code.CANCELLED);
            }
        };
        Toolkit.getDefaultToolkit().addAWTEventListener(input, AWTEvent.KEY_EVENT_MASK | AWTEvent.MOUSE_EVENT_MASK | AWTEvent.MOUSE_WHEEL_EVENT_MASK);
        timer = new javax.swing.Timer(16, event -> {
            try {
                if (completed.isCancelled()) throw failure(WorkspaceApiException.Code.CANCELLED);
                if (!authorized.getAsBoolean()) throw failure(WorkspaceApiException.Code.AUTHORIZATION_REVOKED);
                if (!surface.current() || !target.isShowing() || !target.isEnabled()
                    || !Objects.equals(surface.state().get("revision"), revision[0])) throw failure(WorkspaceApiException.Code.CONTEXT_CHANGED);
                long elapsed = (System.nanoTime() - started) / 1_000_000;
                if (elapsed > 3000) throw failure(WorkspaceApiException.Code.TIMEOUT);
                if (finishing[0] >= 0) {
                    presentation.frame(1f,false);
                    if (elapsed - finishing[0] >= 140) finish(surface.state(),null);
                    return;
                }
                int clickMillis = surface.motion() ? 300 : 32;
                if (elapsed < clickMillis) { presentation.frame((float)elapsed / clickMillis,false); return; }
                if (command.action().equals("find.query")) {
                    JTextComponent field = (JTextComponent)target;
                    if (written[0] < 0) { field.requestFocusInWindow(); field.setText(""); written[0] = 0; pulse=new AgentTextPulse(field,"writing",surface.motion()); }
                    // complex queries install as one shaped chunk instead of repeatedly laying out partial scripts
                    int end=AgentTextPulse.typedUnits(command.text(),elapsed-clickMillis,surface.motion());
                    while (end < command.text().length() && !GraphemeBoundary.isBoundary(command.text(), end)) end++;
                    if (end > written[0]) {
                        field.getDocument().insertString(field.getDocument().getLength(), command.text().substring(written[0], end), null);
                        field.setCaretPosition(field.getDocument().getLength()); written[0] = end;
                    }
                    pulse.frame(0,end,end/(double)Math.max(1,command.text().length()),elapsed);
                    presentation.badgeOnly(!AgentTextPulse.small(command.text()));presentation.activity("writing",Math.min(1,(elapsed-clickMillis)/650.0));presentation.frame(1f,AgentTextPulse.small(command.text()));
                    applied[0] = end == command.text().length();
                } else if (!applied[0]) {
                    presentation.frame(1f,false);
                    if (!command.action().equals("find.open")) surface.invoke(command.action());
                    else target.requestFocusInWindow();
                    applied[0] = true;
                }
                var state = surface.state(); revision[0] = ((Number)state.get("revision")).longValue();
                if (applied[0] && !"searching".equals(state.get("phase"))) {
                    if (command.action().equals("find.query") && surface.motion()) finishing[0] = elapsed;
                    else finish(state,null);
                }
            } catch (WorkspaceApiException rejected) { finish(null, rejected); }
            catch (Exception invalid) { cancel(WorkspaceApiException.Code.INVALID_CONTEXT); }
        });
        timer.start();
        return completed;
    }

    void cancel(WorkspaceApiException.Code code) { requireEdt(); finish(null, failure(code)); }
    private void finish(Map<String,Object> state, RuntimeException error) {
        if (timer != null) timer.stop(); timer = null;
        if (input != null) Toolkit.getDefaultToolkit().removeAWTEventListener(input); input = null;
        if(pulse!=null){pulse.close();pulse=null;}
        presentation.settle();
        var done = pending; pending = null;
        if (done != null) { if (error == null) done.complete(state); else done.completeExceptionally(error); }
    }
    boolean active() { return pending != null; }
    @Override public void close() { requireEdt(); closed = true; cancel(WorkspaceApiException.Code.CANCELLED); presentation.close(); }
    static boolean validUtf16(String text) {
        for (int i=0;i<text.length();i++) {
            char c=text.charAt(i);
            if (Character.isHighSurrogate(c)) { if (++i == text.length() || !Character.isLowSurrogate(text.charAt(i))) return false; }
            else if (Character.isLowSurrogate(c)) return false;
        }
        return true;
    }
    private static WorkspaceApiException failure(WorkspaceApiException.Code code) { return new WorkspaceApiException(code, "UI action unavailable: " + code); }
    private static void requireEdt() { if (!SwingUtilities.isEventDispatchThread()) throw new IllegalStateException("UI actions belong on the EDT"); }
}
