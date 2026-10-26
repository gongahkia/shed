package shed;

import static org.junit.jupiter.api.Assertions.*;
import java.awt.Component;
import java.awt.Container;
import java.awt.Font;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import javax.swing.*;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AgentSessionPanelTest {
    @TempDir Path temporary;
    private AsyncJobService jobs;
    private AgentSessionPanel panel;
    private final AgentSessionRegistry registry = new AgentSessionRegistry();
    private final List<AgentSessionService> owned = new CopyOnWriteArrayList<>();
    private final AtomicReference<AgentSessionPanel.Scope> scope = new AtomicReference<>();
    private final AtomicReference<AgentProvider> provider = new AtomicReference<>();

    @AfterEach void close() throws Exception {
        if (panel != null) edt(panel::close); registry.close(); if (jobs != null) jobs.shutdownNow();
        for (var session : owned) session.termination().get(10, TimeUnit.SECONDS);
    }

    @Test void constructingAndRefreshingNeverStartsAnAgent() throws Exception {
        prepare("project"); open();
        edt(() -> { panel.refresh(); assertTrue(button("connect").isEnabled()); assertFalse(button("send").isEnabled()); });
        assertTrue(owned.isEmpty());
        edt(() -> { scope.set(null); button("connect").doClick(); assertTrue(status().getText().contains("Select a workspace")); });
        assertTrue(owned.isEmpty());
    }

    @Test void connectedConversationKeepsItsWorkspaceAndPreservesTheNextDraft() throws Exception {
        Path root = prepare("project"), file = Files.writeString(root.resolve("source"), "saved\r\n");
        FileBuffer buffer = new FileBuffer(file.toFile());
        try (var editor = new EditorWorkspaceDocuments(() -> List.of(buffer), () -> List.of(root), () -> root, root)) {
            edt(() -> buffer.replaceContentUndoably("unsaved human text\n")); scope.set(new AgentSessionPanel.Scope(root, editor));
            open(); connect(); Path other = Files.createDirectory(temporary.resolve("other"));
            edt(() -> {
                scope.set(new AgentSessionPanel.Scope(other, WorkspaceDocumentSource.diskOnly())); panel.refresh();
                text("prompt").setText("read"); button("send").doClick(); text("prompt").setText("next unsent draft");
            });
            await(() -> button("send").isEnabled() && text("transcript").getText().contains("unsaved human text"));
            edt(() -> {
                assertEquals("next unsent draft", text("prompt").getText());
                assertEquals(root.toAbsolutePath().normalize().getFileName(), panel.boundRoot().getFileName());
                assertTrue(label("workspace").getText().contains("project")); assertFalse(label("workspace").getText().contains("other"));
            });
            assertEquals(root.toRealPath(), owned.getFirst().root()); assertEquals("saved\r\n", Files.readString(file));
            edt(() -> {
                assertEquals(text("transcript").getDocument().getLength(), text("transcript").getCaretPosition());
                text("transcript").select(1, 4);
            });
            send("another turn");
            await(() -> button("send").isEnabled() && text("transcript").getText().contains("Echo: another turn"));
            edt(() -> assertEquals("You", text("transcript").getSelectedText()));
        }
    }

    @Test void permissionControlsRequireAResponseAndCancellationDoesNotSwitchTabs() throws Exception {
        prepare("project"); open(); connect(); send("permission");
        await(() -> button("respond").isEnabled());
        edt(() -> {
            assertEquals(0, tabs().getSelectedIndex());
            assertEquals("reject_once", MiniJson.asObject(combo("options").getSelectedItem()).get("kind"));
            assertFalse(button("send").isEnabled()); assertTrue(text("permissionDetails").getText().contains("Fixture action"));
            assertFalse(text("permissionDetails").getText().contains("toolCallId"));
            tabs().setSelectedIndex(1); combo("options").setSelectedIndex(0); button("respond").doClick();
        });
        await(() -> button("send").isEnabled() && text("transcript").getText().contains("Decision: allow"));
        send("execute"); await(() -> button("respond").isEnabled());
        edt(() -> { combo("options").setSelectedIndex(0); button("respond").doClick(); });
        await(() -> status().getText().contains("Capability is not granted"));
        assertEquals(1, owned.getFirst().snapshot().permissions().size());
        edt(() -> button("cancel").doClick());
        await(() -> button("send").isEnabled() && text("transcript").getText().contains("[Cancelled]"));
        edt(() -> { assertEquals(1, tabs().getSelectedIndex()); assertFalse(button("respond").isEnabled()); });
        assertTrue(owned.getFirst().snapshot().permissions().isEmpty());
    }

    @Test void stoppingDuringHandshakeClosesTheProcessAndDiscardsLateStartupCallbacks() throws Exception {
        prepare("project"); provider.set(new AgentProvider("fixture", AcpConnectionTest.command("hang"), Map.of(), Duration.ofSeconds(10), Duration.ofSeconds(20)));
        open(); edt(() -> button("connect").doClick()); AcpConnectionTest.await(() -> !owned.isEmpty());
        edt(() -> button("stop").doClick()); owned.getFirst().termination().get(10, TimeUnit.SECONDS);
        await(() -> button("connect").isEnabled());
        edt(() -> { assertFalse(button("send").isEnabled()); assertTrue(status().getText().contains("cancelled")); });
        assertFalse(owned.getFirst().snapshot().diagnostics().running());
    }

    @Test void longRunningAgentWorkAndCancelDoNotWaitForTheOrdinaryJobPool() throws Exception {
        prepare("project"); var worker = java.util.concurrent.Executors.newSingleThreadExecutor();
        var entered = new java.util.concurrent.CountDownLatch(1); var release = new java.util.concurrent.CountDownLatch(1);
        worker.submit(() -> { entered.countDown(); release.await(); return null; }); assertTrue(entered.await(5, TimeUnit.SECONDS));
        jobs = new AsyncJobService(worker, 50, null);
        try {
            open(); connect(); send("permission"); await(() -> button("respond").isEnabled());
            edt(() -> button("cancel").doClick()); await(() -> button("send").isEnabled());
            assertEquals(AgentSessionService.State.IDLE, owned.getFirst().snapshot().state());
        } finally { release.countDown(); }
    }

    @Test void unsupportedResumeCanBeCorrectedWithoutLosingTheConnection() throws Exception {
        prepare("project"); provider.set(new AgentProvider("fixture", AcpConnectionTest.command("echo"), Map.of(), Duration.ofSeconds(10), Duration.ofSeconds(20)));
        open(); edt(() -> { field("resume").setText("old-session"); button("connect").doClick(); });
        await(() -> button("begin").isEnabled());
        edt(() -> {
            assertFalse(button("send").isEnabled()); assertTrue(status().getText().contains("does not advertise session loading"));
            field("resume").setText(""); button("begin").doClick();
        });
        await(() -> button("send").isEnabled()); assertEquals(1, owned.size());
    }

    @Test void authenticationRequiresExplicitForegroundOptInAndAnExplicitSignIn() throws Exception {
        prepare("project"); provider.set(new AgentProvider("fixture", AcpConnectionTest.command("auth"), Map.of(), Duration.ofSeconds(10), Duration.ofSeconds(20)));
        open(); edt(() -> button("connect").doClick());
        await(() -> button("begin").isEnabled());
        edt(() -> {
            assertTrue(status().getText().contains("Authentication required")); assertFalse(button("authenticate").isEnabled());
            assertFalse(((JCheckBox) find(panel.component(), "agent.session.foreground")).isEnabled());
            assertEquals(0, tabs().getSelectedIndex()); button("stop").doClick();
        });
        await(() -> button("connect").isEnabled());
        edt(() -> {
            ((JCheckBox) find(panel.component(), "agent.session.foreground")).setSelected(true); button("connect").doClick();
        });
        await(() -> button("authenticate").isEnabled());
        edt(() -> { assertFalse(button("send").isEnabled()); button("authenticate").doClick(); });
        await(() -> button("send").isEnabled());
        edt(() -> { assertEquals(0, tabs().getSelectedIndex()); assertFalse(status().getText().contains("Authentication required")); });
        assertEquals(2, owned.size()); assertTrue(owned.getLast().policy().capabilities().contains(AgentPolicy.Capability.OPEN_UI));
    }

    private Path prepare(String name) throws Exception {
        Path root = Files.createDirectory(temporary.resolve(name)); scope.set(new AgentSessionPanel.Scope(root, WorkspaceDocumentSource.diskOnly()));
        provider.set(new AgentProvider("fixture", AcpConnectionTest.javaCommand(AcpSdkAgentFixture.class.getName(), root.resolve("source").toString()),
            Map.of(), Duration.ofSeconds(15), Duration.ofSeconds(20))); return root;
    }
    private void open() throws Exception {
        if (jobs == null) jobs = new AsyncJobService(50);
        edt(() -> panel = new AgentSessionPanel(jobs, scope::get, () -> List.of("fixture"), ignored -> provider.get(), session -> {
            owned.add(session); registry.own(session);
        }, () -> new Font(Font.DIALOG, Font.PLAIN, 13), () -> new Font(Font.MONOSPACED, Font.PLAIN, 13)));
    }
    private void connect() throws Exception { edt(() -> button("connect").doClick()); await(() -> button("send").isEnabled()); }
    private void send(String value) throws Exception { edt(() -> { text("prompt").setText(value); button("send").doClick(); }); }
    private void await(BooleanSupplier condition) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        while (System.nanoTime() < deadline) {
            var done = new AtomicReference<Boolean>(); edt(() -> { panel.refresh(); done.set(condition.getAsBoolean()); });
            if (done.get()) return; Thread.sleep(10);
        }
        edt(() -> fail("Agent controls did not reach expected state: " + status().getText()));
    }
    private JButton button(String name) { return (JButton) find(panel.component(), "agent.session." + name); }
    private JTextArea text(String name) { return (JTextArea) find(panel.component(), "agent.session." + name); }
    private JTextField field(String name) { return (JTextField) find(panel.component(), "agent.session." + name); }
    private JLabel label(String name) { return (JLabel) find(panel.component(), "agent.session." + name); }
    private JLabel status() { return label("status"); }
    private JComboBox<?> combo(String name) { return (JComboBox<?>) find(panel.component(), "agent.session." + name); }
    private JTabbedPane tabs() { return (JTabbedPane) find(panel.component(), "agent.session.views"); }
    private static Component find(Container container, String name) {
        for (Component child : container.getComponents()) {
            if (name.equals(child.getName())) return child;
            if (child instanceof Container nested) { Component found = find(nested, name); if (found != null) return found; }
        }
        return null;
    }
    private static void edt(Runnable action) throws Exception { SwingUtilities.invokeAndWait(action); }
}
