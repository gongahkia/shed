package shed;

import java.io.IOException;
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

    private final int iterations;

    LargeFileBenchmark(int iterations) {
        if (iterations < 1 || iterations > MAX_ITERATIONS) {
            throw new IllegalArgumentException("iterations must be between 1 and " + MAX_ITERATIONS);
        }
        this.iterations = iterations;
    }

    public static void main(String[] args) {
        Arguments arguments;
        try {
            arguments = Arguments.parse(args);
        } catch (IllegalArgumentException error) {
            System.err.println(error.getMessage());
            System.err.println(Arguments.usage());
            return;
        }
        if (arguments.help()) {
            System.out.println(Arguments.usage());
            return;
        }
        LargeFileBenchmark benchmark = new LargeFileBenchmark(arguments.iterations());
        for (Path file : arguments.files()) {
            System.out.print(benchmark.measure(file).format());
        }
    }

    Report measure(Path input) {
        Path source = Objects.requireNonNull(input, "input").toAbsolutePath().normalize();
        Map<String, List<Sample>> samples = new LinkedHashMap<>();
        samples.put("open", new ArrayList<>());
        samples.put("edit", new ArrayList<>());
        samples.put("save", new ArrayList<>());
        List<Failure> failures = new ArrayList<>();
        long inputBytes = inputBytes(source, failures);
        if (inputBytes >= 0) {
            for (int iteration = 1; iteration <= iterations; iteration++) {
                measureIteration(source, iteration, samples, failures);
            }
        }
        Map<String, OperationStats> operations = new LinkedHashMap<>();
        for (Map.Entry<String, List<Sample>> entry : samples.entrySet()) {
            operations.put(entry.getKey(), OperationStats.from(entry.getValue()));
        }
        return new Report(Environment.current(), source, Math.max(0L, inputBytes), iterations, operations, failures);
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

    private static void measureIteration(Path source, int iteration, Map<String, List<Sample>> samples, List<Failure> failures) {
        Path copy = null;
        try {
            copy = Files.createTempFile("shed-large-file-benchmark-", suffix(source));
            Files.copy(source, copy, StandardCopyOption.REPLACE_EXISTING);
            Path target = copy;
            FileBuffer buffer = measure("open", iteration, samples, failures, () -> new FileBuffer(target.toFile()));
            if (buffer == null) {
                return;
            }
            boolean edited = measure("edit", iteration, samples, failures, () -> {
                buffer.setContent(buffer.getContent() + " ");
                return true;
            });
            if (!edited) {
                return;
            }
            measure("save", iteration, samples, failures, () -> {
                buffer.save();
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

    record Report(Environment environment, Path input, long inputBytes, int iterations, Map<String, OperationStats> operations,
                  List<Failure> failures) {
        Report {
            environment = Objects.requireNonNull(environment, "environment");
            input = Objects.requireNonNull(input, "input");
            if (inputBytes < 0 || iterations < 1) {
                throw new IllegalArgumentException("invalid benchmark report");
            }
            operations = Collections.unmodifiableMap(new LinkedHashMap<>(operations));
            failures = List.copyOf(failures);
        }

        String format() {
            StringBuilder output = new StringBuilder();
            output.append("report.version=1\n");
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
            output.append("workload.operations=open,edit,save\n");
            output.append("reproduction.command=java -cp target/shed-2.0.0.jar shed.LargeFileBenchmark --iterations ")
                .append(iterations).append(' ').append(input).append('\n');
            for (Map.Entry<String, OperationStats> entry : operations.entrySet()) {
                String name = entry.getKey();
                OperationStats stats = entry.getValue();
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

    record OperationStats(int samples, long medianNanos, long p95Nanos, long medianHeapDeltaBytes, long p95HeapDeltaBytes) {
        static OperationStats from(List<Sample> samples) {
            if (samples.isEmpty()) {
                return new OperationStats(0, -1L, -1L, -1L, -1L);
            }
            List<Long> durations = samples.stream().map(Sample::durationNanos).sorted().toList();
            List<Long> heapDeltas = samples.stream().map(Sample::heapDeltaBytes).sorted().toList();
            return new OperationStats(samples.size(), percentile(durations, 0.50), percentile(durations, 0.95),
                percentile(heapDeltas, 0.50), percentile(heapDeltas, 0.95));
        }

        private static long percentile(List<Long> sorted, double percentile) {
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
    private interface ThrowingSupplier<T> {
        T get() throws Exception;
    }

    private record Arguments(int iterations, List<Path> files, boolean help) {
        static Arguments parse(String[] args) {
            int iterations = DEFAULT_ITERATIONS;
            List<Path> files = new ArrayList<>();
            for (int index = 0; index < args.length; index++) {
                String argument = args[index];
                if ("--help".equals(argument) || "-h".equals(argument)) {
                    return new Arguments(iterations, List.of(), true);
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
                files.add(Path.of(argument));
            }
            if (files.isEmpty()) {
                throw new IllegalArgumentException("at least one input file is required");
            }
            new LargeFileBenchmark(iterations);
            return new Arguments(iterations, List.copyOf(files), false);
        }

        static String usage() {
            return "Usage: java -cp target/shed-2.0.0.jar shed.LargeFileBenchmark [--iterations 1..25] <file>...";
        }
    }
}
