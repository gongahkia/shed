package shed;

import java.lang.management.ManagementFactory;
import java.lang.ref.WeakReference;
import java.nio.file.*;
import java.util.*;
import static shed.MarkdownPerformanceProbe.*;

/** Opt-in bounded project workload using the production facade, workbench and worker builder. */
final class MarkdownLinkProbe {
    static void run(Texteditor editor, int samples, int warmups) throws Exception {
        FileBuffer buffer = edt(editor::getCurrentBuffer); Path file = Path.of(buffer.getFilePath()); Path root = file.getParent();
        long size = Files.size(file); int count = size >= 1024 * 1024 ? 1000 : size >= 100 * 1024 ? 100 : 20;
        Path notes = Files.createDirectories(root.resolve("notes"));
        for (int i = 0; i < count; i++) {
            StringBuilder text = new StringBuilder("# Note " + i + "\n\n" + "prose ".repeat(170) + "\n");
            for (int link = 0; link < 8; link++) text.append("[[note-").append((i + link + 1) % count).append("]] ");
            Files.writeString(notes.resolve("note-" + i + ".md"), text);
        }
        Path target = notes.resolve("note-0.md").toRealPath();
        for (int i = -warmups; i < samples; i++) {
            uiText("index_inactive_typing", () -> typed('x'), i >= 0);
            uiText("index_inactive_undo", () -> buffer.getUndoManager().undo(), i >= 0);
        }
        edt(() -> { buffer.setModified(false); return null; }); settle("before_project_index");
        long cold = System.nanoTime();
        double requestMs = edt(() -> {
            editor.workspaceRoots.add(root); long start = System.nanoTime(); editor.markdownLinks().request(root); return (System.nanoTime() - start) / 1e6;
        });
        out("MD_SAMPLE ", Map.of("operation", "index_request", "state", "OK", "edt_ms", requestMs));
        int overlapping = 0;
        // Paint callbacks run between worker passes. No test lock or artificial worker sleep creates overlap.
        for (int i = 0; i < samples; i++) {
            if (edt(() -> editor.markdownLinks().diagnostics().running() > 0)) overlapping++;
            uiText("index_active_typing", () -> typed('x'), true);
            uiText("index_active_undo", () -> buffer.getUndoManager().undo(), true);
        }
        await(() -> editor.markdownLinks().diagnostics().running() == 0 && editor.markdownLinks().diagnostics().pending() == 0, 60);
        out("MD_SAMPLE ", Map.of("operation", "index_convergence_with_typing", "state", "OK", "observed_ms", (System.nanoTime() - cold) / 1e6));
        out("MD_META ", Map.of("operation", "project_workload", "files", count + 1, "typing_samples_overlapping_worker", overlapping,
            "status", edt(() -> editor.markdownLinks().backlinks(root, target).state().name()),
            "metrics", edt(() -> editor.markdownLinks().metrics(root).orElseThrow().toString()), "diagnostics", edt(() -> editor.markdownLinks().diagnostics().toString())));
        settle("retained_project_index");
        for (int i = -warmups; i < samples; i++) {
            boolean measured = i >= 0;
            edt(() -> {
                long start = System.nanoTime(); var outgoing = editor.markdownLinks().outgoing(root, target); double outMs = (System.nanoTime() - start) / 1e6;
                start = System.nanoTime(); var incoming = editor.markdownLinks().backlinks(root, target); double inMs = (System.nanoTime() - start) / 1e6;
                if (measured) {
                    out("MD_SAMPLE ", Map.of("operation", "outgoing_query", "state", "OK", "edt_ms", outMs, "edges", outgoing.edges().size()));
                    out("MD_SAMPLE ", Map.of("operation", "backlink_query", "state", "OK", "edt_ms", inMs, "edges", incoming.edges().size()));
                }
                return null;
            });
        }
        long overlayStart = System.nanoTime();
        edt(() -> {
            var undo = (BoundedUndoManager) buffer.getUndoManager(); undo.beginCompoundEdit();
            try { editor.writingArea.replaceRange("[[note-0]]\n", 0, 11); } finally { undo.endCompoundEdit(); }
            return null;
        });
        await(() -> editor.markdownLinks().backlinks(root, target).edges().stream().anyMatch(e -> e.occurrence().source().document().isPresent()), 30);
        out("MD_SAMPLE ", Map.of("operation", "unsaved_overlay_convergence", "state", "OK", "observed_ms", (System.nanoTime() - overlayStart) / 1e6));
        edt(() -> { buffer.getUndoManager().undo(); return null; });
        await(() -> editor.markdownLinks().diagnostics().running() == 0 && editor.markdownLinks().diagnostics().pending() == 0, 30);
        WeakReference<MarkdownProjectLinks> released = edt(() -> {
            var service = editor.markdownLinks(); var weak = new WeakReference<>(service); editor.disposeMarkdownPreviews(); return weak;
        });
        settle("after_project_disposal"); out("MD_META ", Map.of("operation", "project_service_collected", "collected", released.get() == null));
        builderMeasurements(root, target, Math.min(20, samples));
    }
    private static void builderMeasurements(Path root, Path target, int samples) throws Exception {
        var threads = (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean(); long id = Thread.currentThread().threadId();
        MarkdownLinkIndex.Snapshot previous = null;
        for (int i = 0; i < 3; i++) {
            long bytes = threads.getThreadAllocatedBytes(id), start = System.nanoTime();
            previous = MarkdownLinkIndex.build(root, List.of(root), null, List.of(), Set.of(), () -> false);
            out("MD_SAMPLE ", Map.of("operation", "cold_project_builder", "state", "OK", "worker_ms", (System.nanoTime() - start) / 1e6,
                "allocated_bytes", threads.getThreadAllocatedBytes(id) - bytes, "parsed", previous.metrics().parsed()));
        }
        for (int i = 0; i < samples; i++) {
            long start = System.nanoTime(); previous = MarkdownLinkIndex.build(root, List.of(root), previous, List.of(), Set.of(), () -> false);
            out("MD_SAMPLE ", Map.of("operation", "unchanged_project_refresh", "state", "OK", "worker_ms", (System.nanoTime() - start) / 1e6, "parsed", previous.metrics().parsed()));
        }
        for (int i = 0; i < samples; i++) {
            var source = new MarkdownSemanticSnapshot.Source(new DocumentWorkspace.Id(99999), i, VersionedTextSnapshot.of("# Unsaved\n[[note-1]]"));
            var overlay = new MarkdownLinkIndex.Overlay(target, source, Optional.empty());
            long start = System.nanoTime(); previous = MarkdownLinkIndex.build(root, List.of(root), previous, List.of(overlay), Set.of(), () -> false);
            out("MD_SAMPLE ", Map.of("operation", "single_overlay_refresh", "state", "OK", "worker_ms", (System.nanoTime() - start) / 1e6, "parsed", previous.metrics().parsed()));
        }
        for (int i = 0; i < samples; i++) {
            Files.writeString(target, "# Changed " + i + "\n[[note-1]]\n");
            long start = System.nanoTime(); previous = MarkdownLinkIndex.build(root, List.of(root), previous, List.of(), Set.of(target), () -> false);
            out("MD_SAMPLE ", Map.of("operation", "single_file_refresh", "state", "OK", "worker_ms", (System.nanoTime() - start) / 1e6, "parsed", previous.metrics().parsed()));
        }
    }
    private static void settle(String phase) throws Exception {
        for (int i = 0; i < 3; i++) { System.gc(); Thread.sleep(60); }
        memory(phase);
        var process = new ProcessBuilder("ps", "-o", "rss=", "-p", Long.toString(ProcessHandle.current().pid())).start();
        String output = new String(process.getInputStream().readAllBytes()).strip(); process.waitFor();
        out("MD_META ", Map.of("operation", phase + "_rss", "rss_mib", Long.parseLong(output) / 1024d));
    }
}
