package shed;

import java.awt.AWTEvent;
import java.awt.EventQueue;
import java.awt.GraphicsEnvironment;
import java.awt.Toolkit;
import java.nio.file.Path;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import javax.swing.JOptionPane;
import javax.swing.SwingUtilities;

public final class ApplicationErrorReporter implements Thread.UncaughtExceptionHandler {
    private final DiagnosticLog diagnosticLog;
    private final Consumer<String> notifier;
    private final AtomicBoolean notificationShown;
    private final AtomicBoolean installed;

    public ApplicationErrorReporter() {
        this(defaultLogPath(), ApplicationErrorReporter::showErrorDialog);
    }

    ApplicationErrorReporter(Path logPath, Consumer<String> notifier) {
        this(new DiagnosticLog(logPath), notifier);
    }

    ApplicationErrorReporter(DiagnosticLog diagnosticLog, Consumer<String> notifier) {
        this.diagnosticLog = Objects.requireNonNull(diagnosticLog, "diagnosticLog");
        this.notifier = Objects.requireNonNull(notifier, "notifier");
        this.notificationShown = new AtomicBoolean(false);
        this.installed = new AtomicBoolean(false);
    }

    public Path getLogPath() {
        return diagnosticLog.getPath();
    }

    public void install() {
        if (!installed.compareAndSet(false, true)) {
            return;
        }
        Thread.setDefaultUncaughtExceptionHandler(this);
        try {
            Toolkit.getDefaultToolkit().getSystemEventQueue().push(new GuardedEventQueue(this));
        } catch (RuntimeException error) {
            report(error, "ui", "installing the UI error boundary", "docs/THREADING.md#edt-ownership");
        }
    }

    public void report(Throwable error, String context) {
        report(error, "application", context, "docs/DIAGNOSTICS.md");
    }

    public void report(Throwable error, String subsystem, String context, String remediationReference) {
        if (error == null) {
            return;
        }
        boolean logged = diagnosticLog.record(
            DiagnosticLog.Severity.ERROR,
            subsystem,
            context,
            error,
            remediationReference
        );
        if (!notificationShown.compareAndSet(false, true)) {
            return;
        }
        try {
            notifier.accept(logged
                ? "Shed encountered an unexpected error and remains open if possible. Details were recorded locally at " + getLogPath() + "."
                : "Shed encountered an unexpected error and remains open if possible. Shed could not write its local log at " + getLogPath() + ".");
        } catch (RuntimeException ignored) {
        }
    }

    @Override
    public void uncaughtException(Thread thread, Throwable error) {
        String threadName = thread == null ? "unknown thread" : thread.getName();
        report(error, "uncaught", "uncaught failure on " + threadName, "docs/DIAGNOSTICS.md");
    }

    private static Path defaultLogPath() {
        String home = System.getProperty("user.home", ".");
        return Path.of(home, ".shed", "shed-diagnostics.jsonl");
    }

    private static void showErrorDialog(String message) {
        if (GraphicsEnvironment.isHeadless()) {
            return;
        }
        SwingUtilities.invokeLater(() -> {
            try {
                JOptionPane.showMessageDialog(null, message, "Shed error", JOptionPane.ERROR_MESSAGE);
            } catch (RuntimeException ignored) {
            }
        });
    }

    static final class GuardedEventQueue extends EventQueue {
        private final ApplicationErrorReporter reporter;

        GuardedEventQueue(ApplicationErrorReporter reporter) {
            this.reporter = reporter;
        }

        @Override
        protected void dispatchEvent(AWTEvent event) {
            try {
                super.dispatchEvent(event);
            } catch (Throwable error) {
                reporter.report(error, "ui", "processing a UI event", "docs/THREADING.md#edt-ownership");
                if (isFatal(error)) {
                    throw (Error) error;
                }
            }
        }

        private boolean isFatal(Throwable error) {
            return error instanceof VirtualMachineError || error instanceof ThreadDeath || error instanceof LinkageError;
        }
    }
}
