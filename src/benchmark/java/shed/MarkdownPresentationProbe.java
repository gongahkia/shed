package shed;

import java.awt.*;
import java.awt.event.ActionEvent;
import java.awt.image.BufferedImage;
import java.lang.management.ManagementFactory;
import java.lang.ref.WeakReference;
import java.nio.file.*;
import java.util.*;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import javax.imageio.ImageIO;
import javax.swing.*;
import javax.swing.text.*;
import static shed.MarkdownPerformanceProbe.*;

/** Bounded 4A diagnostic, compiled only on explicit request, never packaged into Shed. */
final class MarkdownPresentationProbe {
    private static final com.sun.management.ThreadMXBean THREADS = (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
    private static volatile Object sink;
    private static <T> T timed(String operation, Callable<T> work) throws Exception {
        long start = System.nanoTime(), bytes = THREADS.getThreadAllocatedBytes(Thread.currentThread().threadId());
        T result = work.call(); out("MD_SAMPLE ", Map.of("operation", operation, "state", "OK", "wall_ms", (System.nanoTime() - start) / 1e6,
            "thread_allocated_bytes", Math.max(0, THREADS.getThreadAllocatedBytes(Thread.currentThread().threadId()) - bytes), "thread", Thread.currentThread().getName())); return result;
    }
    private static void settle(String phase) throws Exception {
        for (int i = 0; i < 3; i++) { System.gc(); Thread.sleep(80); }
        memory(phase);
        var ps = new ProcessBuilder("ps", "-o", "rss=", "-p", Long.toString(ProcessHandle.current().pid())).start();
        String rss = new String(ps.getInputStream().readAllBytes()).strip(); ps.waitFor();
        out("MD_META ", Map.of("operation", phase + "_rss", "rss_mib", Long.parseLong(rss) / 1024d));
    }
    private record Prepared(MarkdownPresentationFrame frame, long elapsed) { }
    private static Prepared prepare(Texteditor editor, MarkdownPresentationPrototype prototype, FileBuffer buffer) throws Exception {
        var ticket = edt(prototype::capture); var slot = new AtomicReference<Prepared>(); var error = new AtomicReference<Exception>(); var done = new CountDownLatch(1); long began = System.nanoTime();
        edt(() -> {
            editor.asyncJobService.submit("4A explicit presentation", token -> {
                var semantic = timed("presentation_semantics_worker", () -> MarkdownHeadingAnalysis.analyze(ticket.source(), token::isCancelled).semantic());
                var frame = timed("presentation_geometry_worker", () -> MarkdownPresentationFrame.prepare(semantic, ticket.settings(), ticket.generation(), token::isCancelled));
                return new Prepared(frame, System.nanoTime() - began);
            }, (job, result, failure) -> { slot.set(result); error.set(failure); done.countDown(); }); return null;
        });
        if (!done.await(45, TimeUnit.SECONDS)) throw new AssertionError("Presentation worker timeout");
        if (error.get() != null) throw error.get();
        Prepared prepared = slot.getAndSet(null); // job history retains only an empty slot, never obsolete geometry/source
        boolean installed = edt(() -> timed("presentation_install_edt", () -> prototype.install(prepared.frame())));
        out("MD_META ", Map.of("operation", "presentation_prepared", "state", prepared.frame().state.name(), "detail", prepared.frame().detail,
            "installed", installed, "rows", prepared.frame().rows.size(), "decorations", prepared.frame().styles.size(), "metadata_payload_bytes", prepared.frame().metadataPayloadBytes()));
        out("MD_SAMPLE ", Map.of("operation", "explicit_presentation_convergence", "state", "OK", "wall_ms", prepared.elapsed() / 1e6));
        return prepared;
    }
    private static void paint(JTextArea area) { area.paintImmediately(area.getVisibleRect()); }
    private static void edit(JTextArea area, FileBuffer buffer, String operation, boolean measured) throws Exception {
        long began = System.nanoTime();
        edt(() -> {
            long mutation = System.nanoTime();
            area.getActionMap().get(DefaultEditorKit.defaultKeyTypedAction).actionPerformed(new ActionEvent(area, 0, "x"));
            long changed = System.nanoTime(); paint(area); long painted = System.nanoTime();
            if (measured) out("MD_SAMPLE ", Map.of("operation", operation, "state", "OK", "mutation_edt_ms", (changed - mutation) / 1e6,
                "mutation_and_paint_ms", (painted - began) / 1e6, "paint_ms", (painted - changed) / 1e6));
            buffer.getUndoManager().undo(); paint(area); return null;
        });
    }
    private static void screenshot(JTextArea area, String name) throws Exception {
        BufferedImage image = edt(() -> {
            Rectangle visible = area.getVisibleRect(); var result = new BufferedImage(Math.max(1, visible.width), Math.max(1, visible.height), BufferedImage.TYPE_INT_RGB);
            var graphics = result.createGraphics(); try { graphics.translate(-visible.x, -visible.y); graphics.setClip(visible); area.paint(graphics); } finally { graphics.dispose(); } return result;
        });
        Path output = Path.of(System.getProperty("shed.presentation.output", System.getProperty("java.io.tmpdir"))).resolve(name);
        ImageIO.write(image, "png", output.toFile()); out("MD_META ", Map.of("operation", "presentation_image", "path", output.toString())); image.flush();
    }
    static void run(Texteditor editor, int samples, int warmups, String mode) throws Exception {
        settle("production_editor");
        if (mode.equals("presentation-styled")) { styledCandidate(editor); return; }
        FileBuffer buffer = edt(editor::getCurrentBuffer); String expected = edt(() -> buffer.textSnapshot().text()); long revision = edt(buffer::documentRevision);
        Container original = edt(editor::getContentPane);
        JTextArea area = edt(() -> {
            JTextArea raw = new JTextArea(); raw.putClientProperty(SourcePlainView.SOURCE_PROPERTY, Boolean.TRUE);
            raw.setDocument(buffer.getDocument()); raw.setFont(editor.writingArea.getFont()); raw.setBackground(editor.writingArea.getBackground()); raw.setForeground(editor.writingArea.getForeground());
            raw.setSelectionColor(editor.writingArea.getSelectionColor()); raw.setSelectedTextColor(editor.writingArea.getSelectedTextColor()); raw.setLineWrap(editor.writingArea.getLineWrap()); raw.setWrapStyleWord(true);
            raw.putClientProperty(MarkdownPresentationPrototype.LINK_COLOR, editor.configManager.getSyntaxFunctionColor());
            raw.updateUI(); raw.setCaret(new DefaultCaret()); raw.getCaret().setBlinkRate(0);
            var scroll = new JScrollPane(raw); scroll.setVerticalScrollBarPolicy(ScrollPaneConstants.VERTICAL_SCROLLBAR_ALWAYS);
            editor.setContentPane(scroll); editor.validate(); raw.setCaretPosition(0); return raw;
        });
        MarkdownPresentationPrototype prototype = mode.equals("presentation-prototype") ? edt(() -> new MarkdownPresentationPrototype(area,
            () -> new MarkdownPresentationPrototype.Binding(MarkdownSemanticSnapshot.Source.capture(editor.documentStore.id(buffer), buffer), buffer.getDocument()))) : null;
        WeakReference<MarkdownPresentationPrototype> released = new WeakReference<>(prototype);
        List<WeakReference<MarkdownPresentationPrototype>> repeated = new ArrayList<>();
        List<WeakReference<MarkdownPresentationFrame>> frames = new ArrayList<>();
        try {
            if (prototype != null) {
                var prepared = prepare(editor, prototype, buffer); frames.add(new WeakReference<>(prepared.frame()));
                prepared = null;
            }
            edt(() -> { editor.validate(); paint(area); return null; });
            screenshot(area, mode + "-initial-" + buffer.textSnapshot().length() + "-" + area.getLineWrap() + ".png");
            settle("retained_presentation");
            for (int i = 0; i < samples; i++) {
                final int step = i;
                edt(() -> {
                    long start = System.nanoTime();
                    var scroll = (JScrollPane) editor.getContentPane(); int range = Math.max(0, area.getHeight() - scroll.getViewport().getHeight());
                    scroll.getViewport().setViewPosition(new Point(0, (int) ((long) range * (step % 11) / 10)));
                    long layout = System.nanoTime(); paint(area);
                    out("MD_SAMPLE ", Map.of("operation", "presentation_scroll", "state", "OK", "scroll_edt_ms", (layout - start) / 1e6, "paint_ms", (System.nanoTime() - layout) / 1e6)); return null;
                });
            }
            edt(() -> { ((JScrollPane) editor.getContentPane()).getViewport().setViewPosition(new Point(0, 0)); area.setCaretPosition(0); paint(area); return null; });
            for (int i = 0; i < 3; i++) {
                if (prototype != null) {
                    var prepared = prepare(editor, prototype, buffer); frames.add(new WeakReference<>(prepared.frame())); prepared = null;
                }
                edit(area, buffer, "presentation_first_edit", true);
            }
            for (int i = -warmups; i < samples; i++) edit(area, buffer, "presentation_source_fallback_typing", i >= 0);
            if (prototype != null) {
                var prepared = prepare(editor, prototype, buffer); frames.add(new WeakReference<>(prepared.frame())); prepared = null;
                for (int i = 0; i < samples; i++) {
                    final int offset = i % Math.max(1, Math.min(buffer.textSnapshot().length(), 200));
                    edt(() -> { long start = System.nanoTime(); area.setCaretPosition(offset); area.moveCaretPosition(Math.min(area.getDocument().getLength(), offset + 5)); paint(area);
                        out("MD_SAMPLE ", Map.of("operation", "presentation_caret_selection", "state", "OK", "wall_ms", (System.nanoTime() - start) / 1e6)); return null; });
                }
                out("MD_META ", Map.of("operation", "presentation_cache", "metrics", edt(prototype::metrics).toString()));
                screenshot(area, mode + "-selection-" + buffer.textSnapshot().length() + "-" + area.getLineWrap() + ".png");
            }
            if (!expected.equals(edt(() -> buffer.textSnapshot().text())) || edt(buffer::documentRevision) - revision != 2L * (samples + warmups + 3)) throw new AssertionError("Unexpected source mutation");
            out("MD_META ", Map.of("operation", "presentation_source", "unexpected_revision_delta", edt(buffer::documentRevision) - revision - 2L * (samples + warmups + 3)));
            if (prototype != null && expected.length() <= 4096) {
                close(prototype); prototype = null;
                for (int cycle = 0; cycle < 3; cycle++) {
                    var next = edt(() -> new MarkdownPresentationPrototype(area, () -> new MarkdownPresentationPrototype.Binding(
                        MarkdownSemanticSnapshot.Source.capture(editor.documentStore.id(buffer), buffer), buffer.getDocument())));
                    repeated.add(new WeakReference<>(next));
                    var prepared = prepare(editor, next, buffer); frames.add(new WeakReference<>(prepared.frame())); prepared = null;
                    edt(() -> { paint(area); return null; }); close(next); next = null;
                }
            }
        } finally {
            if (prototype != null) { var current = prototype; edt(() -> { current.close(); return null; }); }
            prototype = null;
            edt(() -> { area.setDocument(new PlainDocument()); editor.setContentPane(original); editor.validate(); return null; });
        }
        settle("after_presentation_disposal");
        out("MD_META ", Map.of("operation", "presentation_disposal", "prototype_collected", released.get() == null,
            "frames_still_referenced", frames.stream().filter(reference -> reference.get() != null).count(),
            "repeat_cycles", repeated.size(), "repeated_prototypes_alive", repeated.stream().filter(reference -> reference.get() != null).count()));
        if (released.get() != null || frames.stream().anyMatch(reference -> reference.get() != null) || repeated.stream().anyMatch(reference -> reference.get() != null)) throw new AssertionError("Prototype state retained");
    }
    private static void close(MarkdownPresentationPrototype prototype) throws Exception { edt(() -> { prototype.close(); return null; }); }
    private static void styledCandidate(Texteditor editor) throws Exception {
        FileBuffer buffer = edt(editor::getCurrentBuffer); var source = edt(buffer::textSnapshot);
        var copy = timed("styled_document_copy_worker", () -> { var document = new DefaultStyledDocument(); document.insertString(0, source.text(), null); return document; });
        timed("styled_attributes_worker", () -> {
            var attributes = new SimpleAttributeSet(); StyleConstants.setBold(attributes, true); StyleConstants.setFontSize(attributes, 24);
            for (int offset = 0, count = 0; offset < source.length() && count < 1024; offset += 100, count++) copy.setCharacterAttributes(offset, Math.min(10, source.length() - offset), attributes, false);
            return null;
        });
        var pane = edt(() -> {
            var component = new JTextPane(copy); component.setFont(editor.writingArea.getFont()); component.setForeground(editor.writingArea.getForeground()); component.setBackground(editor.writingArea.getBackground());
            editor.setContentPane(new JScrollPane(component)); editor.validate(); return component;
        });
        edt(() -> timed("styled_layout_paint_edt", () -> { pane.getPreferredSize(); pane.paintImmediately(pane.getVisibleRect()); return null; }));
        settle("retained_styled_candidate");
        out("MD_META ", Map.of("operation", "styled_ownership", "shares_canonical_document", pane.getDocument() == buffer.getDocument(), "characters", source.length()));
        edt(() -> { editor.setContentPane(new JPanel()); pane.setDocument(new DefaultStyledDocument()); editor.validate(); return null; });
        sink = null; settle("after_styled_candidate_disposal");
    }
}
