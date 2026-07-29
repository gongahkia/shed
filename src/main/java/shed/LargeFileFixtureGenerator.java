package shed;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

public final class LargeFileFixtureGenerator {
    static final Path DEFAULT_OUTPUT = Path.of(".shed-large-file-fixtures");
    static final List<Fixture> DEFAULT_FIXTURES = List.of(
        new Fixture("large-1m.txt", 1L << 20),
        new Fixture("large-100m.txt", 100L << 20),
        new Fixture("large-1g.txt", 1L << 30)
    );
    private static final String MANIFEST_NAME = "fixtures.manifest";
    private static final byte[] CORPUS = "shed-large-file-fixture-v1: 0123456789 abcdefghijklmnopqrstuvwxyz ABCDEFGHIJKLMNOPQRSTUVWXYZ\n"
        .getBytes(StandardCharsets.US_ASCII);
    private static final byte[] CHUNK = corpusChunk();
    private static final Map<Fixture, String> EXPECTED_CHECKSUMS = new ConcurrentHashMap<>();

    private LargeFileFixtureGenerator() {
    }

    public static void main(String[] args) {
        System.exit(run(args, System.out, System.err));
    }

    static int run(String[] args, PrintStream output, PrintStream error) {
        try {
            Arguments arguments = Arguments.parse(args);
            if (arguments.help()) {
                output.println(Arguments.usage());
                return 0;
            }
            Report report = arguments.verify()
                ? verify(arguments.output(), DEFAULT_FIXTURES)
                : generate(arguments.output(), DEFAULT_FIXTURES);
            output.print(report.format());
            return report.verified() ? 0 : 1;
        } catch (IllegalArgumentException | IOException exception) {
            error.println(exception.getMessage());
            error.println(Arguments.usage());
            return 2;
        }
    }

    static Report generate(Path output, List<Fixture> fixtures) throws IOException {
        Path directory = outputDirectory(output);
        List<Fixture> normalizedFixtures = normalizeFixtures(fixtures);
        Files.createDirectories(directory);
        List<Result> results = new ArrayList<>();
        for (Fixture fixture : normalizedFixtures) {
            Path file = directory.resolve(fixture.name());
            String expected = expectedChecksum(fixture);
            if (!hasChecksum(file, fixture.bytes(), expected)) {
                writeFixture(directory, file, fixture, expected);
            }
            String actual = checksum(file);
            results.add(new Result(fixture, Files.size(file), actual, actual.equals(expected)));
        }
        writeManifest(directory, normalizedFixtures);
        return new Report(directory, results, List.of());
    }

    static Report verify(Path output, List<Fixture> fixtures) throws IOException {
        Path directory = outputDirectory(output);
        List<Fixture> normalizedFixtures = normalizeFixtures(fixtures);
        List<Result> results = new ArrayList<>();
        List<String> failures = new ArrayList<>();
        for (Fixture fixture : normalizedFixtures) {
            Path file = directory.resolve(fixture.name());
            if (!Files.isRegularFile(file)) {
                results.add(new Result(fixture, -1L, "missing", false));
                failures.add(fixture.name() + " is missing");
                continue;
            }
            long bytes = Files.size(file);
            String actual = checksum(file);
            boolean valid = bytes == fixture.bytes() && actual.equals(expectedChecksum(fixture));
            results.add(new Result(fixture, bytes, actual, valid));
            if (!valid) {
                failures.add(fixture.name() + " does not match its deterministic byte count and checksum");
            }
        }
        Path manifest = directory.resolve(MANIFEST_NAME);
        if (!Files.isRegularFile(manifest)) {
            failures.add(MANIFEST_NAME + " is missing");
        } else if (!Files.readString(manifest, StandardCharsets.UTF_8).equals(manifest(normalizedFixtures))) {
            failures.add(MANIFEST_NAME + " does not match the deterministic fixture manifest");
        }
        return new Report(directory, results, failures);
    }

    private static Path outputDirectory(Path output) {
        return Objects.requireNonNull(output, "output").toAbsolutePath().normalize();
    }

    private static List<Fixture> normalizeFixtures(List<Fixture> fixtures) {
        List<Fixture> normalized = List.copyOf(Objects.requireNonNull(fixtures, "fixtures"));
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException("at least one fixture is required");
        }
        if (normalized.stream().map(Fixture::name).distinct().count() != normalized.size()) {
            throw new IllegalArgumentException("fixture names must be unique");
        }
        return normalized;
    }

    private static boolean hasChecksum(Path file, long bytes, String expected) throws IOException {
        return Files.isRegularFile(file) && Files.size(file) == bytes && checksum(file).equals(expected);
    }

    private static void writeFixture(Path directory, Path target, Fixture fixture, String expected) throws IOException {
        Path temporary = Files.createTempFile(directory, ".shed-fixture-", ".tmp");
        try {
            try (FileChannel channel = FileChannel.open(temporary, java.nio.file.StandardOpenOption.WRITE)) {
                long remaining = fixture.bytes();
                while (remaining > 0) {
                    int count = (int) Math.min(CHUNK.length, remaining);
                    ByteBuffer bytes = ByteBuffer.wrap(CHUNK, 0, count);
                    while (bytes.hasRemaining()) {
                        channel.write(bytes);
                    }
                    remaining -= count;
                }
                channel.force(true);
            }
            if (!hasChecksum(temporary, fixture.bytes(), expected)) {
                throw new IOException("generated fixture checksum mismatch: " + fixture.name());
            }
            move(temporary, target);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private static void writeManifest(Path directory, List<Fixture> fixtures) throws IOException {
        Path target = directory.resolve(MANIFEST_NAME);
        Path temporary = Files.createTempFile(directory, ".shed-fixture-manifest-", ".tmp");
        try {
            Files.writeString(temporary, manifest(fixtures), StandardCharsets.UTF_8);
            move(temporary, target);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private static void move(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException exception) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static String manifest(List<Fixture> fixtures) {
        StringBuilder output = new StringBuilder("fixtures.version=1\nfixtures.algorithm=SHA-256\n");
        for (Fixture fixture : fixtures) {
            output.append(fixture.name()).append(".bytes=").append(fixture.bytes()).append('\n');
            output.append(fixture.name()).append(".sha256=").append(expectedChecksum(fixture)).append('\n');
        }
        return output.toString();
    }

    private static String expectedChecksum(Fixture fixture) {
        return EXPECTED_CHECKSUMS.computeIfAbsent(fixture, ignored -> digestCorpus(fixture.bytes()));
    }

    private static String digestCorpus(long bytes) {
        MessageDigest digest = sha256();
        long remaining = bytes;
        while (remaining > 0) {
            int count = (int) Math.min(CHUNK.length, remaining);
            digest.update(CHUNK, 0, count);
            remaining -= count;
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static String checksum(Path file) throws IOException {
        MessageDigest digest = sha256();
        try (var input = Files.newInputStream(file)) {
            byte[] buffer = new byte[64 * 1024];
            for (int read; (read = input.read(buffer)) >= 0;) {
                digest.update(buffer, 0, read);
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 unavailable", exception);
        }
    }

    private static byte[] corpusChunk() {
        byte[] chunk = new byte[64 * 1024];
        for (int index = 0; index < chunk.length; index++) {
            chunk[index] = CORPUS[index % CORPUS.length];
        }
        return chunk;
    }

    record Fixture(String name, long bytes) {
        Fixture {
            if (name == null || !name.matches("[a-z0-9][a-z0-9.-]*\\.txt") || name.contains("..")) {
                throw new IllegalArgumentException("fixture name must be a simple lowercase .txt file name");
            }
            if (bytes < 1) {
                throw new IllegalArgumentException("fixture bytes must be positive");
            }
        }
    }

    record Result(Fixture fixture, long bytes, String checksum, boolean valid) {
    }

    record Report(Path output, List<Result> results, List<String> failures) {
        Report {
            output = Objects.requireNonNull(output, "output");
            results = List.copyOf(results);
            failures = List.copyOf(failures);
        }

        boolean verified() {
            return failures.isEmpty() && results.stream().allMatch(Result::valid);
        }

        String format() {
            StringBuilder text = new StringBuilder();
            text.append("fixtures.output=").append(output).append('\n');
            text.append("fixtures.verified=").append(verified()).append('\n');
            for (Result result : results) {
                String name = result.fixture().name();
                text.append(name).append(".bytes=").append(result.bytes()).append('\n');
                text.append(name).append(".sha256=").append(result.checksum()).append('\n');
                text.append(name).append(".valid=").append(result.valid()).append('\n');
            }
            text.append("fixtures.failures=").append(failures.size()).append('\n');
            for (int index = 0; index < failures.size(); index++) {
                text.append("fixtures.failure.").append(index + 1).append('=').append(failures.get(index)).append('\n');
            }
            return text.toString();
        }
    }

    private record Arguments(Path output, boolean verify, boolean help) {
        static Arguments parse(String[] args) {
            Path output = DEFAULT_OUTPUT;
            boolean verify = false;
            for (int index = 0; index < args.length; index++) {
                String argument = args[index];
                if ("--help".equals(argument) || "-h".equals(argument)) {
                    return new Arguments(output, verify, true);
                }
                if ("--verify".equals(argument)) {
                    verify = true;
                    continue;
                }
                if ("--output".equals(argument)) {
                    if (++index >= args.length) {
                        throw new IllegalArgumentException("--output requires a directory");
                    }
                    output = Path.of(args[index]);
                    continue;
                }
                throw new IllegalArgumentException("unknown option: " + argument);
            }
            return new Arguments(output, verify, false);
        }

        static String usage() {
            return "Usage: java -cp target/shed-2.0.0.jar shed.LargeFileFixtureGenerator [--output <directory>] [--verify]";
        }
    }
}
