package shed;

import java.lang.management.ManagementFactory;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

/** Alternating, same-JVM comparison of the pure path ranker in two independent JARs. */
public final class PickerComparisonProbe {
    private record Ranker(URLClassLoader loader, Object target, java.lang.reflect.Method method) implements AutoCloseable {
        static Ranker load(Path jar) throws Exception {
            var loader = new URLClassLoader(new java.net.URL[] {jar.toUri().toURL()}, ClassLoader.getPlatformClassLoader());
            Class<?> type = loader.loadClass("shed.FuzzyMatchService");
            return new Ranker(loader, type.getConstructor().newInstance(), type.getMethod("matchPathStrings", String.class, List.class, int.class));
        }
        Object rank(List<String> paths) throws Exception { return method.invoke(target, "wid12", paths, 50); }
        public void close() throws java.io.IOException { loader.close(); }
    }
    public static void main(String[] args) throws Exception {
        if (args.length != 2) throw new IllegalArgumentException("Usage: java PickerComparisonProbe.java BEFORE.jar AFTER.jar");
        Path before = Path.of(args[0]).toRealPath(), after = Path.of(args[1]).toRealPath();
        var clock = ManagementFactory.getThreadMXBean();
        if (!clock.isCurrentThreadCpuTimeSupported()) throw new IllegalStateException("Thread CPU measurements unavailable");
        clock.setThreadCpuTimeEnabled(true);
        List<String> paths = new ArrayList<>();
        for (int index = 0; index < 50_000; index++) paths.add("project/module" + index % 200 + "/src/Widget" + index + ".java");
        List<Double> beforeWall = new ArrayList<>(), afterWall = new ArrayList<>(), beforeCpu = new ArrayList<>(), afterCpu = new ArrayList<>();
        try (Ranker first = Ranker.load(before); Ranker second = Ranker.load(after)) {
            for (int sample = -30; sample < 50; sample++) {
                Object a = null, b = null;
                for (int order = 0; order < 2; order++) {
                    boolean old = (sample + order & 1) == 0;
                    long cpu = clock.getCurrentThreadCpuTime(), wall = System.nanoTime();
                    Object result = (old ? first : second).rank(paths);
                    double elapsedWall = (System.nanoTime() - wall) / 1_000_000d;
                    double elapsedCpu = (clock.getCurrentThreadCpuTime() - cpu) / 1_000_000d;
                    if (old) a = result; else b = result;
                    if (sample >= 0) {
                        (old ? beforeWall : afterWall).add(elapsedWall);
                        (old ? beforeCpu : afterCpu).add(elapsedCpu);
                    }
                }
                if (!a.equals(b)) throw new IllegalStateException("Ranking results differ");
            }
        }
        System.out.println("{\"before_sha256\":\"" + sha(before) + "\",\"after_sha256\":\"" + sha(after)
            + "\",\"java\":\"" + System.getProperty("java.runtime.version") + "\",\"paths\":50000,\"warmups\":30,\"samples\":50,"
            + "\"before_wall_ms\":" + beforeWall + ",\"after_wall_ms\":" + afterWall
            + ",\"before_cpu_ms\":" + beforeCpu + ",\"after_cpu_ms\":" + afterCpu + "}");
    }
    private static String sha(Path path) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path)));
    }
}
