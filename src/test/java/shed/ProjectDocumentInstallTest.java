package shed;

import static org.junit.jupiter.api.Assertions.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.io.TempDir;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.Test;

/** Synthetic, in-process EDT contract tests; no shell, network, filesystem or JFrame. */
class ProjectDocumentInstallTest {
    private static ProjectDocumentInstall.Result edt(java.util.concurrent.Callable<ProjectDocumentInstall.Result> action) throws Exception {
        var result = new java.util.concurrent.atomic.AtomicReference<ProjectDocumentInstall.Result>();
        var failure = new java.util.concurrent.atomic.AtomicReference<Exception>();
        SwingUtilities.invokeAndWait(() -> {
            try { result.set(action.call()); }
            catch (Exception error) { failure.set(error); }
        });
        if (failure.get() != null) throw failure.get();
        return result.get();
    }

    @Test void checkedCommitAppliesOnce() throws Exception {
        var ticket = new ProjectDocumentInstall.Ticket();
        var installs = new AtomicInteger();
        assertEquals(ProjectDocumentInstall.Outcome.APPLIED,
            edt(() -> ProjectDocumentInstall.commit(ticket, () -> true, installs::incrementAndGet)).outcome());
        assertEquals(ProjectDocumentInstall.Outcome.APPLIED,
            edt(() -> ProjectDocumentInstall.commit(ticket, () -> true, installs::incrementAndGet)).outcome());
        assertEquals(1, installs.get());
        assertFalse(ticket.cancel());
    }

    @Test void staleOrUnauthorizedContextDoesNotInstall() throws Exception {
        var ticket = new ProjectDocumentInstall.Ticket();
        var installs = new AtomicInteger();
        assertEquals(ProjectDocumentInstall.Outcome.REJECTED,
            edt(() -> ProjectDocumentInstall.commit(ticket, () -> false, installs::incrementAndGet)).outcome());
        assertEquals(0, installs.get());
        assertFalse(ticket.cancel());
    }

    @Test void cancelledBeforeEdtCommitNeverInstalls() throws Exception {
        var ticket = new ProjectDocumentInstall.Ticket();
        assertTrue(ticket.cancel());
        var installs = new AtomicInteger();
        assertEquals(ProjectDocumentInstall.Outcome.CANCELLED,
            edt(() -> ProjectDocumentInstall.commit(ticket, () -> true, installs::incrementAndGet)).outcome());
        assertEquals(0, installs.get());
    }

    @Test void cancellationDuringPreflightPreventsCommit() throws Exception {
        var ticket = new ProjectDocumentInstall.Ticket();
        var installs = new AtomicInteger();
        assertEquals(ProjectDocumentInstall.Outcome.CANCELLED,
            edt(() -> ProjectDocumentInstall.commit(ticket, () -> { assertTrue(ticket.cancel()); return true; },
                installs::incrementAndGet)).outcome());
        assertEquals(0, installs.get());
    }

    @Test void cancellationAfterCommitBeginsCannotReportCancelled() throws Exception {
        var ticket = new ProjectDocumentInstall.Ticket();
        assertEquals(ProjectDocumentInstall.Outcome.APPLIED,
            edt(() -> ProjectDocumentInstall.commit(ticket, () -> true, () -> assertFalse(ticket.cancel()))).outcome());
        assertFalse(ticket.cancel());
    }

    @Test void installationFailureReportsUnknownRatherThanClaimingRejection() throws Exception {
        var ticket = new ProjectDocumentInstall.Ticket();
        assertEquals(ProjectDocumentInstall.Outcome.OUTCOME_UNKNOWN,
            edt(() -> ProjectDocumentInstall.commit(ticket, () -> true, () -> { throw new IllegalStateException("partial change"); })).outcome());
        assertEquals(ProjectDocumentInstall.Outcome.OUTCOME_UNKNOWN, ticket.outcome());
    }

    @Test void thrownPreconditionFailsClosedWithoutRunningInstaller() throws Exception {
        var ticket = new ProjectDocumentInstall.Ticket();
        var installs = new AtomicInteger();
        assertEquals(ProjectDocumentInstall.Outcome.REJECTED,
            edt(() -> ProjectDocumentInstall.commit(ticket, () -> { throw new IllegalStateException("stale pane"); },
                installs::incrementAndGet)).outcome());
        assertEquals(0, installs.get());
    }

    @Test void preparedProjectResourceUsesExistingCanonicalStore(@TempDir Path temporary) throws Exception {
        Path root = Files.createDirectory(temporary.resolve("project")).toRealPath();
        Files.writeString(root.resolve("Example.java"), "prepared source\n");
        var pin = ProjectBoundFileAcquisition.pinAuthorizedRoot(root);
        var store = new LegacyDocumentStore(null);
        var prepared = store.prepareProjectFile(pin, "Example.java", () -> false);
        assertEquals(root, prepared.root().path());
        assertEquals("prepared source\n", prepared.buffer().getContent());
        assertEquals(root.resolve("Example.java").toFile(), prepared.buffer().getFile());
        assertTrue(store.legacyBuffers().isEmpty());
    }

    @Test void cannotInstallOffEdt() {
        var ticket = new ProjectDocumentInstall.Ticket();
        assertThrows(IllegalStateException.class,
            () -> ProjectDocumentInstall.commit(ticket, () -> true, () -> { }));
        assertNull(ticket.outcome());
    }
}
