package shed;

import java.lang.management.ManagementFactory;
import java.util.ArrayList;
import java.util.List;

/** Opt-in startup phase timings, printed only after the first window is visible. */
final class StartupProfile {
    private final boolean enabled = Boolean.getBoolean("shed.startup.profile");
    private final long startedAt = System.nanoTime();
    private long previous = startedAt;
    private final List<String> phases = new ArrayList<>();

    void mark(String name) {
        if (!enabled) return;
        long now = System.nanoTime();
        phases.add(String.format("  %-25s %7.1f ms", name, (now - previous) / 1_000_000.0));
        previous = now;
    }

    void printAfterVisible() {
        if (!enabled) return;
        mark("setVisible");
        long visibleAtEpochMillis = System.currentTimeMillis();
        double constructorMillis = (System.nanoTime() - startedAt) / 1_000_000.0;
        System.err.println("Shed startup (prebuilt JAR; Swing window visible)");
        for (String phase : phases) System.err.println(phase);
        System.err.printf("  %-25s %7.1f ms%n", "constructor total", constructorMillis);
        System.err.printf("  %-25s %7d ms%n", "JVM launch to visible",
            visibleAtEpochMillis - ManagementFactory.getRuntimeMXBean().getStartTime());
    }

    void printFullUiReady() {
        if (!enabled) return;
        System.err.printf("  %-25s %7d ms%n", "JVM launch to full UI",
            System.currentTimeMillis() - ManagementFactory.getRuntimeMXBean().getStartTime());
    }
}
