package shed;

import java.lang.management.ManagementFactory;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.function.BooleanSupplier;

/** Isolated, opt-in lexical replay; the real UI convergence measurement remains a separate mode. */
final class MarkdownLexicalProbe {
    private final GrammarHighlightService grammar = new GrammarHighlightService();
    private final Object cache;
    private final Method bind, changed, highlight;
    private final com.sun.management.ThreadMXBean threads = (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();

    MarkdownLexicalProbe() throws Exception {
        Object value = null; Method b = null, c = null, h = null;
        try {
            Class<?> type = Class.forName("shed.GrammarHighlightService$MarkdownCache");
            var constructor = type.getDeclaredConstructor(); constructor.setAccessible(true); value = constructor.newInstance();
            b = type.getDeclaredMethod("bind", VersionedTextSnapshot.class);
            c = type.getDeclaredMethod("changed", VersionedTextSnapshot.class, VersionedTextSnapshot.class, int.class, int.class);
            h = GrammarHighlightService.class.getDeclaredMethod("highlightMarkdown", VersionedTextSnapshot.class, int.class, int.class, type, long.class, BooleanSupplier.class);
            b.setAccessible(true); c.setAccessible(true); h.setAccessible(true);
        } catch (ClassNotFoundException baseline) { /* Preserved distribution before checkpoint support. */ }
        cache = value; bind = b; changed = c; highlight = h;
    }

    @SuppressWarnings("unchecked")
    private void measure(String name, VersionedTextSnapshot text, int position, boolean measured, boolean verify) throws Exception {
        int line = text.positionAt(position).line();
        int start = text.lineStartOffset(Math.max(0, line - 60));
        int end = text.lineEndOffset(Math.min(text.lineCount() - 1, line + 60));
        List<GrammarHighlightService.Token> tokens;
        long allocated = threads.getThreadAllocatedBytes(Thread.currentThread().threadId());
        long began = System.nanoTime();
        Map<String, Object> result = new LinkedHashMap<>();
        if (cache != null) {
            long generation = (long) bind.invoke(cache, text);
            Object value = highlight.invoke(grammar, text, start, end, cache, generation, (BooleanSupplier) () -> false);
            if (value == null) throw new AssertionError("Unexpected cancellation");
            tokens = (List<GrammarHighlightService.Token>) field(value, "tokens");
            for (String key : List.of("scannedCharacters", "readCharacters", "scannedLines", "scanStart", "checkpoints")) result.put(key, field(value, key));
        } else {
            // The actual old production path constructs a grammar and a full/prefix string per job.
            int scanEnd = text.length() <= 750000 && text.lineCount() <= 20000 ? text.length() : end;
            String source = text.substring(0, scanEnd);
            var old = new GrammarHighlightService();
            tokens = scanEnd == text.length() && text.length() <= 750000 && text.lineCount() <= 20000
                ? old.highlightSnapshot(source, FileType.MARKDOWN) : old.highlightViewport(source, FileType.MARKDOWN, start, end);
            result.put("scannedCharacters", scanEnd); result.put("readCharacters", scanEnd); result.put("checkpoints", 0);
        }
        double duration = (System.nanoTime() - began) / 1e6;
        long bytes = threads.getThreadAllocatedBytes(Thread.currentThread().threadId()) - allocated;
        result.put("operation", name); result.put("state", measured ? "OK" : "WARMUP");
        result.put("wall_ms", duration); result.put("allocated_bytes", bytes); result.put("tokens", tokens.size());
        result.put("outside_context_tokens", tokens.stream().filter(t -> t.start() < start || t.end() > end).count());
        result.put("context_start", start); result.put("context_end", end); result.put("total_characters", text.length());
        if (verify) {
            var expected = grammar.highlightSnapshot(text.text(), FileType.MARKDOWN).stream().filter(t -> t.start() >= start && t.end() <= end).toList();
            var actual = tokens.stream().filter(t -> t.start() >= start && t.end() <= end).toList();
            if (!expected.equals(actual)) throw new AssertionError("Visible tokens differ from full reference");
            result.put("reference_equal", true);
        }
        System.out.println("MD_SAMPLE " + MiniJson.stringify(result));
    }

    private static Object field(Object value, String name) throws Exception {
        var f = value.getClass().getDeclaredField(name); f.setAccessible(true); return f.get(value);
    }

    private void change(VersionedTextSnapshot before, VersionedTextSnapshot after, int at, int removed) throws Exception {
        if (cache != null) changed.invoke(cache, before, after, at, removed);
    }

    static void run(Path file, int samples, int warmups) throws Exception {
        if (Files.size(file) > 10 * 1024 * 1024) throw new IllegalArgumentException("Lexical replay limit: 10 MiB");
        var probe = new MarkdownLexicalProbe();
        var otherProbe = new MarkdownLexicalProbe();
        var text = VersionedTextSnapshot.of(Files.readString(file));
        for (int p = 0; p < 3; p++) for (int i = -warmups; i < samples; i++) {
            int at = p == 0 ? 0 : p == 1 ? text.length() / 2 : text.length();
            probe.measure("lexical_viewport_" + p, text, at, i >= 0, i == 0);
        }
        for (String scenario : List.of("visible_insert", "visible_delete", "before_insert", "before_delete", "fence_edit", "unmatched_fence", "rapid_edits", "distant_jump", "buffer_switch")) {
            for (int i = 0; i < Math.min(samples, 10); i++) {
                int at = scenario.startsWith("visible") ? Math.max(0, text.length() - 40) : 0;
                int removed = scenario.endsWith("delete") ? Math.min(1, text.length() - at) : 0;
                String insertion = scenario.endsWith("delete") ? "" : scenario.equals("unmatched_fence") ? "```java\n/* open\n" : "x";
                if (scenario.equals("fence_edit")) {
                    int fence = text.text().indexOf("```"); // Outside timing, bounded fixture only.
                    at = fence < 0 ? 0 : fence; insertion = "~";
                }
                String restore = text.substring(at, removed);
                var next = text.replace(at, removed, insertion); probe.change(text, next, at, removed);
                if (scenario.equals("rapid_edits")) {
                    for (int n = 0; n < 8; n++) { var newer = next.replace(0, 0, "x"); probe.change(next, newer, 0, 0); next = newer; }
                }
                if (scenario.equals("buffer_switch")) otherProbe.measure("lexical_other_buffer", VersionedTextSnapshot.of("```java\nclass Other {}\n"), 0, true, true);
                int position = scenario.equals("distant_jump") && (i & 1) == 0 ? 0 : next.length();
                probe.measure("lexical_" + scenario, next, position, true, i == 0);
                // Undo uses an exact immutable replacement, not a reset to an old snapshot identity.
                int added = insertion.length() + (scenario.equals("rapid_edits") ? 8 : 0);
                var undone = next.replace(at, added, restore); probe.change(next, undone, at, added);
                if (!undone.text().equals(text.text())) throw new AssertionError("Replay failed to restore source");
                text = undone;
                probe.measure("lexical_after_undo", text, text.length(), false, false);
            }
        }
        System.gc();
        System.out.println("MD_META " + MiniJson.stringify(Map.of("operation", "lexical_final_requested_gc",
            "heap_used", ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed(), "checkpoints", probe.cache == null ? 0 : field(probe.cache, "checkpoints") instanceof Map<?, ?> m ? m.size() : 0)));
    }
}
