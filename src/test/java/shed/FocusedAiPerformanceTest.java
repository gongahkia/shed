package shed;

import static org.junit.jupiter.api.Assertions.*;
import java.awt.Font;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import javax.swing.JTextArea;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

/** Opt-in repeatable local measurements, without a display or model/network variability. */
@EnabledIfSystemProperty(named = "shed.ai.benchmark", matches = "true")
class FocusedAiPerformanceTest {
    @Test void measurePreviewPreparationPaintingAndDismissalAcrossBufferSizes() throws Exception {
        var perf = new PerfService();
        SwingUtilities.invokeAndWait(() -> {
            try {
                for (int size : new int[] {10_000, 100_000, 1_000_000}) {
                    for (boolean wrap : new boolean[] {false, true}) {
                        String line = "\tString value = anotherValue + longName + moreValues + finalValue;\n";
                        String text = line.repeat(size / line.length() + 1).substring(0, size);
                        var area = new JTextArea(text);
                        area.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 14)); area.setTabSize(4);
                        area.setLineWrap(wrap); area.setWrapStyleWord(true); area.setSize(580, 600);
                        int anchor = size / 2;
                        var preview = new InlineCompletionDisplay(area);
                        var pixels = new BufferedImage(580, 600, BufferedImage.TYPE_INT_RGB);
                        String name = "chars=" + size + ",wrap=" + wrap;
                        for (int pass = 0; pass < 13; pass++) {
                            long start = System.nanoTime();
                            preview.show(anchor, "suggestedValue\n\t+ moreWordsAndValues + anotherValue\n\t+ finalValue");
                            area.setSize(580, Math.max(600, area.getPreferredSize().height));
                            var caret = area.modelToView2D(anchor);
                            if (pass >= 3) perf.recordDuration("preview.prepare." + name, start, "show, extent and caret geometry");
                            start = System.nanoTime();
                            var graphics = pixels.createGraphics();
                            try {
                                int top = Math.max(0, (int) caret.getY() - 250);
                                graphics.translate(0, -top); graphics.setClip(0, top, 580, 600); area.paint(graphics);
                            } finally { graphics.dispose(); }
                            if (pass >= 3) perf.recordDuration("preview.paint." + name, start, "600px viewport");
                            start = System.nanoTime(); preview.clear();
                            area.modelToView2D(anchor);
                            if (pass >= 3) perf.recordDuration("preview.dismiss." + name, start, "restore source layout");
                            assertEquals(text, area.getText());
                        }
                    }
                }
            } catch (Exception error) { throw new RuntimeException(error); }
        });
        Path directory = Path.of("target/ai-acceptance"); Files.createDirectories(directory);
        String report = "Java " + System.getProperty("java.version") + ", " + System.getProperty("os.name") + " "
            + System.getProperty("os.arch") + "; 3 warmup + 10 measured iterations per case\n" + perf.report();
        Files.writeString(directory.resolve("preview-performance.txt"), report); System.out.println(report);
    }
}
