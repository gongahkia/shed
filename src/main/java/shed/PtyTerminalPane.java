package shed;

import com.jediterm.terminal.TextStyle;
import com.jediterm.terminal.TerminalColor;
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
    private boolean closed;

    private PtyTerminalPane(JediTermWidget widget, PtyTerminalConnector connector, PtyProcess process) {
        this.widget = widget;
        this.connector = connector;
        this.process = process;
    }

    static PtyTerminalPane open(File workingDirectory, ConfigManager configManager) throws IOException {
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
        JediTermWidget widget = new JediTermWidget(INITIAL_COLUMNS, INITIAL_ROWS, new ShedTerminalSettingsProvider(configManager));
        widget.setTtyConnector(connector);
        widget.start();
        return new PtyTerminalPane(widget, connector, process);
    }

    JComponent getComponent() {
        return widget.getComponent();
    }

    void requestFocusInWindow() {
        widget.requestFocusInWindow();
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

    private static final class ShedTerminalSettingsProvider extends DefaultSettingsProvider {
        private final Font font;
        private final float fontSize;
        private final TextStyle selectionColor;

        private ShedTerminalSettingsProvider(ConfigManager configManager) {
            int size = configManager == null ? 14 : configManager.getFontSize();
            String family = configManager == null ? "Monospaced" : configManager.getFontFamily();
            this.font = new Font(family == null || family.isBlank() ? "Monospaced" : family, Font.PLAIN, size);
            this.fontSize = size;
            Color selection = configManager == null ? new Color(64, 96, 160) : configManager.getSelectionColor();
            this.selectionColor = new TextStyle(null, toTerminalColor(selection));
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
}
