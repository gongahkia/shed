package shed;

import static org.junit.jupiter.api.Assertions.*;
import java.awt.Component;
import java.awt.Container;
import java.awt.Font;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import javax.swing.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AgentWorkbenchPanelTest {
    @TempDir Path temporary;
    @Test void conversationsRunIndependentlyAndStopAllCancelsPendingWorkWithoutRetargeting() throws Exception {
        Path one = Files.createDirectory(temporary.resolve("one")).toRealPath(), two = Files.createDirectory(temporary.resolve("two")).toRealPath();
        Files.writeString(one.resolve("source"), "first\n"); Files.writeString(two.resolve("source"), "second\n");
        var scope = new AtomicReference<>(new AgentSessionPanel.Scope(one, WorkspaceDocumentSource.diskOnly(), temporary.resolve("sessions")));
        var panels = new ArrayList<AgentSessionPanel>(); var services = new java.util.concurrent.CopyOnWriteArrayList<AgentSessionService>();
        var jobs = new AsyncJobService(50); var workbench = new AtomicReference<AgentWorkbenchPanel>(); var dismissals = new AtomicInteger();
        var font = new Font(Font.DIALOG, Font.PLAIN, 13);
        var documents = new EditorWorkspaceDocuments(List::of, () -> List.of(one, two), () -> scope.get().root(), one);
        try {
            SwingUtilities.invokeAndWait(() -> workbench.set(new AgentWorkbenchPanel(() -> {
                var panel = new AgentSessionPanel(jobs, scope::get, () -> List.of("fixture"), name -> new AgentProvider("fixture",
                    AcpConnectionTest.javaCommand(AcpSdkAgentFixture.class.getName(), scope.get().root().resolve("source").toString()),
                    Map.of(), Duration.ofSeconds(15), Duration.ofSeconds(20)), services::add, () -> font, () -> font);
                panels.add(panel); return panel;
            }, owner -> new AgentReviewPanel.Scope(owner == null || owner.boundRoot() == null ? scope.get().root() : owner.boundRoot(),
                temporary.resolve("sessions"), documents), jobs, () -> font, () -> font, dismissals::incrementAndGet)));
            assertEquals(1, panels.size()); assertTrue(services.isEmpty());
            click(panels.getFirst().component(), "agent.session.connect"); await(() -> button(panels.getFirst().component(), "agent.session.send").isEnabled());
            SwingUtilities.invokeAndWait(() -> {
                panels.getFirst().prepare(AgentAction.ASK);
                text(panels.getFirst(), "prompt").setText("check command"); button(panels.getFirst().component(), "agent.session.send").doClick();
            });
            await(() -> button(panels.getFirst().component(), "agent.session.approveCommands").isEnabled());
            SwingUtilities.invokeAndWait(() -> assertTrue(((JTabbedPane) find(workbench.get().component(), "agent.conversations")).getTitleAt(0).contains("Working")));
            scope.set(new AgentSessionPanel.Scope(two, WorkspaceDocumentSource.diskOnly(), temporary.resolve("sessions")));
            click(workbench.get().component(), "agent.newConversation"); assertEquals(2, panels.size()); assertEquals(1, services.size());
            click(panels.getLast().component(), "agent.session.connect"); await(() -> button(panels.getLast().component(), "agent.session.send").isEnabled());
            SwingUtilities.invokeAndWait(() -> {
                panels.getLast().prepare(AgentAction.ASK); text(panels.getLast(), "prompt").setText("Where is source?");
                button(panels.getLast().component(), "agent.session.send").doClick();
            });
            await(() -> text(panels.getLast(), "transcript").getText().contains("The source handles"));
            assertEquals(one.toRealPath(), services.getFirst().root()); assertEquals(two.toRealPath(), services.getLast().root());
            SwingUtilities.invokeAndWait(() -> {
                assertTrue(button(panels.getFirst().component(), "agent.session.approveCommands").isEnabled());
                var locations = (JList<?>) find(panels.getLast().component(), "agent.session.locations");
                assertEquals(two.resolve("source").toAbsolutePath().normalize(), ((AgentAnswer.Location) locations.getModel().getElementAt(0)).path());
            });
            // Review must follow the conversation that produced the proposal, not the currently active workspace.
            send(panels.getLast(), AgentAction.RUN, "write"); await(() -> button(panels.getLast().component(), "agent.session.review").isEnabled());
            click(panels.getLast().component(), "agent.session.review");
            await(() -> ((JTextArea) find(workbench.get().component(), "agent.before")).getText().equals("second\n"));
            click(panels.getFirst().component(), "agent.session.rejectCommands");
            await(() -> button(panels.getFirst().component(), "agent.session.send").isEnabled());
            send(panels.getFirst(), AgentAction.RUN, "write"); await(() -> button(panels.getFirst().component(), "agent.session.review").isEnabled());
            click(panels.getFirst().component(), "agent.session.review");
            await(() -> ((JTextArea) find(workbench.get().component(), "agent.before")).getText().equals("first\n"));
            assertEquals("first\n", Files.readString(one.resolve("source"))); assertEquals("second\n", Files.readString(two.resolve("source")));
            send(panels.getFirst(), AgentAction.ASK, "check command"); send(panels.getLast(), AgentAction.ASK, "check command");
            await(() -> button(panels.getFirst().component(), "agent.session.approveCommands").isEnabled()
                && button(panels.getLast().component(), "agent.session.approveCommands").isEnabled());
            click(workbench.get().component(), "agent.closeConversation");
            await(() -> services.getLast().snapshot().state() == AgentSessionService.State.CLOSED);
            SwingUtilities.invokeAndWait(() -> assertTrue(button(panels.getFirst().component(), "agent.session.approveCommands").isEnabled()));
            click(workbench.get().component(), "agent.stopAll");
            await(() -> services.stream().allMatch(s -> s.snapshot().state() == AgentSessionService.State.CLOSED));
            assertEquals(1, dismissals.get());
            SwingUtilities.invokeAndWait(() -> assertFalse(button(panels.getFirst().component(), "agent.session.approveCommands").isEnabled()));
            click(workbench.get().component(), "agent.closeConversation");
            SwingUtilities.invokeAndWait(() -> assertEquals(1, ((JTabbedPane) find(workbench.get().component(), "agent.conversations")).getTabCount()));
        } finally {
            if (workbench.get() != null) SwingUtilities.invokeAndWait(workbench.get()::close);
            documents.close(); jobs.shutdownNow(); for (var service : services) { service.close(); service.termination().get(10, TimeUnit.SECONDS); }
        }
    }
    private static void send(AgentSessionPanel panel, AgentAction action, String instruction) throws Exception {
        SwingUtilities.invokeAndWait(() -> { panel.prepare(action); text(panel, "prompt").setText(instruction); button(panel.component(), "agent.session.send").doClick(); });
    }
    private static JTextArea text(AgentSessionPanel panel, String name) { return (JTextArea) find(panel.component(), "agent.session." + name); }
    private static JButton button(Container root, String name) { return (JButton) find(root, name); }
    private static void click(Container root, String name) throws Exception { SwingUtilities.invokeAndWait(() -> button(root, name).doClick()); }
    private static Component find(Container root, String name) {
        for (Component child : root.getComponents()) { if (name.equals(child.getName())) return child;
            if (child instanceof Container nested) { var found = find(nested, name); if (found != null) return found; } }
        return null;
    }
    private static void await(BooleanSupplier condition) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20); var ready = new java.util.concurrent.atomic.AtomicBoolean();
        while (System.nanoTime() < deadline) { SwingUtilities.invokeAndWait(() -> ready.set(condition.getAsBoolean())); if (ready.get()) return; Thread.sleep(20); }
        fail("Conversation controls did not reach the expected state");
    }
}
