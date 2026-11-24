package shed;

import java.util.Objects;
import java.util.function.BooleanSupplier;
import javax.swing.SwingUtilities;

/**
 * Typed pre-commit gate for a prepared project document. This does not acquire files,
 * grant agent permissions, schedule jobs or expose a remote file-open operation.
 *
 * The caller must supply a prepared, project-bound FileBuffer and check the *current*
 * instance/project/pane/document/interaction state on the EDT before installation.
 * Cancellation is effective until the commit begins; once it begins the result may
 * be APPLIED or OUTCOME_UNKNOWN, never an asserted cancellation.
 */
final class ProjectDocumentInstall {
    enum Outcome { APPLIED, REJECTED, CANCELLED, OUTCOME_UNKNOWN }
    record Result(Outcome outcome, RuntimeException failure) {
        Result { Objects.requireNonNull(outcome, "outcome"); }
        Result(Outcome outcome) { this(outcome, null); }
    }

    /** No pane, source or session is retained by the ticket. */
    static final class Ticket {
        private Outcome finished;
        private boolean committing;

        /** Returns false if the operation has already begun committing. */
        synchronized boolean cancel() {
            if (committing || finished != null) return false;
            finished = Outcome.CANCELLED;
            return true;
        }

        synchronized Outcome outcome() { return finished; }

        private synchronized boolean begin() {
            if (committing || finished != null) return false;
            committing = true;
            return true;
        }

        private synchronized Result finish(Outcome outcome, RuntimeException failure) {
            if (!committing || finished != null) throw new IllegalStateException("Installation already completed");
            finished = outcome;
            committing = false;
            return new Result(outcome, failure);
        }

        private synchronized Result reject() {
            if (finished == null && !committing) finished = Outcome.REJECTED;
            return new Result(finished == null ? Outcome.OUTCOME_UNKNOWN : finished);
        }
    }

    private ProjectDocumentInstall() { }

    /** Shared source/context checks for the workbench and real component fixtures. */
    static boolean matches(LegacyDocumentStore.PreparedProjectFile prepared, EditorPane target,
                           EditorNavigation.State expected, EditorNavigation.State current) {
        if (prepared == null || target == null || expected == null || target.isDisposed()
                || target.isHiddenByFocusMode() || target.getBuffer() == null || target.getBuffer().isLargeFile()
                || target.getTerminalPane() != null
                || target.getAlternateComponent() != null || target.isMarkdownPreview()) return false;
        FileBuffer loaded = prepared.buffer();
        return loaded.hasFilePath() && !loaded.isLargeFile()
            && Objects.equals(expected.metadata().activeRoot(), prepared.root().path())
            && loaded.getFile().toPath().toAbsolutePath().normalize().startsWith(prepared.root().path())
            && expected.equals(current);
    }

    /** Execute on the EDT. The precondition must be bounded and side-effect-free. */
    static Result commit(Ticket ticket, BooleanSupplier currentAndAuthorized, Runnable install) {
        if (!SwingUtilities.isEventDispatchThread())
            throw new IllegalStateException("Document installation belongs to the EDT");
        Objects.requireNonNull(ticket, "ticket");
        Objects.requireNonNull(currentAndAuthorized, "currentAndAuthorized");
        Objects.requireNonNull(install, "install");
        if (ticket.outcome() != null) return new Result(ticket.outcome());
        final boolean allowed;
        try {
            allowed = currentAndAuthorized.getAsBoolean();
        } catch (RuntimeException rejected) {
            return ticket.reject(); // No installation effects have started.
        }
        if (!allowed) return ticket.reject();
        if (!ticket.begin()) return new Result(ticket.outcome() == null ? Outcome.OUTCOME_UNKNOWN : ticket.outcome());
        try {
            install.run();
            return ticket.finish(Outcome.APPLIED, null);
        } catch (RuntimeException partial) {
            // A component may already have changed; never claim a clean refusal.
            return ticket.finish(Outcome.OUTCOME_UNKNOWN, partial);
        } catch (Error catastrophic) {
            ticket.finish(Outcome.OUTCOME_UNKNOWN, new IllegalStateException("Unrecoverable installation failure", catastrophic));
            throw catastrophic;
        }
    }
}
