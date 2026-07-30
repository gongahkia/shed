package shed;

import com.jediterm.terminal.TextStyle;
import com.jediterm.terminal.TerminalColor;
import com.jediterm.terminal.emulator.ColorPalette;
import com.jediterm.terminal.emulator.ColorPaletteImpl;
import com.jediterm.terminal.ui.JediTermWidget;
import com.jediterm.terminal.ui.settings.DefaultSettingsProvider;
import com.pty4j.PtyProcess;
import com.pty4j.PtyProcessBuilder;
import java.awt.Color;
import java.awt.Font;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import javax.swing.JComponent;

final class PtyTerminalPane implements AutoCloseable {
    private static final int INITIAL_COLUMNS = 100;
    private static final int INITIAL_ROWS = 28;

    private final JediTermWidget widget;
    private final PtyTerminalConnector connector;
    private final PtyProcess process;
    private final File workingDirectory;
    private boolean closed;

    private PtyTerminalPane(JediTermWidget widget, PtyTerminalConnector connector, PtyProcess process, File workingDirectory) {
        this.widget = widget;
        this.connector = connector;
        this.process = process;
        this.workingDirectory = workingDirectory;
    }

    static PtyTerminalPane open(File workingDirectory, ConfigManager configManager, Font editorFont) throws IOException {
        List<String> command = ShellCommand.interactiveCommand();
        File cwd = normalizeDirectory(workingDirectory);
        Map<String, String> env = new HashMap<>(System.getenv());
        env.put("TERM", "xterm-256color");
        env.put("COLORTERM", "truecolor");
        env.put("SHELL", command.get(0));

        PtyProcess process = new PtyProcessBuilder(command.toArray(new String[0]))
            .setDirectory(cwd.getAbsolutePath())
            .setEnvironment(env)
            .setInitialColumns(INITIAL_COLUMNS)
            .setInitialRows(INITIAL_ROWS)
            .setRedirectErrorStream(true)
            .start();

        PtyTerminalConnector connector = new PtyTerminalConnector(process, command, StandardCharsets.UTF_8);
        JediTermWidget widget = new JediTermWidget(INITIAL_COLUMNS, INITIAL_ROWS, new ShedTerminalSettingsProvider(configManager, editorFont));
        widget.setTtyConnector(connector);
        widget.start();
        return new PtyTerminalPane(widget, connector, process, cwd);
    }

    JComponent getComponent() {
        return widget.getComponent();
    }

    JComponent getInputComponent() {
        return widget.getTerminalPanel();
    }

    void requestFocusInWindow() {
        widget.requestFocusInWindow();
    }

    File getWorkingDirectory() {
        return workingDirectory;
    }

    void onExit(Runnable callback) {
        process.onExit().thenRun(callback);
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        widget.stop();
        connector.close();
        if (process.isRunning()) {
            process.destroy();
        }
        widget.close();
    }

    private static File normalizeDirectory(File directory) {
        File candidate = directory == null ? new File(".") : directory;
        try {
            return candidate.getCanonicalFile();
        } catch (IOException ignored) {
            return candidate.getAbsoluteFile();
        }
    }

    static final class ShedTerminalSettingsProvider extends DefaultSettingsProvider {
        private final Font font;
        private final float fontSize;
        private final TerminalColor foreground;
        private final TerminalColor background;
        private final TextStyle defaultStyle;
        private final TextStyle selectionColor;
        private final ColorPalette colorPalette;

        ShedTerminalSettingsProvider(ConfigManager configManager, Font editorFont) {
            int size = configManager == null ? 14 : configManager.getFontSize();
            String family = configManager == null ? "Monospaced" : configManager.getFontFamily();
            Font resolvedFont = editorFont == null ? null : editorFont.deriveFont(Font.PLAIN, (float) size);
            this.font = resolvedFont == null
                ? new Font(family == null || family.isBlank() ? "Monospaced" : family, Font.PLAIN, size)
                : resolvedFont;
            this.fontSize = size;
            Color fg = configManager == null ? Color.WHITE : configManager.getEditorForeground();
            Color bg = configManager == null ? Color.BLACK : configManager.getNormalColor();
            Color selection = configManager == null ? new Color(64, 96, 160) : configManager.getSelectionColor();
            this.foreground = toTerminalColor(fg);
            this.background = toTerminalColor(bg);
            this.defaultStyle = new TextStyle(foreground, background);
            this.selectionColor = new TextStyle(null, toTerminalColor(selection));
            this.colorPalette = new ShedTerminalColorPalette(configManager);
        }

        @Override
        public Font getTerminalFont() {
            return font;
        }

        @Override
        public float getTerminalFontSize() {
            return fontSize;
        }

        @Override
        public TerminalColor getDefaultForeground() {
            return foreground;
        }

        @Override
        public TerminalColor getDefaultBackground() {
            return background;
        }

        @Override
        public TextStyle getDefaultStyle() {
            return defaultStyle;
        }

        @Override
        public ColorPalette getTerminalColorPalette() {
            return colorPalette;
        }

        @Override
        public TextStyle getSelectionColor() {
            return selectionColor;
        }

        @Override
        public boolean audibleBell() {
            return false;
        }

        private static TerminalColor toTerminalColor(Color color) {
            return TerminalColor.rgb(color.getRed(), color.getGreen(), color.getBlue());
        }
    }

    private static final class ShedTerminalColorPalette extends ColorPalette {
        private final com.jediterm.core.Color[] colors;

        private ShedTerminalColorPalette(ConfigManager configManager) {
            Color bg = configManager == null ? Color.BLACK : configManager.getNormalColor();
            Color fg = configManager == null ? Color.WHITE : configManager.getEditorForeground();
            Color accent = configManager == null ? new Color(97, 175, 239) : configManager.getCaretColor();
            Color string = configManager == null ? new Color(152, 195, 121) : configManager.getSyntaxStringColor();
            Color command = configManager == null ? new Color(229, 192, 123) : configManager.getCommandColor();
            Color replace = configManager == null ? new Color(224, 108, 117) : configManager.getReplaceColor();
            Color visual = configManager == null ? new Color(198, 120, 221) : configManager.getVisualColor();
            Color function = configManager == null ? new Color(86, 182, 194) : configManager.getSyntaxFunctionColor();
            this.colors = new com.jediterm.core.Color[] {
                toCoreColor(bg),
                toCoreColor(replace),
                toCoreColor(string),
                toCoreColor(command),
                toCoreColor(accent),
                toCoreColor(visual),
                toCoreColor(function),
                toCoreColor(fg),
                toCoreColor(brighten(bg)),
                toCoreColor(brighten(replace)),
                toCoreColor(brighten(string)),
                toCoreColor(brighten(command)),
                toCoreColor(brighten(accent)),
                toCoreColor(brighten(visual)),
                toCoreColor(brighten(function)),
                toCoreColor(Color.WHITE)
            };
        }

        @Override
        protected com.jediterm.core.Color getForegroundByColorIndex(int index) {
            return colorForIndex(index, true);
        }

        @Override
        protected com.jediterm.core.Color getBackgroundByColorIndex(int index) {
            return colorForIndex(index, false);
        }

        private com.jediterm.core.Color colorForIndex(int index, boolean foreground) {
            if (index >= 0 && index < colors.length) {
                return colors[index];
            }
            TerminalColor terminalColor = TerminalColor.index(index);
            return foreground
                ? ColorPaletteImpl.XTERM_PALETTE.getForeground(terminalColor)
                : ColorPaletteImpl.XTERM_PALETTE.getBackground(terminalColor);
        }

        private static Color brighten(Color color) {
            return blend(color, Color.WHITE, 0.30);
        }

        private static Color blend(Color base, Color overlay, double ratio) {
            double inverse = 1.0 - ratio;
            int r = (int) Math.round(base.getRed() * inverse + overlay.getRed() * ratio);
            int g = (int) Math.round(base.getGreen() * inverse + overlay.getGreen() * ratio);
            int b = (int) Math.round(base.getBlue() * inverse + overlay.getBlue() * ratio);
            return new Color(clamp(r), clamp(g), clamp(b));
        }

        private static int clamp(int value) {
            return Math.max(0, Math.min(255, value));
        }

        private static com.jediterm.core.Color toCoreColor(Color color) {
            return new com.jediterm.core.Color(color.getRed(), color.getGreen(), color.getBlue());
        }
    }
}
