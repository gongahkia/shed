package shed;

import static org.junit.jupiter.api.Assertions.*;
import java.awt.GraphicsEnvironment;
import java.awt.event.KeyEvent;
import java.awt.image.BufferedImage;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import javax.imageio.ImageIO;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;

/** Explicitly opens Shed. Synthetic project/profile only; captures the application's own rendered pixels. */
@EnabledIfSystemProperty(named = "shed.ai.native", matches = "true")
class FocusedAiNativeAcceptanceTest {
    @TempDir Path temporary;

    @Test void actualEditorAcceptsRealSuggestionsAndRendersWrappedMultilinePreviews() throws Exception {
        assertFalse(GraphicsEnvironment.isHeadless(), "Native acceptance requires -Djava.awt.headless=false");
        Path home = Files.createDirectory(temporary.resolve("home")); Files.createDirectory(home.resolve(".shed"));
        Files.writeString(home.resolve(".shed/config.toml"), "schema_version = 1\n\"font.family\" = \"Monospaced\"\n\"ui.font.family\" = \"Dialog\"\n\"keymap.profile\" = \"plain\"\n");
        String original = "public static int add(int a, int b) {\n    return ;\n}\n";
        Path file = Files.writeString(temporary.resolve("completion.txt"), original);
        String previousHome = System.getProperty("user.home");
        Texteditor editor = edt(() -> {
            System.setProperty("user.home", home.toString());
            try { return new Texteditor(new String[] {file.toString()}); }
            finally { System.setProperty("user.home", previousHome); }
        });
        var server = com.sun.net.httpserver.HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        String multiline = "a + b\n\t+ anotherValueWithALongName + moreValues + moreValues + moreValues\n\t+ finalValue";
        server.createContext("/api/generate", exchange -> {
            exchange.getRequestBody().readAllBytes();
            byte[] body = MiniJson.stringify(Map.of("response", multiline, "done", true)).getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length); exchange.getResponseBody().write(body); exchange.close();
        }); server.start();
        try {
            await(() -> editor.getCurrentBuffer() != null && file.toString().equals(editor.getCurrentBuffer().getFilePath()) && !editor.stagedStartupPending, 30);
            edt(() -> {
                editor.configManager.set("ai.completion.enabled", "true");
                editor.configManager.set("ai.completion.model", System.getProperty("shed.acceptance.ollama.model", "qwen2.5-coder:0.5b-base"));
                editor.configManager.set("ai.completion.endpoint", System.getProperty("shed.acceptance.ollama.endpoint", "http://127.0.0.1:11434/api/generate"));
                editor.configManager.set("ai.completion.timeout.ms", "30000");
                editor.setSize(900, 650); editor.toFront(); editor.setMode(EditorMode.INSERT);
                editor.writingArea.setCaretPosition(original.indexOf(';')); editor.writingArea.requestFocusInWindow(); return null;
            });
            await(() -> editor.writingArea.isFocusOwner(), 15);
            edt(() -> { editor.requestAiCompletion(); return null; }); await(() -> editor.hasAiCompletion(editor.writingArea), 40);
            capture(editor, "native-real-completion");
            edt(() -> {
                assertEquals(original, editor.writingArea.getText()); press(editor, KeyEvent.VK_TAB);
                assertTrue(editor.writingArea.getText().replaceAll("\\s", "").contains("returna+b;"));
                editor.inputController.performMenuAction(PlainKeymap.Action.UNDO); assertEquals(original, editor.writingArea.getText());
                editor.configManager.set("ai.completion.endpoint", "http://127.0.0.1:" + server.getAddress().getPort() + "/api/generate");
                editor.writingArea.setCaretPosition(original.indexOf(';')); editor.writingArea.setLineWrap(true);
                editor.writingArea.setWrapStyleWord(true); editor.writingArea.setTabSize(4);
                editor.requestAiCompletion(); return null;
            });
            await(() -> editor.hasAiCompletion(editor.writingArea), 10);
            for (int width : List.of(900, 580)) {
                edt(() -> { editor.setSize(width, 500); editor.validate(); return null; });
                capture(editor, "native-multiline-" + width);
                edt(() -> {
                    assertTrue(editor.writingArea.isFocusOwner(), "resizing must retain editor focus");
                    assertTrue(editor.hasAiCompletion(editor.writingArea), "resizing must retain the preview");
                    assertEquals(original, editor.writingArea.getText());
                    var lastLine = editor.writingArea.modelToView2D(original.lastIndexOf('}'));
                    var caret = editor.writingArea.modelToView2D(original.indexOf(';'));
                    assertTrue(lastLine.getY() >= caret.getY() + 3 * editor.writingArea.getFontMetrics(editor.writingArea.getFont()).getHeight());
                    return null;
                });
            }
            edt(() -> {
                press(editor, KeyEvent.VK_ESCAPE); assertFalse(editor.hasAiCompletion(editor.writingArea)); assertEquals(original, editor.writingArea.getText());
                editor.writingArea.setCaretPosition(original.indexOf("    return")); press(editor, KeyEvent.VK_TAB);
                assertNotEquals(original, editor.writingArea.getText(), "ordinary Tab must still indent after dismissal");
                return null;
            });
            Path reports = Path.of("target/ai-acceptance"); Files.createDirectories(reports);
            Files.writeString(reports.resolve("native-timings.txt"), editor.perfService.report());
            assertEquals(original, Files.readString(file));
        } finally {
            capture(editor, "native-latest");
            System.out.println(editor.perfService.report());
            server.stop(0);
            edt(() -> {
                for (var timer : new javax.swing.Timer[] {editor.messageResetTimer, editor.externalChangeTimer, editor.recoverySnapshotTimer, editor.diagnosticRefreshTimer}) if (timer != null) timer.stop();
                if (editor.fileWatcherService != null) editor.fileWatcherService.stop();
                editor.shutdownSyntaxUi(); editor.shutdownLspScheduling(); editor.asyncJobService.shutdownNow();
                if (editor.backupScheduler != null) editor.backupScheduler.close(); editor.dispose(); return null;
            });
        }
    }
    private static void press(Texteditor editor, int key) {
        var event = new KeyEvent(editor.writingArea, KeyEvent.KEY_PRESSED, System.currentTimeMillis(), 0, key, KeyEvent.CHAR_UNDEFINED);
        editor.writingArea.dispatchEvent(event); assertTrue(event.isConsumed(), "editor should handle the requested key");
    }
    private static void capture(Texteditor editor, String name) throws Exception {
        var image = edt(() -> {
            editor.validate(); var pixels = new BufferedImage(editor.getWidth(), editor.getHeight(), BufferedImage.TYPE_INT_RGB);
            var graphics = pixels.createGraphics(); editor.paintAll(graphics); graphics.dispose(); return pixels;
        });
        Path directory = Path.of("target/ai-acceptance"); Files.createDirectories(directory);
        ImageIO.write(image, "png", directory.resolve(name + ".png").toFile());
    }
    private static void await(BooleanSupplier condition, int seconds) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds);
        while (System.nanoTime() < deadline) { if (edt(condition::getAsBoolean)) return; Thread.sleep(50); }
        fail("Native acceptance condition timed out");
    }
    private static <T> T edt(Callable<T> action) throws Exception {
        var task = new java.util.concurrent.FutureTask<>(action); SwingUtilities.invokeAndWait(task); return task.get();
    }
}
