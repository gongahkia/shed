package shed;

import java.io.PrintStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Explicit local measurement for Shed's editable-file text model and deferred
 * visual work. It is intentionally not part of the normal Maven test run.
 */
public final class EditorResponsivenessBenchmark {
    static final int DEFAULT_SAMPLES = 25;
    static final int TARGET_LINES = 500_000;
    static final long DEFAULT_MAX_EDIT_P95_MS = 16L;
    static final long DEFAULT_MAX_CONVERGENCE_P95_MS = 250L;

    private EditorResponsivenessBenchmark() { }

    public static void main(String[] args) {
        System.exit(run(args, System.out, System.err));
    }

    static int run(String[] args, PrintStream output, PrintStream error) {
        Arguments arguments;
        try {
            arguments = Arguments.parse(args);
        } catch (IllegalArgumentException invalid) {
            error.println(invalid.getMessage());
            error.println(Arguments.usage());
            return 2;
        }
        if (arguments.help()) {
            output.println(Arguments.usage());
            return 0;
        }
        Report report = measure(arguments);
        output.print(report.format());
        return report.passes(arguments) ? 0 : 1;
    }

    static Report measure(Arguments arguments) {
        String fixture = fixture();
        VersionedTextSnapshot baseline = VersionedTextSnapshot.of(fixture);
        warm(baseline);
        List<Long> editSamples = new ArrayList<>(arguments.samples());
        List<Long> caretSamples = new ArrayList<>(arguments.samples());
        List<Long> convergenceSamples = new ArrayList<>(arguments.samples());
        for (int sample = 0; sample < arguments.samples(); sample++) {
            int offset = baseline.lineStartOffset((sample * 19_973) % TARGET_LINES);
            editSamples.add(measureNanos(() -> baseline.replace(offset, 0, "x")));
            caretSamples.add(measureNanos(() -> {
                VersionedTextSnapshot.Position position = baseline.positionAt(offset);
                baseline.offsetAt(position.line(), position.character());
            }));
            convergenceSamples.add(measureNanos(() -> converge(baseline)));
        }
        return new Report(baseline.length(), baseline.lineCount(), percentile(editSamples), percentile(caretSamples),
            percentile(convergenceSamples), arguments.samples());
    }

    private static void warm(VersionedTextSnapshot baseline) {
        baseline.positionAt(baseline.length() / 2);
        converge(baseline);
    }

    private static void converge(VersionedTextSnapshot snapshot) {
        String text = snapshot.text();
        new GrammarHighlightService().highlightSnapshot(text, FileType.JAVA);
        new SymbolService().collectSymbols(text, FileType.JAVA);
        new OpenBufferCompletionIndex().build(text);
        LineNumberPanel.diffMarkers(text, snapshot.replace(0, 0, "// ").text());
    }

    private static long measureNanos(Runnable operation) {
        long started = System.nanoTime();
        operation.run();
        return System.nanoTime() - started;
    }

    private static long percentile(List<Long> samples) {
        List<Long> sorted = new ArrayList<>(samples);
        Collections.sort(sorted);
        return sorted.get(Math.max(0, (int) Math.ceil(sorted.size() * 0.95d) - 1));
    }

    static String fixture() {
        StringBuilder source = new StringBuilder(18 * 1024 * 1024);
        for (int line = 0; line < TARGET_LINES; line++) {
            if (line == 0) source.append("public final class Sample {\n");
            else if (line % 8_000 == 1) source.append("  /* multiline comment carrying #a1b2c3 */\n");
            else if (line % 8_000 == 2) source.append("  private final String name = \"sample value\";\n");
            else if (line % 8_000 == 3) source.append("  void method").append(line).append("(int count) {\n");
            else if (line % 8_000 == 4) source.append("    if ((count > 0) && (name != null)) {\n");
            else if (line % 8_000 == 5) source.append("      System.out.println(name + count);\n");
            else if (line % 8_000 == 6) source.append("    }\n");
            else source.append("  int value = ").append(line).append(";\n");
        }
        source.setLength(source.length() - 1);
        return source.toString();
    }

    record Report(int characters, int lines, long editP95Nanos, long caretP95Nanos, long convergenceP95Nanos, int samples) {
        boolean passes(Arguments arguments) {
            return editP95Nanos <= arguments.maxEditP95Ms() * 1_000_000L
                && caretP95Nanos <= arguments.maxEditP95Ms() * 1_000_000L
                && convergenceP95Nanos <= arguments.maxConvergenceP95Ms() * 1_000_000L;
        }

        String format() {
            return "workload.lines=" + lines + '\n'
                + "workload.characters=" + characters + '\n'
                + "workload.samples=" + samples + '\n'
                + "p95.edit.ms=" + millis(editP95Nanos) + '\n'
                + "p95.caret.ms=" + millis(caretP95Nanos) + '\n'
                + "p95.convergence.ms=" + millis(convergenceP95Nanos) + '\n';
        }

        private static String millis(long nanos) {
            return String.format(java.util.Locale.ROOT, "%.3f", nanos / 1_000_000d);
        }
    }

    record Arguments(int samples, long maxEditP95Ms, long maxConvergenceP95Ms, boolean help) {
        static Arguments parse(String[] values) {
            int samples = DEFAULT_SAMPLES;
            long edit = DEFAULT_MAX_EDIT_P95_MS;
            long convergence = DEFAULT_MAX_CONVERGENCE_P95_MS;
            boolean help = false;
            for (String value : values == null ? new String[0] : values) {
                if ("--help".equals(value)) help = true;
                else if (value.startsWith("--samples=")) samples = positive(value, "--samples=");
                else if (value.startsWith("--max-edit-p95-ms=")) edit = positive(value, "--max-edit-p95-ms=");
                else if (value.startsWith("--max-convergence-p95-ms=")) convergence = positive(value, "--max-convergence-p95-ms=");
                else throw new IllegalArgumentException("Unknown argument: " + value);
            }
            if (samples < 1 || samples > DEFAULT_SAMPLES) throw new IllegalArgumentException("--samples must be between 1 and 25");
            return new Arguments(samples, edit, convergence, help);
        }

        private static int positive(String value, String prefix) {
            try {
                int parsed = Integer.parseInt(value.substring(prefix.length()));
                if (parsed <= 0) throw new NumberFormatException();
                return parsed;
            } catch (NumberFormatException invalid) {
                throw new IllegalArgumentException(prefix + " must be a positive integer");
            }
        }

        static String usage() {
            return "Usage: shed.EditorResponsivenessBenchmark [--samples=1..25] [--max-edit-p95-ms=N] [--max-convergence-p95-ms=N]";
        }
    }
}
