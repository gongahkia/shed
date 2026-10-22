package shed;

import java.awt.BasicStroke;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Rectangle;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.event.FocusAdapter;
import java.awt.event.FocusEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import javax.swing.AbstractAction;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JProgressBar;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTextArea;
import javax.swing.KeyStroke;
import javax.swing.SwingConstants;

final class GitGraphDialog extends JDialog {
    interface Loader {
        GitGraphModel.Snapshot load(AsyncJobService.JobToken token);
    }

    private final Texteditor editor;
    private final Loader loader;
    private final JLabel repository = new JLabel("Repository: resolving…");
    private final JLabel state = new JLabel("Loading local Git graph…");
    private final JProgressBar progress = new JProgressBar();
    private final GraphCanvas graph;
    private final JTextArea commitDetails = readOnly();
    private final JButton refresh = new JButton("Refresh");
    private final JButton cancel = new JButton("Cancel");
    private int activeJobId = -1;

    static void showFor(Texteditor editor, Loader loader) {
        GitGraphDialog dialog = new GitGraphDialog(editor, loader);
        dialog.setVisible(true);
        dialog.refresh();
    }

    private GitGraphDialog(Texteditor editor, Loader loader) {
        super(editor, "Git Graph", false);
        this.editor = editor;
        this.loader = loader;
        this.graph = new GraphCanvas(editor.configManager, editor::resolveUiFont, this::showCommit);
        setDefaultCloseOperation(DISPOSE_ON_CLOSE);
        KeyboardFocusSupport.installEscape(getRootPane(), this::dispose);
        setLayout(new BorderLayout(8, 8));
        add(header(), BorderLayout.NORTH);
        add(content(), BorderLayout.CENTER);
        add(actions(), BorderLayout.SOUTH);
        editor.editorUiController.prepareDialog(this, 1060, 650);
        pack();
        WorkbenchToolWindowPlacement.restore(editor, this, WorkbenchLayout.SurfaceType.GIT, "graph");
    }

    private JPanel header() {
        JPanel text = new JPanel(new BorderLayout(0, 4));
        text.add(repository, BorderLayout.NORTH);
        state.setHorizontalAlignment(SwingConstants.LEFT);
        text.add(state, BorderLayout.SOUTH);
        progress.setIndeterminate(true);
        progress.setVisible(false);
        JPanel panel = new JPanel(new BorderLayout(8, 0));
        panel.setBorder(BorderFactory.createEmptyBorder(8, 8, 0, 8));
        panel.add(text, BorderLayout.CENTER);
        panel.add(progress, BorderLayout.EAST);
        return panel;
    }

    private JSplitPane content() {
        JScrollPane graphScroll = new JScrollPane(graph);
        graphScroll.setBorder(BorderFactory.createTitledBorder("Commit graph"));
        graphScroll.getViewport().setBackground(editor.configManager.getNormalColor());
        JScrollPane detailsScroll = new JScrollPane(commitDetails);
        detailsScroll.setBorder(BorderFactory.createTitledBorder("Selected commit"));
        JSplitPane split = new JSplitPane(JSplitPane.VERTICAL_SPLIT, graphScroll, detailsScroll);
        split.setResizeWeight(0.76);
        return split;
    }

    private JPanel actions() {
        refresh.addActionListener(event -> refresh());
        cancel.addActionListener(event -> cancelActiveJob());
        cancel.setEnabled(false);
        JButton close = new JButton("Close");
        close.addActionListener(event -> dispose());
        JPanel panel = new JPanel();
        panel.add(refresh);
        panel.add(cancel);
        panel.add(close);
        return panel;
    }

    private void refresh() {
        if (activeJobId >= 0) return;
        setBusy(true);
        state.setText("Loading local Git graph…");
        int jobId = editor.asyncJobService.submit("Git graph refresh", token -> loader.load(token), (job, result, error) -> {
            if (!isDisplayable() || job.getId() != activeJobId) return;
            activeJobId = -1;
            setBusy(false);
            if (job.getStatus() == AsyncJobService.Status.CANCELLED) {
                state.setText("Git graph refresh cancelled.");
                return;
            }
            if (job.getStatus() != AsyncJobService.Status.SUCCEEDED || result == null) {
                state.setText(error == null ? job.getErrorMessage() : error.getMessage());
                return;
            }
            render(result);
        });
        activeJobId = jobId;
    }

    private void render(GitGraphModel.Snapshot snapshot) {
        repository.setText(snapshot.root().isBlank() ? "Repository: unavailable" : "Repository: " + snapshot.root());
        state.setText(snapshot.detail());
        graph.setSnapshot(snapshot);
        if (!snapshot.rows().isEmpty()) graph.select(0);
        else commitDetails.setText("");
    }

    private void showCommit(GitGraphModel.Commit commit) {
        if (commit == null) {
            commitDetails.setText("");
            return;
        }
        String refs = commit.decorations().isBlank() ? "" : "\nRefs: " + commit.decorations();
        String parents = commit.parents().isEmpty() ? "" : "\nParents: " + String.join(" ", commit.parents());
        commitDetails.setText("Commit: " + commit.hash() + refs + parents + "\nAuthor: " + commit.author() + "\nDate: " + commit.timestamp()
            + "\n\n" + commit.subject() + "\n");
        commitDetails.setCaretPosition(0);
    }

    private void cancelActiveJob() {
        if (activeJobId >= 0 && editor.asyncJobService.cancel(activeJobId)) {
            state.setText("Cancellation requested…");
            cancel.setEnabled(false);
        }
    }

    private void setBusy(boolean busy) {
        progress.setVisible(busy);
        refresh.setEnabled(!busy);
        cancel.setEnabled(busy);
    }

    private static JTextArea readOnly() {
        JTextArea area = new JTextArea();
        area.setEditable(false);
        area.setLineWrap(false);
        return area;
    }

    static final class GraphCanvas extends JComponent {
        private final ConfigManager config;
        private final java.util.function.Supplier<Font> fontSupplier;
        private final java.util.function.Consumer<GitGraphModel.Commit> selectionListener;
        private Font textFont() { return fontSupplier.get(); }
        private int px(int value) { return UiZoom.scale(value, config.getUiZoom()); }
        private int rowHeight() { return Math.max(px(40), getFontMetrics(textFont()).getHeight() + px(12)); }
        private GitGraphModel.Snapshot snapshot = GitGraphModel.unavailable("");
        private int selected = -1;

        GraphCanvas(ConfigManager config, java.util.function.Supplier<Font> fontSupplier,
                    java.util.function.Consumer<GitGraphModel.Commit> selectionListener) {
            this.config = config;
            this.fontSupplier = fontSupplier;
            this.selectionListener = selectionListener;
            setFocusable(true);
            setOpaque(true);
            setBackground(config.getNormalColor());
            AccessibilitySupport.describe(this, "Git commit graph", "Colored local Git commit topology. Arrow keys select commits.");
            addFocusListener(new FocusAdapter() {
                @Override public void focusGained(FocusEvent event) { repaint(); }
                @Override public void focusLost(FocusEvent event) { repaint(); }
            });
            addMouseListener(new MouseAdapter() {
                @Override
                public void mouseClicked(MouseEvent event) {
                    select(event.getY() / rowHeight());
                    requestFocusInWindow();
                }
            });
            getInputMap(WHEN_FOCUSED).put(KeyStroke.getKeyStroke("UP"), "previous");
            getInputMap(WHEN_FOCUSED).put(KeyStroke.getKeyStroke("DOWN"), "next");
            getInputMap(WHEN_FOCUSED).put(KeyStroke.getKeyStroke("HOME"), "first");
            getInputMap(WHEN_FOCUSED).put(KeyStroke.getKeyStroke("END"), "last");
            getActionMap().put("previous", new AbstractAction() { @Override public void actionPerformed(java.awt.event.ActionEvent event) { select(selected - 1); } });
            getActionMap().put("next", new AbstractAction() { @Override public void actionPerformed(java.awt.event.ActionEvent event) { select(selected + 1); } });
            getActionMap().put("first", new AbstractAction() { @Override public void actionPerformed(java.awt.event.ActionEvent event) { select(0); } });
            getActionMap().put("last", new AbstractAction() { @Override public void actionPerformed(java.awt.event.ActionEvent event) { select(snapshot.rows().size() - 1); } });
        }

        void setSnapshot(GitGraphModel.Snapshot snapshot) {
            this.snapshot = snapshot == null ? GitGraphModel.unavailable("") : snapshot;
            selected = -1;
            setBackground(config.getNormalColor());
            revalidate();
            repaint();
        }

        void select(int index) {
            if (index < 0 || index >= snapshot.rows().size()) return;
            selected = index;
            selectionListener.accept(snapshot.rows().get(index).commit());
            scrollRectToVisible(new Rectangle(getVisibleRect().x, index * rowHeight(), 1, rowHeight()));
            repaint();
        }

        @Override
        public Dimension getPreferredSize() {
            int graphWidth = graphWidth();
            int widest = 0;
            Font font = textFont();
            FontMetrics metrics = getFontMetrics(font);
            FontMetrics hashMetrics = getFontMetrics(font.deriveFont(Font.BOLD));
            for (GitGraphModel.Row row : snapshot.rows()) {
                GitGraphModel.Commit commit = row.commit();
                int width = metrics.stringWidth(commit.timestamp()) + px(16)
                    + hashMetrics.stringWidth(commit.shortHash()) + px(16) + metrics.stringWidth(commit.subject());
                if (!commit.decorations().isBlank()) width += metrics.stringWidth(commit.decorations()) + px(16);
                widest = Math.max(widest, width);
            }
            return new Dimension(Math.max(px(760), graphWidth + widest + px(36)), Math.max(1, snapshot.rows().size()) * rowHeight());
        }

        @Override
        protected void paintComponent(Graphics graphics) {
            super.paintComponent(graphics);
            Graphics2D g = (Graphics2D) graphics.create();
            try {
                g.setColor(config.getNormalColor());
                g.fillRect(0, 0, getWidth(), getHeight());
                g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g.setStroke(new BasicStroke(Math.max(1f, (float) config.getUiZoom())));
                int rowHeight = rowHeight();
                Font font = textFont();
                Rectangle clip = g.getClipBounds();
                int first = clip == null ? 0 : Math.max(0, clip.y / rowHeight);
                int last = clip == null ? snapshot.rows().size() : Math.min(snapshot.rows().size(), (clip.y + clip.height) / rowHeight + 1);
                for (int index = first; index < last; index++) drawRow(g, snapshot.rows().get(index), index, rowHeight, font);
            } finally {
                g.dispose();
            }
        }

        private void drawRow(Graphics2D g, GitGraphModel.Row row, int index, int rowHeight, Font font) {
            int top = index * rowHeight;
            int middle = top + rowHeight / 2;
            int bottom = top + rowHeight;
            if (index == selected) {
                g.setColor(config.getCurrentLineHighlightColor());
                g.fillRect(0, top, getWidth(), rowHeight);
                g.setColor(config.getFocusColor());
                g.fillRect(0, top, px(3), rowHeight);
                if (hasFocus()) g.drawRect(0, top, getWidth() - 1, rowHeight - 1);
            }
            for (int lane = 0; lane < row.beforeLanes().size(); lane++) {
                int x = laneX(lane);
                if (lane == row.lane()) {
                    drawLine(g, x, top, x, middle, lane);
                    continue;
                }
                int target = row.afterLanes().indexOf(row.beforeLanes().get(lane));
                if (target >= 0) drawLine(g, x, top, laneX(target), bottom, target);
            }
            for (String parent : row.commit().parents()) {
                int target = row.afterLanes().indexOf(parent);
                if (target >= 0) drawLine(g, laneX(row.lane()), middle, laneX(target), bottom, target);
            }
            g.setColor(laneColor(row.lane()));
            g.fillOval(laneX(row.lane()) - px(5), middle - px(5), px(10), px(10));
            FontMetrics metrics = g.getFontMetrics(font);
            int baseline = top + (rowHeight - metrics.getHeight()) / 2 + metrics.getAscent();
            drawMetadata(g, row.commit(), graphWidth() + px(8), baseline, font);
        }

        private void drawMetadata(Graphics2D g, GitGraphModel.Commit commit, int x, int baseline, Font font) {
            g.setFont(font);
            FontMetrics metrics = g.getFontMetrics();
            g.setColor(config.getMutedForegroundColor());
            g.drawString(commit.timestamp(), x, baseline);
            x += metrics.stringWidth(commit.timestamp()) + px(16);
            if (!commit.decorations().isBlank()) {
                g.setColor(config.getSyntaxStringColor());
                g.drawString(commit.decorations(), x, baseline);
                x += metrics.stringWidth(commit.decorations()) + px(16);
            }
            g.setFont(font.deriveFont(Font.BOLD));
            metrics = g.getFontMetrics();
            g.setColor(config.getCaretColor());
            g.drawString(commit.shortHash(), x, baseline);
            x += metrics.stringWidth(commit.shortHash()) + px(16);
            g.setFont(font);
            g.setColor(config.getEditorForeground());
            g.drawString(commit.subject(), x, baseline);
        }

        private void drawLine(Graphics2D g, int x1, int y1, int x2, int y2, int lane) {
            g.setColor(laneColor(lane));
            g.drawLine(x1, y1, x2, y2);
        }

        private int graphWidth() {
            return px(16) + Math.max(1, snapshot.laneCount()) * px(19) + px(12);
        }

        private int laneX(int lane) {
            return px(16) + lane * px(19);
        }

        private Color laneColor(int lane) {
            return switch (Math.floorMod(lane, 7)) {
                case 0 -> config.getSyntaxKeywordColor();
                case 1 -> config.getSyntaxTypeColor();
                case 2 -> config.getSyntaxStringColor();
                case 3 -> config.getSyntaxNumberColor();
                case 4 -> config.getSyntaxFunctionColor();
                case 5 -> config.getSyntaxAnnotationColor();
                default -> config.getSyntaxConstantColor();
            };
        }
    }
}
