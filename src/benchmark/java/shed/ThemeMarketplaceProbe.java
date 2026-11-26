package shed;

import java.awt.Window;
import java.awt.Component;
import java.awt.Container;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.FutureTask;
import java.util.concurrent.atomic.AtomicReference;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import javax.imageio.ImageIO;
import javax.swing.JButton;
import javax.swing.JList;
import javax.swing.JOptionPane;
import javax.swing.JTabbedPane;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;

/** Opt-in owned-window check. The deterministic desktop fixture never uses the public network. */
public final class ThemeMarketplaceProbe {
    private static final AtomicReference<Throwable> uncaught = new AtomicReference<>();
    private static final List<String> checks = new ArrayList<>();
    private static Texteditor editor;
    private static ThemeGalleryDialog gallery;

    private static <T> T edt(Callable<T> work) throws Exception {
        FutureTask<T> future = new FutureTask<>(work);
        SwingUtilities.invokeAndWait(future);
        return future.get();
    }
    private static void await(Callable<Boolean> ready) throws Exception {
        long deadline = System.nanoTime() + 10_000_000_000L;
        while (!edt(ready)) {
            if (System.nanoTime() > deadline) throw new AssertionError("Gallery state timed out");
            Thread.sleep(10);
        }
        if (uncaught.get() != null) throw new AssertionError("Uncaught desktop error", uncaught.get());
    }
    private static Object field(String name) throws Exception {
        Field field = ThemeGalleryDialog.class.getDeclaredField(name);
        field.setAccessible(true);
        return field.get(gallery);
    }
    private static void click(String name) throws Exception {
        edt(() -> { JButton button = (JButton) field(name); if (!button.isEnabled()) throw new AssertionError("Disabled " + name); button.doClick(0); return null; });
        await(() -> (int) field("jobId") < 0);
    }
    private static void capture(Path work, String name) throws Exception {
        BufferedImage image = edt(() -> {
            gallery.validate();
            var root = gallery.getRootPane();
            BufferedImage picture = new BufferedImage(root.getWidth(), root.getHeight(), BufferedImage.TYPE_INT_RGB);
            var graphics = picture.createGraphics();
            try { root.paint(graphics); } finally { graphics.dispose(); }
            return picture;
        });
        ImageIO.write(image, "png", work.resolve(name + ".png").toFile());
    }

    private static JOptionPane confirmation(Component root) {
        if (root instanceof JOptionPane pane) return pane;
        if (root instanceof Container container) for (Component child : container.getComponents()) {
            JOptionPane pane = confirmation(child);
            if (pane != null) return pane;
        }
        return null;
    }

    private static byte[] archive() throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        Map<String, String> files = Map.of("extension/package.json", """
            {"publisher":"ShedFixture","name":"palette","version":"1.0.0","contributes":{"themes":[
              {"label":"Harbor Dark","path":"themes/dark.json","uiTheme":"vs-dark"},
              {"label":"Harbor Light","path":"themes/light.json","uiTheme":"vs"}]}}
            """, "extension/themes/dark.json", """
            {"colors":{"editor.background":"#14242d","editor.foreground":"#e8f0f2","focusBorder":"#65c9c3",
              "statusBar.background":"#1f3540","sideBar.background":"#1a2e38"},
             "tokenColors":[{"scope":"string","settings":{"foreground":"#b1d889"}}]}
            """, "extension/themes/light.json", """
            {"colors":{"editor.background":"#f4f9fa","editor.foreground":"#213c47","focusBorder":"#137c80"}}
            """, "extension/LICENSE", "Synthetic fixture license text. No third-party theme redistributed.");
        try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
            for (var entry : files.entrySet()) {
                zip.putNextEntry(new ZipEntry(entry.getKey()));
                zip.write(entry.getValue().getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
            }
        }
        return bytes.toByteArray();
    }

    private static void desktop(Path work, Path project) throws Exception {
        byte[] archive = archive();
        List<String> requests = java.util.Collections.synchronizedList(new ArrayList<>());
        OpenVsxThemeService service = new OpenVsxThemeService((uri, limit, token) -> {
            if (SwingUtilities.isEventDispatchThread()) throw new AssertionError("Registry request on EDT");
            requests.add(uri.getPath());
            if (uri.toString().endsWith(".vsix")) return archive;
            if (uri.toString().endsWith(".sha256")) return OpenVsxThemeService.sha256(archive).getBytes(StandardCharsets.US_ASCII);
            String metadata = """
                {"namespace":"ShedFixture","name":"palette","version":"1.0.0","displayName":"Harbor Colors",
                "description":"Disposable desktop palette fixture","categories":["Themes"],"license":"MIT",
                "files":{"download":"https://open-vsx.org/api/ShedFixture/palette/1.0.0/file/theme.vsix",
                "sha256":"https://open-vsx.org/api/ShedFixture/palette/1.0.0/file/theme.sha256"}}
                """;
            return (uri.getPath().endsWith("search") ? "{\"extensions\":[" + metadata + "]}" : metadata).getBytes(StandardCharsets.UTF_8);
        });
        editor = edt(() -> new Texteditor(new String[]{project.resolve("Demo.java").toString()}));
        await(() -> !editor.stagedStartupPending && editor.getCurrentBuffer() != null);
        var buffer = edt(editor::getCurrentBuffer);
        String source = edt(buffer::getContent);
        long revision = edt(buffer::documentRevision);
        boolean dirty = edt(buffer::isModified);
        Object undo = edt(buffer::getUndoManager);
        String font = edt(() -> editor.configManager.getFontFamily());
        gallery = edt(() -> { var dialog = new ThemeGalleryDialog(editor, service); dialog.setVisible(true); return dialog; });
        await(() -> (int) field("jobId") < 0);
        if (!requests.isEmpty()) throw new AssertionError("Gallery contacted registry before Search");
        checks.add("no-network-before-search");
        edt(() -> { ((JTabbedPane) field("sources")).setSelectedIndex(1); ((JTextField) field("query")).setText("Harbor"); return null; });
        click("search");
        edt(() -> { ((JList<?>) field("discoveries")).setSelectedIndex(0); return null; });
        click("download");
        if (!edt(() -> editor.configManager.getThemeId()).equals("shed-light")) throw new AssertionError("Download applied theme");
        capture(work, "discover");
        click("install");
        edt(() -> {
            JList<?> list = (JList<?>) field("themes");
            int selected = list.getSelectedIndex();
            if (selected < 0 || !list.getVisibleRect().intersects(list.getCellBounds(selected, selected)))
                throw new AssertionError("Installed theme selection is outside the viewport");
            return null;
        });
        checks.add("installed-selection-visible");
        String importedId = edt(() -> editor.configManager.getThemeIds().get(56));
        click("previewInApp");
        if (!edt(() -> editor.configManager.getThemeId()).equals(importedId)) throw new AssertionError("Preview not applied");
        capture(work, "preview-dark");
        click("previewInApp");
        if (!edt(() -> editor.configManager.getThemeId()).equals("shed-light")) throw new AssertionError("Cancel did not restore");
        checks.add("search-download-install-preview-cancel");
        click("applyAndSave");
        if (!edt(() -> new ConfigManager().getThemeId()).equals(importedId)) throw new AssertionError("Offline config did not restore imported theme");
        if (edt(() -> ((JButton) field("remove")).isEnabled())) throw new AssertionError("Active package removal offered");
        checks.add("apply-save-offline-reload");
        edt(() -> { editor.configManager.setAndPersist("theme", "shed-light"); editor.applyThemeColors(); gallery.dispose(); return null; });
        gallery = edt(() -> { var dialog = new ThemeGalleryDialog(editor, service); dialog.setSize(900, 650); dialog.setVisible(true); return dialog; });
        await(() -> (int) field("jobId") < 0);
        if (!edt(() -> editor.configManager.getThemeIds().size()).equals(58)) throw new AssertionError("Installed variants lost");
        capture(work, "installed-narrow");
        edt(() -> {
            JList<?> list = (JList<?>) field("themes");
            for (int index = 0; index < list.getModel().getSize(); index++) {
                if (((ThemePreview) list.getModel().getElementAt(index)).id().equals(importedId)) list.setSelectedIndex(index);
            }
            return null;
        });
        SwingUtilities.invokeLater(() -> {
            try { ((JButton) field("remove")).doClick(0); }
            catch (Exception error) { throw new RuntimeException(error); }
        });
        await(() -> {
            for (Window window : gallery.getOwnedWindows()) {
                JOptionPane pane = confirmation(window);
                if (window.isShowing() && pane != null) { pane.setValue(JOptionPane.OK_OPTION); return true; }
            }
            return false;
        });
        await(() -> (int) field("jobId") < 0 && editor.configManager.getThemeIds().size() == 56);
        if (edt(() -> new ConfigManager().getThemeIds().size()) != 56) throw new AssertionError("Remove did not persist");
        checks.add("remove-package-offline");
        edt(() -> {
            if (!source.equals(buffer.getContent()) || revision != buffer.documentRevision() || dirty != buffer.isModified()
                || undo != buffer.getUndoManager() || !font.equals(editor.configManager.getFontFamily()))
                throw new AssertionError("Theme flow changed source, revision, dirty, Undo or font");
            gallery.dispose();
            return null;
        });
        checks.add("source-undo-font-unchanged");
        // Hold a registry request until the dialog closes; cancellation must retire the late completion.
        OpenVsxThemeService slow = new OpenVsxThemeService((uri, limit, token) -> {
            while (!token.isCancelled()) {
                try { Thread.sleep(5); } catch (InterruptedException error) { Thread.currentThread().interrupt(); break; }
            }
            OpenVsxThemeService.checkCancelled(token);
            throw new AssertionError("Cancelled request returned");
        });
        gallery = edt(() -> { var dialog = new ThemeGalleryDialog(editor, slow); dialog.setVisible(true); return dialog; });
        await(() -> (int) field("jobId") < 0);
        edt(() -> { ((JTabbedPane) field("sources")).setSelectedIndex(1); ((JButton) field("search")).doClick(0); gallery.dispose(); return null; });
        await(() -> editor.asyncJobService.list().stream().noneMatch(job -> job.getStatus() == AsyncJobService.Status.RUNNING));
        checks.add("close-cancels-request-no-late-ui-install");
    }

    public static void main(String[] args) throws Exception {
        Thread.setDefaultUncaughtExceptionHandler((thread, error) -> { uncaught.compareAndSet(null, error); System.err.println("UNCAUGHT_DESKTOP_EXCEPTION"); error.printStackTrace(); });
        Path work = Path.of(args[0]).toRealPath(), project = Path.of(args[1]).toRealPath();
        Path fixture = Path.of(System.getenv("SHED_PORTABLE_FIXTURE_ROOT")).toRealPath();
        if (!work.startsWith(fixture.resolve("run")) || !project.startsWith(fixture.resolve("run"))
            || !Path.of(System.getProperty("user.home")).toRealPath().startsWith(fixture)) throw new IllegalArgumentException("Disposable fixture required");
        int exit = 1;
        try {
            if (args.length == 3 && args[2].equals("--registry-smoke")) {
                OpenVsxThemeService registry = new OpenVsxThemeService();
                long start = System.nanoTime();
                var entries = registry.search("catppuccin", null);
                var selected = entries.stream().filter(e -> e.publisher().equals("Catppuccin") && e.name().equals("catppuccin-vsc")).findFirst().orElseThrow();
                var downloaded = registry.download(selected, null);
                Files.writeString(work.resolve("registry.json"), MiniJson.stringify(Map.of("package", selected.id(), "version", selected.version(),
                    "sha256", downloaded.sha256(), "variants", downloaded.themes().size(), "license", downloaded.themes().getFirst().license(),
                    "elapsed_ms", (System.nanoTime() - start) / 1e6)));
                checks.add("real-registry-search-checksum-translation-no-install");
            } else desktop(work, project);
            exit = 0;
        } catch (Throwable error) { error.printStackTrace(); }
        finally {
            if (editor != null) edt(() -> {
                for (Window window : editor.getOwnedWindows()) window.dispose();
                editor.shutdownRecoveryJournalScheduling();
                editor.shutdownSyntaxUi();
                editor.shutdownLspScheduling();
                editor.asyncJobService.shutdownNow();
                editor.dispose();
                return null;
            });
            edt(() -> null);
            if (uncaught.get() != null) exit = 1;
            Files.writeString(work.resolve("result.json"), MiniJson.stringify(Map.of("state", exit == 0 ? "PASS" : "FAIL", "checks", checks)));
        }
        System.exit(exit);
    }
}
