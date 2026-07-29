package shed;

import java.awt.AWTEvent;
import java.awt.EventQueue;
import java.awt.GraphicsEnvironment;
import java.awt.Toolkit;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import javax.swing.JOptionPane;
import javax.swing.SwingUtilities;

public final class ApplicationErrorReporter implements Thread.UncaughtExceptionHandler {
    private final Path logPath;
    private final Consumer<String> notifier;
    private final AtomicBoolean notificationShown;
    private final AtomicBoolean installed;

    public ApplicationErrorReporter() {
        this(defaultLogPath(), ApplicationErrorReporter::showErrorDialog);
    }

    ApplicationErrorReporter(Path logPath, Consumer<String> notifier) {
        this.logPath = Objects.requireNonNull(logPath, "logPath");
        this.notifier = Objects.requireNonNull(notifier, "notifier");
        this.notificationShown = new AtomicBoolean(false);
        this.installed = new AtomicBoolean(false);
    }

    public Path getLogPath() {
        return logPath;
    }

    public void install() {
        if (!installed.compareAndSet(false, true)) {
            return;
        }
        Thread.setDefaultUncaughtExceptionHandler(this);
        try {
            Toolkit.getDefaultToolkit().getSystemEventQueue().push(new GuardedEventQueue(this));
        } catch (RuntimeException error) {
            report(error, "installing the UI error boundary");
        }
    }

    public void report(Throwable error, String context) {
        if (error == null) {
            return;
        }
        boolean logged = appendToLog(error, context);
        if (!notificationShown.compareAndSet(false, true)) {
            return;
        }
        try {
            notifier.accept(logged
                ? "Shed encountered an unexpected error and remains open if possible. Details were recorded locally at " + logPath + "."
                : "Shed encountered an unexpected error and remains open if possible. Shed could not write its local log at " + logPath + ".");
        } catch (RuntimeException ignored) {
        }
    }

    @Override
    public void uncaughtException(Thread thread, Throwable error) {
        String threadName = thread == null ? "unknown thread" : thread.getName();
        report(error, "uncaught failure on " + threadName);
    }

    private boolean appendToLog(Throwable error, String context) {
        try {
            Path parent = logPath.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            StringWriter stack = new StringWriter();
            error.printStackTrace(new PrintWriter(stack));
            String entry = "timestamp=" + Instant.now()
                + " context=" + safe(context)
                + " thread=" + Thread.currentThread().getName()
                + " error=" + error.getClass().getName()
                + " message=" + safe(error.getMessage())
                + System.lineSeparator()
                + stack
                + System.lineSeparator();
            Files.writeString(logPath, entry, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND, StandardOpenOption.WRITE);
            return true;
        } catch (Exception ignored) {
            return false;
        }
    }

    private static Path defaultLogPath() {
        String home = System.getProperty("user.home", ".");
        return Path.of(home, ".shed", "shed-errors.log");
    }

    private static String safe(String value) {
        return value == null ? "" : value.replace('\r', ' ').replace('\n', ' ');
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
                reporter.report(error, "processing a UI event");
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
