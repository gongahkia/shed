package shed;

import java.awt.*;
import java.awt.event.*;
import java.nio.file.*;
import java.util.*;
import javax.swing.*;
import javax.swing.plaf.basic.BasicTextAreaUI;
import javax.swing.text.*;
import static shed.MarkdownPerformanceProbe.*;

/** Opt-in production observations and a separate instrumented stock-Swing causal control. */
public final class UnicodeLayoutProbe {
    private static Texteditor editor;

    private static void set(String name, Object value) throws Exception {
        var field = MarkdownPerformanceProbe.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(null, value);
    }

    private static void production(Path file, boolean wrap) throws Exception {
        set("file", file);
        set("wrap", wrap);
        var open = MarkdownPerformanceProbe.class.getDeclaredMethod("open");
        open.setAccessible(true);
        open.invoke(null);
        var field = MarkdownPerformanceProbe.class.getDeclaredField("editor");
        field.setAccessible(true);
        editor = (Texteditor) field.get(null);
        String original = Files.readString(file);
        var buffer = edt(editor::getCurrentBuffer);
        if (!original.equals(edt(buffer::getContent))) throw new AssertionError("Initial source mismatch");
        for (int i = 0; i < 20; i++) {
            long cycleStarted = System.nanoTime();
            edt(() -> { editor.writingArea.setCaretPosition(0); return null; });
            uiText("warm_insert", () -> typed('x'), true);
            uiText("undo", () -> buffer.getUndoManager().undo(), true);
            uiText("redo", () -> buffer.getUndoManager().redo(), true);
            edt(() -> { editor.writingArea.setCaretPosition(1); return null; });
            uiText("backspace", () -> key(KeyEvent.VK_BACK_SPACE), true);
            if (!original.equals(edt(buffer::getContent))) throw new AssertionError("Backspace mismatch");
            uiText("newline", () -> typed('\n'), true);
            uiText("undo_newline", () -> buffer.getUndoManager().undo(), true);
            edt(() -> { editor.writingArea.setCaretPosition(0); return null; });
            uiText("delete", () -> key(KeyEvent.VK_DELETE), true);
            uiText("undo_delete", () -> buffer.getUndoManager().undo(), true);
            if (!original.equals(edt(buffer::getContent))) throw new AssertionError("Round trip mismatch");
            if (System.nanoTime() - cycleStarted > 500_000_000L) {
                out("MD_META ", Map.of("operation", "latency_guard", "completed_cycles", i + 1,
                    "reason", "Do not repeat a cycle exceeding 500 ms"));
                break;
            }
        }
        // This is the Swing text-input action, not a native IME qualification.
        edt(() -> { editor.writingArea.setCaretPosition(0); return null; });
        uiText("unicode_text_action", () -> editor.writingArea.getActionMap().get(DefaultEditorKit.defaultKeyTypedAction)
            .actionPerformed(new ActionEvent(editor.writingArea, 0, "é e\u0301 😀 👩‍💻 क्ष 中文 العربية")), true);
        edt(() -> { buffer.getUndoManager().undo(); return null; });
        if (!original.equals(edt(buffer::getContent))) throw new AssertionError("Unicode text-action undo mismatch");
        for (String action : new String[]{"caret-forward", "selection-forward", "caret-down", "caret-up"}) {
            var method = MarkdownPerformanceProbe.class.getDeclaredMethod("uiOther", String.class, Work.class, boolean.class);
            method.setAccessible(true);
            method.invoke(null, action, (Work) () -> editor.writingArea.getActionMap().get(action)
                .actionPerformed(new ActionEvent(editor.writingArea, 0, action)), true);
        }
        edt(() -> { editor.writingArea.setCaretPosition(buffer.getDocument().getLength());
            editor.writingArea.paintImmediately(editor.writingArea.getVisibleRect()); return null; });
        if (!original.equals(edt(buffer::getContent))) throw new AssertionError("Final source mismatch");
        memory("production_after_operations");
        out("MD_META ", Map.of("operation", "integrity", "source_and_undo_exact", true,
            "theme", editor.configManager.getThemeId(), "font", edt(() -> editor.writingArea.getFont().toString()), "wrap", wrap));
    }

    private static void key(int code) {
        var area = editor.writingArea;
        var event = new KeyEvent(area, KeyEvent.KEY_PRESSED, System.currentTimeMillis(), 0, code, KeyEvent.CHAR_UNDEFINED);
        editor.keyPressed(event);
        if (!event.isConsumed()) {
            var binding = area.getInputMap().get(KeyStroke.getKeyStroke(code, 0));
            var action = area.getActionMap().get(binding);
            if (action == null) throw new AssertionError("Missing key action " + code);
            action.actionPerformed(new ActionEvent(area, 0, ""));
        }
    }

    private static final class Reads extends PlainDocument {
        long fullReads, characters, gapCopies, breakReads;
        @Override public void getText(int start, int length, Segment target) throws BadLocationException {
            super.getText(start, length, target);
            characters += length;
            if (length >= getLength() && getLength() > 0) {
                fullReads++;
                if (target.array.length == length && target.offset == 0) gapCopies++;
                if (StackWalker.getInstance().walk(s -> s.anyMatch(f -> f.getClassName().equals("javax.swing.text.GlyphView")
                        && f.getMethodName().equals("getBreakSpot")))) breakReads++;
            }
        }
        void reset() { fullReads = characters = gapCopies = breakReads = 0; }
    }

    private static void stock(Path file, boolean wrap) throws Exception {
        String text = Files.readString(file);
        var document = new Reads();
        document.insertString(0, text, null);
        edt(() -> {
            var area = new JTextArea();
            area.setFont(BundledEditorFont.atSize(16));
            area.setLineWrap(wrap);
            int[] roots = {0};
            area.setUI(new BasicTextAreaUI() {
                @Override public View create(Element element) {
                    if (element == document.getDefaultRootElement()) roots[0]++;
                    return super.create(element);
                }
            });
            for (String phase : new String[]{"attach", "first_insert", "warm_insert", "font_change"}) {
                document.reset();
                int before = roots[0];
                long start = System.nanoTime();
                switch (phase) {
                    case "attach" -> { area.setDocument(document); area.setSize(800, 600); area.getPreferredSize(); }
                    case "first_insert", "warm_insert" -> document.insertString(0, "x", null);
                    case "font_change" -> area.setFont(area.getFont().deriveFont(17f));
                }
                var image = new java.awt.image.BufferedImage(800, 600, java.awt.image.BufferedImage.TYPE_INT_RGB);
                var graphics = image.createGraphics();
                try { area.paint(graphics); } finally { graphics.dispose(); image.flush(); }
                out("MD_META ", Map.of("operation", "stock_" + phase, "wall_ms", (System.nanoTime() - start) / 1e6,
                    "root_creations", roots[0] - before, "full_document_reads", document.fullReads,
                    "requested_characters", document.characters, "full_array_segments", document.gapCopies,
                    "glyph_break_root_reads", document.breakReads, "i18n", String.valueOf(document.getProperty("i18n")),
                    "view", area.getUI().getRootView(area).getView(0).getClass().getName()));
            }
            if (!document.getText(0, document.getLength()).equals("xx" + text)) throw new AssertionError("Stock source mismatch");
            area.setDocument(new PlainDocument());
            return null;
        });
    }

    public static void main(String[] args) throws Exception {
        int exit = 1;
        try {
            if (args[1].equals("stock")) stock(Path.of(args[0]), Boolean.parseBoolean(args[2]));
            else production(Path.of(args[0]), Boolean.parseBoolean(args[2]));
            System.out.println("MD_DONE");
            exit = 0;
        } catch (Throwable failure) {
            failure.printStackTrace();
        } finally {
            if (editor != null) edt(() -> {
                editor.disposeMarkdownPreviews(); editor.shutdownSyntaxUi(); editor.shutdownLspScheduling();
                editor.asyncJobService.shutdownNow(); editor.fileWatcherService.stop();
                if (editor.backupScheduler != null) editor.backupScheduler.close();
                if (editor.recoverySnapshotTimer != null) editor.recoverySnapshotTimer.stop();
                if (editor.externalChangeTimer != null) editor.externalChangeTimer.stop();
                if (editor.messageResetTimer != null) editor.messageResetTimer.stop();
                editor.dispose(); return null;
            });
            System.exit(exit);
        }
    }
}
