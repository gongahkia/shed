package shed;

import java.awt.Rectangle;
import java.lang.management.ManagementFactory;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.FutureTask;
import javax.swing.SwingUtilities;

/** Compiled independently against either revision's JAR by benchmark-editor.py. */
public final class EditorWorkflowProbe {
    private static <T> T edt(Callable<T> work) throws Exception {
        FutureTask<T> result = new FutureTask<>(work);
        SwingUtilities.invokeAndWait(result);
        return result.get();
    }
    public static void main(String[] args) throws Exception {
        Path fixture = Path.of(args[0]).toRealPath();
        Texteditor editor = edt(() -> new Texteditor(new String[] { fixture.toString() }));
        System.out.println("SHED_WINDOW " + System.currentTimeMillis());
        long deadline = System.nanoTime() + 60_000_000_000L;
        while (!edt(() -> editor.getCurrentBuffer() != null
            && editor.getCurrentBuffer().hasFilePath()
            && Path.of(editor.getCurrentBuffer().getFilePath()).toRealPath().equals(fixture))) {
            if (System.nanoTime() > deadline) throw new IllegalStateException("Full UI/file readiness timed out");
            Thread.sleep(20);
        }
        edt(() -> { editor.writingArea.paintImmediately(editor.writingArea.getVisibleRect()); return null; });
        System.out.println("SHED_SOURCE " + System.currentTimeMillis());
        while (edt(() -> editor.stagedStartupPending)) {
            if (System.nanoTime() > deadline) throw new IllegalStateException("Full UI readiness timed out");
            Thread.sleep(20);
        }
        edt(() -> { editor.getRootPane().paintImmediately(editor.getRootPane().getVisibleRect()); return null; });
        long readyAt = System.currentTimeMillis();
        Thread.sleep(1000);
        var memory = ManagementFactory.getMemoryMXBean().getHeapMemoryUsage();
        System.out.println("SHED_READY " + readyAt);
        System.out.flush();
        List<Double> edit = new ArrayList<>();
        for (int sample = -10; sample < 25; sample++) {
            double elapsed = edt(() -> {
                editor.writingArea.setCaretPosition(120);
                long start = System.nanoTime();
                editor.writingArea.replaceSelection("x");
                Rectangle visible = editor.writingArea.getVisibleRect();
                editor.writingArea.paintImmediately(visible);
                long nanos = System.nanoTime() - start;
                editor.writingArea.replaceRange("", 120, 121);
                return nanos / 1_000_000d;
            });
            if (sample >= 0) edit.add(elapsed);
            Thread.sleep(30);
        }
        List<String> paths = new ArrayList<>(50_000);
        for (int index = 0; index < 50_000; index++) paths.add("project/module" + index % 200 + "/src/Widget" + index + ".java");
        FuzzyMatchService matcher = new FuzzyMatchService();
        List<Double> picker = new ArrayList<>();
        for (int sample = -10; sample < 25; sample++) {
            long start = System.nanoTime();
            List<String> found = matcher.matchPathStrings("wid12", paths, 50);
            double elapsed = (System.nanoTime() - start) / 1_000_000d;
            if (found.isEmpty()) throw new IllegalStateException("Invalid picker workload");
            if (sample >= 0) picker.add(elapsed);
        }
        var result = new LinkedHashMap<String, Object>();
        result.put("heap_used_mib", memory.getUsed() / 1048576d);
        result.put("heap_committed_mib", memory.getCommitted() / 1048576d);
        result.put("edit_paint_ms", edit);
        result.put("picker_ms", picker);
        result.put("perf", edt(() -> editor.perfService.report()));
        result.put("open_buffers", edt(() -> editor.buffers.size()));
        result.put("language_servers", edt(() -> editor.lspClients.size()));
        result.put("java", System.getProperty("java.runtime.version"));
        result.put("os", System.getProperty("os.name") + " " + System.getProperty("os.version") + " " + System.getProperty("os.arch"));
        System.out.println("SHED_RESULT " + MiniJson.stringify(result));
        edt(() -> { editor.closeEditor(); return null; });
        System.exit(0);
    }
}
