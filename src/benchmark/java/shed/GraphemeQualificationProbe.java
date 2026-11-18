package shed;

import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/** Opt-in read-only conformance diagnostic and allocation/latency control; no UI or source mutations. */
public final class GraphemeQualificationProbe {
    private static volatile int sink;

    public static void main(String[] args) throws Exception {
        List<Map<String,Object>> failures = new ArrayList<>(); int cases = 0, gb9c = 0, gb9cFailed = 0;
        for (String line : Files.readAllLines(Path.of(args[0]))) {
            String data = line.split("#", 2)[0].trim(); if (data.isEmpty()) continue;
            StringBuilder text = new StringBuilder(); List<Integer> expected = new ArrayList<>();
            for (String token : data.split("\\s+")) {
                if (token.equals("÷")) expected.add(text.length());
                else if (!token.equals("×")) text.appendCodePoint(Integer.parseInt(token, 16));
            }
            List<Integer> actual = new ArrayList<>(List.of(0));
            for (int at = 0; at < text.length();) { at = GraphemeBoundary.next(text.toString(), at); actual.add(at); }
            boolean failed = !actual.equals(expected); cases++;
            if (line.contains("[9.3]")) { gb9c++; if (failed) gb9cFailed++; }
            if (failed) failures.add(Map.of("line", line, "expected_utf16", expected, "actual_utf16", actual));
        }
        List<Map<String,Object>> timing = new ArrayList<>();
        for (int size : List.of(1024, 102400, 1048576)) {
            String ascii = "a".repeat(size), indic = "क्ष", longIndic = ascii + indic;
            for (String kind : List.of("ascii_previous", "conjunct_next", "conjunct_previous_at_eof")) {
                java.util.function.IntSupplier task = switch (kind) {
                    case "ascii_previous" -> () -> GraphemeBoundary.previous(ascii, ascii.length());
                    case "conjunct_next" -> () -> GraphemeBoundary.next(indic, 0);
                    default -> () -> GraphemeBoundary.previous(longIndic, longIndic.length());
                };
                int iterations = kind.endsWith("eof") ? 1 : 1000;
                for (int i = 0; i < 10 * iterations; i++) sink = task.getAsInt();
                var bean = (com.sun.management.ThreadMXBean)ManagementFactory.getThreadMXBean();
                double[] ns = new double[100];
                long id = Thread.currentThread().threadId(), allocated = bean.getThreadAllocatedBytes(id);
                for (int batch = 0; batch < ns.length; batch++) {
                    long began = System.nanoTime();
                    for (int i = 0; i < iterations; i++) sink = task.getAsInt();
                    ns[batch] = (System.nanoTime() - began) / (double) iterations;
                }
                allocated = bean.getThreadAllocatedBytes(id) - allocated; Arrays.sort(ns);
                timing.add(Map.of("size", size, "operation", kind, "samples", 100, "iterations_per_sample", iterations,
                    "p50_ns", ns[49], "p95_ns", ns[94], "allocated_bytes_per_call", allocated / (100.0 * iterations)));
            }
        }
        var iterator = java.text.BreakIterator.getCharacterInstance(Locale.ROOT); iterator.setText("क्ष");
        var regex = java.util.regex.Pattern.compile("\\X").matcher("क्ष"); regex.find();
        System.out.println(MiniJson.stringify(Map.of("unicode_version", "15.1.0", "cases", cases, "passed", cases - failures.size(),
            "failed", failures.size(), "gb9c_cases", gb9c, "gb9c_failed", gb9cFailed, "failures", failures, "timing", timing,
            "jdk_character_iterator_first_end", iterator.next(), "jdk_regex_X_first_end", regex.end())));
        // A diagnostic reports existing out-of-scope failures explicitly; it is not a passing conformance certification.
    }
}
