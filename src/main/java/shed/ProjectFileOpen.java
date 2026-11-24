package shed;

import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Predicate;
import javax.swing.SwingUtilities;

/** Internal asynchronous preparation/commit bridge; no external command or grant. */
final class ProjectFileOpen {
    static final class Request {
        private final ProjectDocumentInstall.Ticket ticket = new ProjectDocumentInstall.Ticket();
        private AsyncJobService jobs;
        private int jobId;

        private Request(AsyncJobService jobs) { this.jobs = jobs; }

        synchronized boolean cancel() {
            if (!ticket.cancel()) return false;
            if (jobs != null) jobs.cancel(jobId);
            return true;
        }

        ProjectDocumentInstall.Outcome outcome() { return ticket.outcome(); }
        private synchronized void retired() { jobs = null; }
    }

    private ProjectFileOpen() { }

    /** Predicates/installation run on the EDT; preparation only runs on the existing workers. */
    static Request start(AsyncJobService jobs, LegacyDocumentStore store, DocumentRequests<EditorPane> requests,
                         EditorPane target, ProjectBoundFileAcquisition.RootPin root, String relativeName,
                         Predicate<LegacyDocumentStore.PreparedProjectFile> currentAndAuthorized,
                         Consumer<LegacyDocumentStore.PreparedProjectFile> install,
                         Consumer<ProjectDocumentInstall.Result> completion) {
        if (!SwingUtilities.isEventDispatchThread()) throw new IllegalStateException("File-open dispatch belongs to the EDT");
        Objects.requireNonNull(jobs); Objects.requireNonNull(store); Objects.requireNonNull(requests);
        Objects.requireNonNull(target); Objects.requireNonNull(root);
        Objects.requireNonNull(currentAndAuthorized); Objects.requireNonNull(install); Objects.requireNonNull(completion);
        long generation = requests.issue(target);
        Request request = new Request(jobs);
        request.jobId = jobs.submit("prepare project file", token -> {
            token.onCancel(request.ticket::cancel);
            return store.prepareProjectFile(root, relativeName, token::isCancelled);
        }, (job, prepared, error) -> {
            try {
                if (job.getStatus() == AsyncJobService.Status.CANCELLED) request.ticket.cancel();
                var result = ProjectDocumentInstall.commit(request.ticket,
                    () -> error == null && prepared != null && !target.isDisposed()
                        && requests.current(target, generation) && currentAndAuthorized.test(prepared),
                    () -> install.accept(prepared));
                if (error != null && result.outcome() == ProjectDocumentInstall.Outcome.REJECTED)
                    result = new ProjectDocumentInstall.Result(result.outcome(),
                        new IllegalStateException("Project file preparation failed", error));
                completion.accept(result);
            } finally {
                request.retired();
            }
        });
        return request;
    }
}
