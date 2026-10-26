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

    @Test void connectionControlsRemainReachableInAShortNarrowDock() throws Exception {
        prepare("project"); open();
        edt(() -> {
            tabs().setSelectedIndex(2); panel.component().setSize(480, 480);
            for (int pass = 0; pass < 4; pass++) layout(panel.component());
            var scroll = (JScrollPane) find(panel.component(), "agent.session.connectionDetails");
            var content = (JComponent) scroll.getViewport().getView();
            assertTrue(scroll.getViewport().getExtentSize().height > 0);
            assertTrue(content.getHeight() > scroll.getViewport().getExtentSize().height);
            assertEquals(scroll.getViewport().getExtentSize().width, content.getWidth());
            for (String name : List.of("resume", "reviewWrites", "foreground", "authMethods", "authenticate", "begin", "diagnostics")) {
                var control = (JComponent) find(panel.component(), "agent.session." + name);
                var bounds = SwingUtilities.convertRectangle(control.getParent(), control.getBounds(), content);
                assertTrue(bounds.width > 0 && bounds.height > 0, name);
                content.scrollRectToVisible(bounds); layout(scroll);
                assertTrue(scroll.getViewport().getViewRect().contains(bounds), name + " must be reachable by scrolling");
            }
        });
        assertTrue(owned.isEmpty());
    }

    private static void layout(Container container) {
        container.doLayout();
        for (Component child : container.getComponents()) if (child instanceof Container nested) layout(nested);
    }

    @Test void connectedConversationKeepsItsWorkspaceAndPreservesTheNextDraft() throws Exception {
        Path root = prepare("project"), file = Files.writeString(root.resolve("source"), "saved\r\n");
        FileBuffer buffer = new FileBuffer(file.toFile());
        try (var editor = new EditorWorkspaceDocuments(() -> List.of(buffer), () -> List.of(root), () -> root, root)) {
            edt(() -> buffer.replaceContentUndoably("unsaved human text\n")); scope.set(new AgentSessionPanel.Scope(root, editor, temporary.resolve("sessions")));
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
        await(() -> status().getText().contains("only permits reading and searching"));
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

    @Test void taskCreatesOneMultiFileProposalAndActualReviewKeepsTheBufferUnsaved() throws Exception {
        Path root = prepare("project"), file = Files.writeString(root.resolve("source"), "disk\n"), sessions = temporary.resolve("sessions");
        FileBuffer buffer = new FileBuffer(file.toFile());
        try (var documents = new EditorWorkspaceDocuments(() -> List.of(buffer), () -> List.of(root), () -> root, root)) {
            edt(() -> buffer.replaceContentUndoably("human draft\n"));
            scope.set(new AgentSessionPanel.Scope(root, documents, sessions)); open(); connect();
            var review = new AtomicReference<AgentReviewPanel>(); var opened = new java.util.concurrent.atomic.AtomicBoolean();
            edt(() -> {
                review.set(new AgentReviewPanel(jobs, () -> new AgentReviewPanel.Scope(panel.boundRoot(), panel.boundSessions(), documents),
                    () -> new Font(Font.DIALOG, Font.PLAIN, 13), () -> new Font(Font.MONOSPACED, Font.PLAIN, 13)));
                panel.onReview(id -> { opened.set(true); review.get().showProposal(id); });
            });
            send("write"); await(() -> button("review").isEnabled() && button("send").isEnabled());
            edt(() -> {
                assertFalse(opened.get()); assertEquals(0, tabs().getSelectedIndex()); assertTrue(status().getText().contains("Review changes"));
                assertFalse(Files.exists(root.resolve("helper"))); assertEquals("human draft\n", buffer.getContent()); button("review").doClick();
            });
            await(() -> ((JButton) find(review.get().component(), "agent.apply")).isEnabled());
            edt(() -> {
                assertEquals("human draft\n", ((JTextArea) find(review.get().component(), "agent.before")).getText());
                assertEquals("agent write\n", ((JTextArea) find(review.get().component(), "agent.proposed")).getText());
                ((JButton) find(review.get().component(), "agent.apply")).doClick();
            });
            await(() -> buffer.getContent().equals("agent write\n"));
            assertEquals("agent write\n", documents.snapshots().getFirst().content()); assertEquals("disk\n", Files.readString(file));
            assertEquals("new helper\n", Files.readString(root.resolve("helper")));
            assertTrue(buffer.isModified()); assertTrue(opened.get());
        }
    }

    @Test void disablingFileReviewBeforeConnectKeepsTheAgentReadOnly() throws Exception {
        Path root = prepare("project"), file = Files.writeString(root.resolve("source"), "human\n");
        scope.set(new AgentSessionPanel.Scope(root, WorkspaceDocumentSource.diskOnly(), temporary.resolve("sessions"))); open();
        edt(() -> ((JCheckBox) find(panel.component(), "agent.session.reviewWrites")).setSelected(false));
        connect(); send("write");
        await(() -> button("send").isEnabled() && status().getText().contains("reviewed changes enabled"));
        assertEquals("human\n", Files.readString(file)); assertTrue(owned.getFirst().snapshot().writes().isEmpty());
        assertFalse(Files.exists(temporary.resolve("sessions")));
        assertFalse(owned.getFirst().policy().capabilities().contains(AgentPolicy.Capability.PROPOSE));
    }

    @Test void selectionActionUsesCapturedUnsavedCodeAndTheActualReviewPanel() throws Exception {
        Path root = prepare("project"), file = Files.writeString(root.resolve("source"), "saved\n"), sessions = temporary.resolve("sessions");
        FileBuffer buffer = new FileBuffer(file.toFile());
        try (var documents = new EditorWorkspaceDocuments(() -> List.of(buffer), () -> List.of(root), () -> root, root)) {
            edt(() -> buffer.replaceContentUndoably("before chosen after\n"));
            scope.set(new AgentSessionPanel.Scope(root, documents, sessions)); open(); connect();
            var review = new AtomicReference<AgentReviewPanel>(); var opened = new java.util.concurrent.atomic.AtomicBoolean();
            edt(() -> {
                panel.selectionSource(() -> documents.captureSelection(buffer, 7, 13)); panel.prepare(AgentAction.EDIT);
                review.set(new AgentReviewPanel(jobs, () -> new AgentReviewPanel.Scope(panel.boundRoot(), panel.boundSessions(), documents),
                    () -> new Font(Font.DIALOG, Font.PLAIN, 13), () -> new Font(Font.MONOSPACED, Font.PLAIN, 13)));
                panel.onReview(id -> { opened.set(true); review.get().showProposal(id); });
                text("prompt").setText("uppercase"); button("send").doClick(); text("prompt").setText("next draft");
            });
            await(() -> button("send").isEnabled() && button("review").isEnabled());
            assertEquals("before chosen after\n", documents.snapshots().getFirst().content());
            edt(() -> {
                assertFalse(opened.get()); assertEquals(0, tabs().getSelectedIndex()); assertEquals("next draft", text("prompt").getText());
                assertFalse(text("transcript").getText().contains("\"replacement\"")); button("review").doClick();
            });
            await(() -> ((JButton) find(review.get().component(), "agent.apply")).isEnabled());
            edt(() -> {
                assertEquals("before CHOSEN after\n", ((JTextArea) find(review.get().component(), "agent.proposed")).getText());
                ((JButton) find(review.get().component(), "agent.apply")).doClick();
            });
            await(() -> buffer.getContent().equals("before CHOSEN after\n"));
            assertEquals("saved\n", Files.readString(file)); assertTrue(buffer.isModified());
        }
    }

    @Test void selectionActionRejectsAnOldCaptureBeforeAnyProposalIsApplied() throws Exception {
        Path root = prepare("project"), file = Files.writeString(root.resolve("source"), "original\n");
        FileBuffer buffer = new FileBuffer(file.toFile());
        try (var documents = new EditorWorkspaceDocuments(() -> List.of(buffer), () -> List.of(root), () -> root, root)) {
            scope.set(new AgentSessionPanel.Scope(root, documents, temporary.resolve("sessions"))); open(); connect();
            edt(() -> {
                panel.selectionSource(() -> documents.captureSelection(buffer, 0, 8)); panel.prepare(AgentAction.EDIT);
                buffer.replaceContentUndoably("later human draft\n");
            });
            send("uppercase"); await(() -> button("send").isEnabled() && status().getText().contains("Document changed"));
            assertEquals("later human draft\n", documents.snapshots().getFirst().content()); edt(() -> assertFalse(button("review").isEnabled()));
        }
    }

    @Test void cancellingASelectionActionDiscardsItsReplyAndReleasesTheAgent() throws Exception {
        Path root = prepare("project"), file = Files.writeString(root.resolve("source"), "human\n");
        FileBuffer buffer = new FileBuffer(file.toFile());
        try (var documents = new EditorWorkspaceDocuments(() -> List.of(buffer), () -> List.of(root), () -> root, root)) {
            scope.set(new AgentSessionPanel.Scope(root, documents, temporary.resolve("sessions"))); open(); connect();
            edt(() -> { panel.selectionSource(() -> documents.captureSelection(buffer, 0, 5)); panel.prepare(AgentAction.EDIT); });
            send("wait for cancellation"); await(() -> button("respond").isEnabled());
            edt(() -> button("cancel").doClick()); await(() -> button("send").isEnabled());
            assertEquals("human\n", documents.snapshots().getFirst().content());
            edt(() -> { assertFalse(button("review").isEnabled()); assertEquals(0, tabs().getSelectedIndex()); });
            assertTrue(owned.getFirst().snapshot().permissions().isEmpty());
            send("uppercase"); await(() -> button("review").isEnabled());
        }
    }

    @Test void askActionShowsNavigableLocationsWithoutAutomaticallyOpeningThem() throws Exception {
        Path root = prepare("project"), file = Files.writeString(root.resolve("source"), "entry point\n");
        open(); connect(); var opened = new AtomicReference<AgentAnswer.Location>();
        edt(() -> { panel.prepare(AgentAction.ASK); panel.onNavigate(opened::set); });
        send("Where is the entry point?");
        await(() -> button("send").isEnabled() && status().getText().contains("Answer complete"));
        edt(() -> {
            assertNull(opened.get()); assertTrue(text("transcript").getText().contains("The source handles the request."));
            assertFalse(text("transcript").getText().contains("\"answer\""));
            ((JList<?>) find(panel.component(), "agent.session.locations")).setSelectedIndex(0); button("openLocation").doClick();
        });
        await(() -> opened.get() != null); assertEquals(file.toRealPath(), opened.get().path()); assertEquals(1, opened.get().line());
        opened.set(null); Files.writeString(file, "changed reference\n");
        edt(() -> button("openLocation").doClick()); await(() -> status().getText().contains("changed since the answer"));
        assertNull(opened.get());
    }

    private Path prepare(String name) throws Exception {
        Path root = Files.createDirectory(temporary.resolve(name)); scope.set(new AgentSessionPanel.Scope(root, WorkspaceDocumentSource.diskOnly(), temporary.resolve("sessions")));
        provider.set(new AgentProvider("fixture", AcpConnectionTest.javaCommand(AcpSdkAgentFixture.class.getName(), root.resolve("source").toString()),
            Map.of(), Duration.ofSeconds(15), Duration.ofSeconds(20))); return root;
    }
    private void open() throws Exception {
        if (jobs == null) jobs = new AsyncJobService(50);
        edt(() -> panel = new AgentSessionPanel(jobs, scope::get, () -> List.of("fixture"), ignored -> provider.get(), session -> {
            owned.add(session); registry.own(session);
        }, () -> new Font(Font.DIALOG, Font.PLAIN, 13), () -> new Font(Font.MONOSPACED, Font.PLAIN, 13)));
        edt(() -> combo("action").setSelectedItem(AgentAction.RUN));
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
