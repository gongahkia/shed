package shed;

import java.awt.*;
import java.awt.event.*;
import java.beans.PropertyChangeListener;
import java.lang.management.ManagementFactory;
import java.lang.ref.WeakReference;
import java.nio.file.*;
import java.util.*;
import javax.swing.*;
import javax.swing.text.*;
import static shed.MarkdownPerformanceProbe.*;

/** Opt-in Candidate A experiment; the adapter and this class are absent from the application JAR. */
public final class UnicodeCandidateProbe {
    private static Texteditor editor;
    private static boolean candidate;
    private static long processStarted;
    private static final class Tasks extends EventQueue {
        private final long[] longest = new long[16];
        @Override protected void dispatchEvent(AWTEvent event) {
            long start = System.nanoTime();
            try { super.dispatchEvent(event); }
            finally {
                long elapsed = System.nanoTime() - start;
                if (elapsed > longest[0]) { longest[0] = elapsed; Arrays.sort(longest); }
            }
        }
        void report() {
            out("MD_META ", Map.of("operation", "longest_edt_tasks", "wall_ms", Arrays.stream(longest).mapToDouble(n -> n / 1e6).boxed().toList()));
        }
    }

    private static void bindProbeEditor(Texteditor value) throws Exception {
        var field = MarkdownPerformanceProbe.class.getDeclaredField("editor");
        field.setAccessible(true); field.set(null, value);
    }

    private static void assertAdapter() {
        if (candidate != (editor.writingArea.getUI() instanceof ParagraphRangePrototype))
            throw new AssertionError("The requested factory is not installed: " + editor.writingArea.getUI());
    }

    private static void observed(String name, Work action) throws Exception {
        edt(() -> {
            var bean = (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
            long id = Thread.currentThread().threadId(), allocation = bean.getThreadAllocatedBytes(id);
            long cpu = bean.getCurrentThreadCpuTime(), start = System.nanoTime();
            action.run();
            out("MD_SAMPLE ", Map.of("operation", name, "state", "OK", "wall_ms", (System.nanoTime() - start) / 1e6,
                "thread_cpu_ms", (bean.getCurrentThreadCpuTime() - cpu) / 1e6,
                "thread_allocated_bytes", bean.getThreadAllocatedBytes(id) - allocation));
            return null;
        });
    }

    private static void run(Path file, boolean wrap, int samples) throws Exception {
        long launch = System.nanoTime();
        editor = edt(() -> new Texteditor(new String[]{file.toString()}));
        bindProbeEditor(editor);
        out("MD_META ", Map.of("operation", "visible", "from_probe_entry_ms", (System.nanoTime() - processStarted) / 1e6));
        await(() -> editor.getCurrentBuffer() != null && file.toString().equals(editor.getCurrentBuffer().getFilePath()), 30);
        edt(() -> { assertAdapter(); editor.setWrap(wrap); return null; });
        observed("preferred_size_after_attach", () -> editor.writingArea.getPreferredSize());
        observed("source_forced_paint", () -> editor.writingArea.paintImmediately(editor.writingArea.getVisibleRect()));
        out("MD_META ", Map.of("operation", "source_painted", "from_open_ms", (System.nanoTime() - launch) / 1e6));
        boolean dirty = edt(() -> editor.getCurrentBuffer().isModified());
        uiText("first_insert", () -> { assertAdapter(); typed('x'); }, true);
        edt(() -> { editor.getCurrentBuffer().getUndoManager().undo(); editor.getCurrentBuffer().setModified(dirty); return null; });
        await(() -> !editor.stagedStartupPending, 30);
        String original = Files.readString(file);
        if (!original.equals(edt(() -> editor.getCurrentBuffer().getContent()))) throw new AssertionError("Startup Undo");
        out("MD_META ", edt(() -> Map.of("operation", "configuration", "candidate", candidate,
            "font", editor.writingArea.getFont().toString(), "theme", editor.configManager.getThemeId(), "wrap", wrap,
            "ui", editor.writingArea.getUI().getClass().getName(), "view", editor.writingArea.getUI().getRootView(editor.writingArea).getView(0).getClass().getName(),
            "viewport", editor.writingArea.getVisibleRect().toString(), "lsp_clients", editor.lspClients.size())));
        memory("ready_no_gc");
        for (int i = 0; i < samples; i++) {
            edt(() -> { editor.writingArea.setCaretPosition(0); return null; });
            long start = System.nanoTime();
            uiText("warm_insert", () -> typed('x'), true);
            uiText("warm_undo", () -> editor.getCurrentBuffer().getUndoManager().undo(), true);
            if (!original.equals(edt(() -> editor.getCurrentBuffer().getContent()))) throw new AssertionError("Warm Undo");
            if (System.nanoTime() - start > 500_000_000L) {
                out("MD_META ", Map.of("operation", "slow_cycle_guard", "completed_cycles", i + 1)); break;
            }
        }
        edt(() -> { editor.writingArea.setCaretPosition(0); return null; });
        uiText("complex_text_action", () -> editor.writingArea.getActionMap().get(DefaultEditorKit.defaultKeyTypedAction)
            .actionPerformed(new ActionEvent(editor.writingArea, 0, "中文 e\u0301 क्ष 👩‍💻 العربية ")), true);
        edt(() -> { editor.getCurrentBuffer().getUndoManager().undo(); return null; });
        if (!original.equals(edt(() -> editor.getCurrentBuffer().getContent()))) throw new AssertionError("Complex Undo");
        memory("after_edits_no_gc");
        out("MD_META ", Map.of("operation", "integrity", "source_and_undo_exact", true));
        for (int i = 0; i < 3; i++) { System.gc(); Thread.sleep(80); }
        memory("after_edits_requested_gc");
        WeakReference<?>[] retired = edt(() -> {
            var buffer = editor.getCurrentBuffer();
            var refs = new WeakReference<?>[]{new WeakReference<>(buffer), new WeakReference<>(buffer.getDocument())};
            editor.closeActiveBufferWithoutExiting(true);
            return refs;
        });
        await(() -> editor.asyncJobService.list().stream().noneMatch(j -> j.getStatus() == AsyncJobService.Status.RUNNING), 20);
        for (int i = 0; i < 3; i++) { edt(() -> null); System.gc(); Thread.sleep(80); }
        memory("source_closed_requested_gc");
        out("MD_META ", Map.of("operation", "retired_source_observation", "alive_buffer_document",
            Arrays.stream(retired).map(r -> r.get() != null).toList()));
    }

    private static WeakReference<?>[] disposeEditor() throws Exception {
        return edt(() -> {
            var refs = new WeakReference<?>[]{new WeakReference<>(editor), new WeakReference<>(editor.writingArea.getUI()),
                new WeakReference<>(editor.getCurrentBuffer()), new WeakReference<>(editor.getCurrentBuffer().getDocument())};
            editor.disposeMarkdownPreviews(); editor.shutdownSyntaxUi(); editor.shutdownLspScheduling();
            editor.shutdownRecoveryJournalScheduling(); editor.asyncJobService.shutdownNow(); editor.fileWatcherService.stop();
            if (editor.backupScheduler != null) editor.backupScheduler.close();
            if (editor.recoverySnapshotTimer != null) editor.recoverySnapshotTimer.stop();
            if (editor.externalChangeTimer != null) editor.externalChangeTimer.stop();
            if (editor.messageResetTimer != null) editor.messageResetTimer.stop();
            editor.dispose(); editor = null; bindProbeEditor(null);
            return refs;
        });
    }

    private static void model(Path file) throws Exception {
        var buffer = new FileBuffer(file.toFile());
        observed("model_only_first_insert", () -> buffer.getDocument().insertString(0, "x", null));
        observed("model_only_undo", () -> buffer.getUndoManager().undo());
    }

    private static final class Reads extends PlainDocument {
        long characters, rootReads;
        @Override public void getText(int at, int length, Segment target) throws BadLocationException {
            characters += length;
            if (getLength() > 0 && length >= getLength()) rootReads++;
            super.getText(at, length, target);
        }
        void reset() { characters = rootReads = 0; }
    }

    private static void reads(Path file, boolean wrap) throws Exception {
        var document = new Reads(); document.insertString(0, Files.readString(file), null);
        edt(() -> {
            var area = new JTextArea(); area.putClientProperty(SourcePlainView.SOURCE_PROPERTY, true);
            area.setUI(candidate ? new ParagraphRangePrototype() : new WorkbenchStyle.TextAreaUI());
            area.setFont(BundledEditorFont.atSize(16)); area.setLineWrap(wrap); area.setWrapStyleWord(true); area.setSize(800, 600);
            for (String phase : new String[]{"attach", "first_insert", "warm_insert"}) {
                document.reset();
                if (phase.equals("attach")) area.setDocument(document); else document.insertString(0, "x", null);
                area.getPreferredSize();
                out("MD_META ", Map.of("operation", "reads_" + phase, "requested_characters", document.characters,
                    "root_reads", document.rootReads, "utf16_units", document.getLength()));
            }
            area.setDocument(new PlainDocument()); return null;
        });
    }

    public static void main(String[] args) throws Exception {
        processStarted = System.nanoTime(); candidate = args[1].endsWith("candidate");
        var tasks = new Tasks(); Toolkit.getDefaultToolkit().getSystemEventQueue().push(tasks);
        String factory = candidate ? ParagraphRangePrototype.class.getName() : WorkbenchStyle.TextAreaUI.class.getName();
        PropertyChangeListener guard = event -> {
            if (event.getPropertyName().equals("TextAreaUI") && !factory.equals(event.getNewValue())) UIManager.put("TextAreaUI", factory);
        };
        if (candidate) { UIManager.getDefaults().addPropertyChangeListener(guard); UIManager.put("TextAreaUI", factory); }
        int exit = 1;
        try {
            if (args[1].equals("model")) model(Path.of(args[0]));
            else if (args[1].startsWith("reads-")) reads(Path.of(args[0]), Boolean.parseBoolean(args[2]));
            else {
                run(Path.of(args[0]), Boolean.parseBoolean(args[2]), Integer.parseInt(args[3]));
                var references = disposeEditor();
                for (int i = 0; i < 3; i++) { edt(() -> null); System.gc(); Thread.sleep(80); }
                memory("disposed_requested_gc");
                out("MD_META ", Map.of("operation", "disposal_observation", "alive_editor_ui_buffer_document",
                    Arrays.stream(references).map(r -> r.get() != null).toList()));
            }
            edt(() -> { tasks.report(); return null; });
            System.out.println("MD_DONE"); exit = 0;
        } catch (Throwable error) { error.printStackTrace(); }
        finally {
            UIManager.getDefaults().removePropertyChangeListener(guard);
            if (editor != null) disposeEditor();
            System.exit(exit);
        }
    }
}
