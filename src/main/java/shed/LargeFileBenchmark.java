package shed;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public final class LargeFileBenchmark {
    private static final int DEFAULT_ITERATIONS = 3;
    private static final int MAX_ITERATIONS = 25;
    private static final int SCROLL_VISIBLE_LINES = 40;
    private static final List<String> OPERATION_NAMES = List.of("coldOpen", "warmOpen", "scroll", "edit", "save");

    private final int iterations;
    private final long maxP95Nanos;

    LargeFileBenchmark(int iterations) {
        this(iterations, -1L);
    }

    LargeFileBenchmark(int iterations, long maxP95Nanos) {
        if (iterations < 1 || iterations > MAX_ITERATIONS) {
            throw new IllegalArgumentException("iterations must be between 1 and " + MAX_ITERATIONS);
        }
        if (maxP95Nanos == 0L || maxP95Nanos < -1L) {
            throw new IllegalArgumentException("max p95 nanos must be positive or disabled");
        }
        this.iterations = iterations;
        this.maxP95Nanos = maxP95Nanos;
    }

    public static void main(String[] args) {
        System.exit(run(args, System.out, System.err));
    }

    static int run(String[] args, PrintStream output, PrintStream error) {
        Arguments arguments;
        try {
            arguments = Arguments.parse(args);
        } catch (IllegalArgumentException exception) {
            error.println(exception.getMessage());
            error.println(Arguments.usage());
            return ResultState.ERROR.exitCode();
        }
        if (arguments.help()) {
            output.println(Arguments.usage());
            return ResultState.PASS.exitCode();
        }
        LargeFileBenchmark benchmark = new LargeFileBenchmark(arguments.iterations(), arguments.maxP95Nanos());
        int exitCode = ResultState.PASS.exitCode();
        for (Path file : arguments.files()) {
            Report report = benchmark.measure(file);
            output.print(report.format());
            exitCode = Math.max(exitCode, report.state().exitCode());
        }
        return exitCode;
    }

    Report measure(Path input) {
        Path source = Objects.requireNonNull(input, "input").toAbsolutePath().normalize();
        Map<String, List<Sample>> samples = new LinkedHashMap<>();
        for (String operation : OPERATION_NAMES) {
            samples.put(operation, new ArrayList<>());
        }
        Map<String, String> unavailable = new LinkedHashMap<>();
        List<Failure> failures = new ArrayList<>();
        long inputBytes = inputBytes(source, failures);
        if (inputBytes >= 0L) {
            for (int iteration = 1; iteration <= iterations; iteration++) {
                measureIteration(source, iteration, samples, unavailable, failures);
            }
        }
        Map<String, OperationStats> operations = new LinkedHashMap<>();
        for (Map.Entry<String, List<Sample>> entry : samples.entrySet()) {
            String operation = entry.getKey();
            boolean failed = failures.stream().anyMatch(failure -> failure.operation().equals(operation));
            operations.put(operation, OperationStats.from(entry.getValue(), iterations, unavailable.get(operation), failed));
        }
        return new Report(Environment.current(), source, Math.max(0L, inputBytes), iterations, maxP95Nanos, operations, failures);
    }

    private static long inputBytes(Path source, List<Failure> failures) {
        try {
            if (!Files.isRegularFile(source)) {
                failures.add(new Failure(0, "input", "NotRegularFile", "input is not a regular file"));
                return -1L;
            }
            return Files.size(source);
        } catch (IOException | SecurityException error) {
            failures.add(Failure.from(0, "input", error));
            return -1L;
        }
    }

    private static void measureIteration(Path source, int iteration, Map<String, List<Sample>> samples,
                                         Map<String, String> unavailable, List<Failure> failures) {
        Path copy = null;
        try {
            copy = Files.createTempFile("shed-large-file-benchmark-", suffix(source));
            Files.copy(source, copy, StandardCopyOption.REPLACE_EXISTING);
            Path target = copy;
            FileBuffer cold = measure("coldOpen", iteration, samples, failures, () -> new FileBuffer(target.toFile()));
            if (cold == null) {
                markUnavailable(unavailable, "warmOpen", "cold open did not complete");
                markUnavailable(unavailable, "scroll", "cold open did not complete");
                markUnavailable(unavailable, "edit", "cold open did not complete");
                markUnavailable(unavailable, "save", "cold open did not complete");
                return;
            }
            if (cold.isLargeFileUnavailable()) {
                failures.add(new Failure(iteration, "coldOpen", "LargeFileUnavailable", cold.getLargeFileStatus()));
                markUnavailable(unavailable, "warmOpen", "cold open selected an unavailable large-file source");
                markUnavailable(unavailable, "scroll", "cold open selected an unavailable large-file source");
                markUnavailable(unavailable, "edit", "cold open selected an unavailable large-file source");
                markUnavailable(unavailable, "save", "cold open selected an unavailable large-file source");
                return;
            }
            FileBuffer warm = measure("warmOpen", iteration, samples, failures, () -> new FileBuffer(target.toFile()));
            if (warm == null) {
                markUnavailable(unavailable, "scroll", "warm open did not complete");
                markUnavailable(unavailable, "edit", "warm open did not complete");
                markUnavailable(unavailable, "save", "warm open did not complete");
                return;
            }
            if (warm.isLargeFileUnavailable()) {
                failures.add(new Failure(iteration, "warmOpen", "LargeFileUnavailable", warm.getLargeFileStatus()));
                markUnavailable(unavailable, "scroll", "warm open selected an unavailable large-file source");
                markUnavailable(unavailable, "edit", "warm open selected an unavailable large-file source");
                markUnavailable(unavailable, "save", "warm open selected an unavailable large-file source");
                return;
            }
            measureScroll(warm, iteration, samples, unavailable, failures);
            measureEdit(warm, iteration, samples, unavailable, failures);
            measure("save", iteration, samples, failures, () -> {
                warm.save();
                return true;
            });
        } catch (IOException | SecurityException error) {
            failures.add(Failure.from(iteration, "prepare", error));
        } finally {
            if (copy != null) {
                try {
                    Files.deleteIfExists(copy);
                } catch (IOException error) {
                    failures.add(Failure.from(iteration, "cleanup", error));
                }
            }
        }
    }

    private static void measureScroll(FileBuffer buffer, int iteration, Map<String, List<Sample>> samples,
                                      Map<String, String> unavailable, List<Failure> failures) {
        if (!buffer.isLargeFile()) {
            markUnavailable(unavailable, "scroll", "input did not select large-file projection");
            return;
        }
        try {
            LargeFileProjection projection = new LargeFileProjection(buffer);
            projection.render(SCROLL_VISIBLE_LINES);
            Boolean moved = measure("scroll", iteration, samples, failures,
                () -> projection.moveForward(SCROLL_VISIBLE_LINES));
            if (Boolean.FALSE.equals(moved)) {
                failures.add(new Failure(iteration, "scroll", "NoScrollableWindow", "large-file projection could not move forward"));
            }
        } catch (IOException | IllegalArgumentException error) {
            failures.add(Failure.from(iteration, "scroll", error));
        }
    }

    private static void measureEdit(FileBuffer buffer, int iteration, Map<String, List<Sample>> samples,
                                    Map<String, String> unavailable, List<Failure> failures) {
        if (buffer.isLargeFile()) {
            markUnavailable(unavailable, "edit", "large-file bounded editing is unavailable");
            return;
        }
        measure("edit", iteration, samples, failures, () -> {
            buffer.setContent(buffer.getContent() + " ");
            return true;
        });
    }

    private static void markUnavailable(Map<String, String> unavailable, String operation, String reason) {
        unavailable.putIfAbsent(operation, reason);
    }

    private static String suffix(Path source) {
        Path name = source.getFileName();
        String value = name == null ? "" : name.toString();
        int extension = value.lastIndexOf('.');
        return extension >= 0 ? value.substring(extension) : ".tmp";
    }

    private static <T> T measure(String operation, int iteration, Map<String, List<Sample>> samples, List<Failure> failures,
                                 ThrowingSupplier<T> supplier) {
        long heapBefore = usedHeapBytes();
        long startedAtNanos = System.nanoTime();
        try {
            T result = supplier.get();
            samples.get(operation).add(new Sample(Math.max(0L, System.nanoTime() - startedAtNanos), usedHeapBytes() - heapBefore));
            return result;
        } catch (Exception | OutOfMemoryError error) {
            failures.add(Failure.from(iteration, operation, error));
            return null;
        }
    }

    private static long usedHeapBytes() {
        Runtime runtime = Runtime.getRuntime();
        return runtime.totalMemory() - runtime.freeMemory();
    }

    record Report(Environment environment, Path input, long inputBytes, int iterations, long maxP95Nanos,
                  Map<String, OperationStats> operations, List<Failure> failures) {
        Report {
            environment = Objects.requireNonNull(environment, "environment");
            input = Objects.requireNonNull(input, "input");
            if (inputBytes < 0L || iterations < 1 || maxP95Nanos == 0L || maxP95Nanos < -1L) {
                throw new IllegalArgumentException("invalid benchmark report");
            }
            operations = Collections.unmodifiableMap(new LinkedHashMap<>(operations));
            failures = List.copyOf(failures);
        }

        ResultState state() {
            if (!failures.isEmpty() || operations.values().stream().anyMatch(stats -> stats.state() == OperationState.ERROR)) {
                return ResultState.ERROR;
            }
            if (maxP95Nanos > 0L && operations.values().stream()
                .filter(stats -> stats.state() == OperationState.PASS)
                .anyMatch(stats -> stats.p95Nanos() > maxP95Nanos)) {
                return ResultState.FAIL;
            }
            return ResultState.PASS;
        }

        String format() {
            StringBuilder output = new StringBuilder();
            output.append("report.version=2\n");
            output.append("result.state=").append(state()).append('\n');
            output.append("environment.javaVersion=").append(environment.javaVersion()).append('\n');
            output.append("environment.javaVendor=").append(environment.javaVendor()).append('\n');
            output.append("environment.vmName=").append(environment.vmName()).append('\n');
            output.append("environment.osName=").append(environment.osName()).append('\n');
            output.append("environment.osVersion=").append(environment.osVersion()).append('\n');
            output.append("environment.osArch=").append(environment.osArch()).append('\n');
            output.append("environment.availableProcessors=").append(environment.availableProcessors()).append('\n');
            output.append("environment.maxHeapBytes=").append(environment.maxHeapBytes()).append('\n');
            output.append("workload.input=").append(input).append('\n');
            output.append("workload.inputBytes=").append(inputBytes).append('\n');
            output.append("workload.iterations=").append(iterations).append('\n');
            output.append("workload.maxP95Nanos=").append(maxP95Nanos).append('\n');
            output.append("workload.operations=").append(String.join(",", operations.keySet())).append('\n');
            output.append("reproduction.command=java -cp target/shed-2.0.0.jar shed.LargeFileBenchmark --iterations ")
                .append(iterations);
            if (maxP95Nanos > 0L) {
                output.append(" --max-p95-ms ").append(maxP95Nanos / 1_000_000L);
            }
            output.append(' ').append(input).append('\n');
            for (Map.Entry<String, OperationStats> entry : operations.entrySet()) {
                String name = entry.getKey();
                OperationStats stats = entry.getValue();
                output.append(name).append(".state=").append(stats.state()).append('\n');
                output.append(name).append(".reason=").append(stats.reason()).append('\n');
                output.append(name).append(".samples=").append(stats.samples()).append('\n');
                output.append(name).append(".medianNanos=").append(stats.medianNanos()).append('\n');
                output.append(name).append(".p95Nanos=").append(stats.p95Nanos()).append('\n');
                output.append(name).append(".medianHeapDeltaBytes=").append(stats.medianHeapDeltaBytes()).append('\n');
                output.append(name).append(".p95HeapDeltaBytes=").append(stats.p95HeapDeltaBytes()).append('\n');
            }
            output.append("failures=").append(failures.size()).append('\n');
            for (int index = 0; index < failures.size(); index++) {
                output.append("failure.").append(index + 1).append('=').append(failures.get(index).format()).append('\n');
            }
            output.append('\n');
            return output.toString();
        }
    }

    record Environment(String javaVersion, String javaVendor, String vmName, String osName, String osVersion, String osArch,
                       int availableProcessors, long maxHeapBytes) {
        static Environment current() {
            Runtime runtime = Runtime.getRuntime();
            return new Environment(property("java.version"), property("java.vendor"), property("java.vm.name"), property("os.name"),
                property("os.version"), property("os.arch"), runtime.availableProcessors(), runtime.maxMemory());
        }

        private static String property(String name) {
            return System.getProperty(name, "unknown").replace('\n', ' ').replace('\r', ' ');
        }
    }

    enum ResultState {
        PASS(0),
        FAIL(1),
        ERROR(2);

        private final int exitCode;

        ResultState(int exitCode) {
            this.exitCode = exitCode;
        }

        int exitCode() {
            return exitCode;
        }
    }

    enum OperationState {
        PASS,
        UNSUPPORTED,
        ERROR
    }

    record OperationStats(OperationState state, String reason, int samples, long medianNanos, long p95Nanos,
                          long medianHeapDeltaBytes, long p95HeapDeltaBytes) {
        static OperationStats from(List<Sample> samples, int iterations, String unavailableReason, boolean failed) {
            if (unavailableReason != null) {
                return new OperationStats(OperationState.UNSUPPORTED, unavailableReason, 0, -1L, -1L, -1L, -1L);
            }
            if (failed || samples.size() != iterations) {
                return new OperationStats(OperationState.ERROR, "operation did not complete every iteration", samples.size(),
                    percentile(samples, Sample::durationNanos), percentile(samples, Sample::durationNanos, 0.95),
                    percentile(samples, Sample::heapDeltaBytes), percentile(samples, Sample::heapDeltaBytes, 0.95));
            }
            return new OperationStats(OperationState.PASS, "", samples.size(), percentile(samples, Sample::durationNanos),
                percentile(samples, Sample::durationNanos, 0.95), percentile(samples, Sample::heapDeltaBytes),
                percentile(samples, Sample::heapDeltaBytes, 0.95));
        }

        private static long percentile(List<Sample> samples, SampleValue value) {
            return percentile(samples, value, 0.50);
        }

        private static long percentile(List<Sample> samples, SampleValue value, double percentile) {
            if (samples.isEmpty()) {
                return -1L;
            }
            List<Long> sorted = samples.stream().map(value::value).sorted().toList();
            int index = Math.min(sorted.size() - 1, Math.max(0, (int) Math.ceil(percentile * sorted.size()) - 1));
            return sorted.get(index);
        }
    }

    record Failure(int iteration, String operation, String type, String message) {
        static Failure from(int iteration, String operation, Throwable error) {
            String message = error.getMessage();
            return new Failure(iteration, operation, error.getClass().getSimpleName(),
                message == null || message.isBlank() ? "no message" : message.replace('\n', ' ').replace('\r', ' '));
        }

        String format() {
            return "iteration=" + iteration + ",operation=" + operation + ",type=" + type + ",message=" + message;
        }
    }

    private record Sample(long durationNanos, long heapDeltaBytes) {
    }

    @FunctionalInterface
    private interface SampleValue {
        long value(Sample sample);
    }

    @FunctionalInterface
    private interface ThrowingSupplier<T> {
        T get() throws Exception;
    }

    private record Arguments(int iterations, long maxP95Nanos, List<Path> files, boolean help) {
        static Arguments parse(String[] args) {
            int iterations = DEFAULT_ITERATIONS;
            long maxP95Nanos = -1L;
            List<Path> files = new ArrayList<>();
            for (int index = 0; index < args.length; index++) {
                String argument = args[index];
                if ("--help".equals(argument) || "-h".equals(argument)) {
                    return new Arguments(iterations, maxP95Nanos, List.of(), true);
                }
                if ("--iterations".equals(argument)) {
                    if (++index >= args.length) {
                        throw new IllegalArgumentException("--iterations requires a value");
                    }
                    try {
                        iterations = Integer.parseInt(args[index]);
                    } catch (NumberFormatException error) {
                        throw new IllegalArgumentException("--iterations must be an integer");
                    }
                    continue;
                }
                if ("--max-p95-ms".equals(argument)) {
                    if (++index >= args.length) {
                        throw new IllegalArgumentException("--max-p95-ms requires a value");
                    }
                    try {
                        long milliseconds = Long.parseLong(args[index]);
                        if (milliseconds < 1L) {
                            throw new NumberFormatException();
                        }
                        maxP95Nanos = Math.multiplyExact(milliseconds, 1_000_000L);
                    } catch (NumberFormatException | ArithmeticException error) {
                        throw new IllegalArgumentException("--max-p95-ms must be a positive whole number");
                    }
                    continue;
                }
                files.add(Path.of(argument));
            }
            if (files.isEmpty()) {
                throw new IllegalArgumentException("at least one input file is required");
            }
            new LargeFileBenchmark(iterations, maxP95Nanos);
            return new Arguments(iterations, maxP95Nanos, List.copyOf(files), false);
        }

        static String usage() {
            return "Usage: java -cp target/shed-2.0.0.jar shed.LargeFileBenchmark [--iterations 1..25] [--max-p95-ms <positive>] <file>...";
        }
    }
}
