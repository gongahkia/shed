package shed;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
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
        this.graph = new GraphCanvas(editor, this::showCommit);
        setDefaultCloseOperation(DISPOSE_ON_CLOSE);
        KeyboardFocusSupport.installEscape(getRootPane(), this::dispose);
        setLayout(new BorderLayout(8, 8));
        add(header(), BorderLayout.NORTH);
        add(content(), BorderLayout.CENTER);
        add(actions(), BorderLayout.SOUTH);
        setPreferredSize(new Dimension(1060, 650));
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

    private static final class GraphCanvas extends JComponent {
        private static final int ROW_HEIGHT = 30;
        private static final int LANE_SPACING = 19;
        private static final int GRAPH_LEFT = 16;
        private static final Color[] LANE_COLORS = {
            new Color(235, 97, 97), new Color(79, 190, 229), new Color(116, 207, 109), new Color(243, 190, 74),
            new Color(186, 117, 232), new Color(242, 137, 185), new Color(101, 205, 187), new Color(221, 141, 69)
        };

        private final Texteditor editor;
        private final java.util.function.Consumer<GitGraphModel.Commit> selectionListener;
        private final Font textFont = new Font(Font.MONOSPACED, Font.PLAIN, 14);
        private final Font hashFont = new Font(Font.MONOSPACED, Font.BOLD, 14);
        private GitGraphModel.Snapshot snapshot = GitGraphModel.unavailable("");
        private int selected = -1;

        GraphCanvas(Texteditor editor, java.util.function.Consumer<GitGraphModel.Commit> selectionListener) {
            this.editor = editor;
            this.selectionListener = selectionListener;
            setFocusable(true);
            setOpaque(true);
            setBackground(editor.configManager.getNormalColor());
            AccessibilitySupport.describe(this, "Git commit graph", "Colored local Git commit topology. Arrow keys select commits.");
            addMouseListener(new MouseAdapter() {
                @Override
                public void mouseClicked(MouseEvent event) {
                    select(event.getY() / ROW_HEIGHT);
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
            setBackground(editor.configManager.getNormalColor());
            revalidate();
            repaint();
        }

        void select(int index) {
            if (index < 0 || index >= snapshot.rows().size()) return;
            selected = index;
            selectionListener.accept(snapshot.rows().get(index).commit());
            repaint();
        }

        @Override
        public Dimension getPreferredSize() {
            int graphWidth = graphWidth();
            int widest = 0;
            FontMetrics metrics = getFontMetrics(textFont);
            for (GitGraphModel.Row row : snapshot.rows()) {
                String text = row.commit().timestamp() + "  " + row.commit().decorations() + "  " + row.commit().shortHash() + "  " + row.commit().subject();
                widest = Math.max(widest, metrics.stringWidth(text));
            }
            return new Dimension(Math.max(760, graphWidth + widest + 36), Math.max(1, snapshot.rows().size()) * ROW_HEIGHT);
        }

        @Override
        protected void paintComponent(Graphics graphics) {
            super.paintComponent(graphics);
            Graphics2D g = (Graphics2D) graphics.create();
            try {
                g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                for (int index = 0; index < snapshot.rows().size(); index++) drawRow(g, snapshot.rows().get(index), index);
            } finally {
                g.dispose();
            }
        }

        private void drawRow(Graphics2D g, GitGraphModel.Row row, int index) {
            int top = index * ROW_HEIGHT;
            int middle = top + ROW_HEIGHT / 2;
            int bottom = top + ROW_HEIGHT;
            if (index == selected) {
                g.setColor(editor.configManager.getCurrentLineHighlightColor());
                g.fillRect(0, top, getWidth(), ROW_HEIGHT);
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
            g.fillOval(laneX(row.lane()) - 5, middle - 5, 10, 10);
            drawMetadata(g, row.commit(), graphWidth() + 8, middle + 5);
        }

        private void drawMetadata(Graphics2D g, GitGraphModel.Commit commit, int x, int baseline) {
            g.setFont(textFont);
            FontMetrics metrics = g.getFontMetrics();
            g.setColor(mutedColor());
            g.drawString(commit.timestamp(), x, baseline);
            x += metrics.stringWidth(commit.timestamp()) + 16;
            if (!commit.decorations().isBlank()) {
                g.setColor(new Color(117, 207, 133));
                g.drawString(commit.decorations(), x, baseline);
                x += metrics.stringWidth(commit.decorations()) + 16;
            }
            g.setFont(hashFont);
            metrics = g.getFontMetrics();
            g.setColor(editor.configManager.getCaretColor());
            g.drawString(commit.shortHash(), x, baseline);
            x += metrics.stringWidth(commit.shortHash()) + 16;
            g.setFont(textFont);
            g.setColor(editor.configManager.getEditorForeground());
            g.drawString(commit.subject(), x, baseline);
        }

        private void drawLine(Graphics2D g, int x1, int y1, int x2, int y2, int lane) {
            g.setColor(laneColor(lane));
            g.drawLine(x1, y1, x2, y2);
        }

        private int graphWidth() {
            return GRAPH_LEFT + Math.max(1, snapshot.laneCount()) * LANE_SPACING + 12;
        }

        private int laneX(int lane) {
            return GRAPH_LEFT + lane * LANE_SPACING;
        }

        private Color laneColor(int lane) {
            return LANE_COLORS[Math.floorMod(lane, LANE_COLORS.length)];
        }

        private Color mutedColor() {
            Color foreground = editor.configManager.getEditorForeground();
            Color background = editor.configManager.getNormalColor();
            return new Color((foreground.getRed() + background.getRed()) / 2, (foreground.getGreen() + background.getGreen()) / 2,
                (foreground.getBlue() + background.getBlue()) / 2);
        }
    }
}
