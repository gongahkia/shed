package shed;

import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JProgressBar;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTabbedPane;
import javax.swing.JTable;
import javax.swing.JTextArea;
import javax.swing.ListSelectionModel;
import javax.swing.SwingConstants;
import javax.swing.event.ChangeListener;
import javax.swing.table.DefaultTableModel;

/** Fast, local control plane for the user-installed Docker CLI and daemon. */
final class DockerWorkbenchDialog extends JDialog {
    private enum Resource {
        CONTAINERS("Containers", "container", new String[] {"ID", "Name", "Image", "Status", "Ports"},
            List.of("docker", "container", "ls", "--all", "--format", "{{.ID}}\t{{.Names}}\t{{.Image}}\t{{.Status}}\t{{.Ports}}")),
        IMAGES("Images", "image", new String[] {"ID", "Repository", "Tag", "Size", "Created"},
            List.of("docker", "image", "ls", "--format", "{{.ID}}\t{{.Repository}}\t{{.Tag}}\t{{.Size}}\t{{.CreatedSince}}")),
        VOLUMES("Volumes", "volume", new String[] {"Name", "Driver", "Scope"},
            List.of("docker", "volume", "ls", "--format", "{{.Name}}\t{{.Driver}}\t{{.Scope}}")),
        NETWORKS("Networks", "network", new String[] {"ID", "Name", "Driver", "Scope"},
            List.of("docker", "network", "ls", "--format", "{{.ID}}\t{{.Name}}\t{{.Driver}}\t{{.Scope}}"));

        private final String label;
        private final String cli;
        private final String[] columns;
        private final List<String> listCommand;

        Resource(String label, String cli, String[] columns, List<String> listCommand) {
            this.label = label;
            this.cli = cli;
            this.columns = columns;
            this.listCommand = listCommand;
        }
    }

    private static final class Page {
        private final Resource resource;
        private final DefaultTableModel model;
        private final JTable table;
        private boolean loaded;

        private Page(Resource resource) {
            this.resource = resource;
            model = new DefaultTableModel(resource.columns, 0) {
                @Override public boolean isCellEditable(int row, int column) { return false; }
            };
            table = new JTable(model);
            table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
            table.setAutoCreateRowSorter(true);
            table.setFillsViewportHeight(true);
        }

        private String selectedId() {
            int row = table.getSelectedRow();
            if (row < 0) return "";
            int modelRow = table.convertRowIndexToModel(row);
            Object value = model.getValueAt(modelRow, 0);
            return value == null ? "" : value.toString();
        }

        private String selectedImageReference() {
            if (resource != Resource.IMAGES) return "";
            int row = table.getSelectedRow();
            if (row < 0) return "";
            int modelRow = table.convertRowIndexToModel(row);
            String id = cell(modelRow, 0);
            String repository = cell(modelRow, 1);
            String tag = cell(modelRow, 2);
            if (repository.isBlank() || "<none>".equals(repository)) return id;
            return tag.isBlank() || "<none>".equals(tag) ? repository : repository + ":" + tag;
        }

        private String cell(int row, int column) {
            Object value = model.getValueAt(row, column);
            return value == null ? "" : value.toString();
        }
    }

    private final Texteditor editor;
    private final Map<Resource, Page> pages = new EnumMap<>(Resource.class);
    private final JTabbedPane tabs = new JTabbedPane();
    private final JTextArea output = readOnlyArea();
    private final JLabel state = new JLabel("Choose a resource tab, then Refresh.");
    private final JProgressBar progress = new JProgressBar();
    private final JButton refresh = new JButton("Refresh");
    private final JButton inspect = new JButton("Inspect");
    private final JButton create = new JButton("Create");
    private final JButton pull = new JButton("Pull Image…");
    private final JButton runImage = new JButton("Run Image…");
    private final JButton start = new JButton("Start");
    private final JButton stop = new JButton("Stop");
    private final JButton restart = new JButton("Restart");
    private final JButton pause = new JButton("Pause");
    private final JButton unpause = new JButton("Resume");
    private final JButton logs = new JButton("Logs");
    private final JButton stats = new JButton("Stats");
    private final JButton processes = new JButton("Processes");
    private final JButton terminal = new JButton("Terminal");
    private final JButton execute = new JButton("Exec…");
    private final JButton openWorkspace = new JButton("Open Workspace…");
    private final JButton connect = new JButton("Connect…");
    private final JButton disconnect = new JButton("Disconnect…");
    private final JButton remove = new JButton("Remove…");
    private final JButton command = new JButton("Docker Command…");
    private final JButton cancel = new JButton("Cancel");
    private int activeJob = -1;

    static void showFor(Texteditor editor) {
        if (editor == null) return;
        DockerWorkbenchDialog dialog = new DockerWorkbenchDialog(editor);
        dialog.setVisible(true);
        dialog.refreshPage(dialog.currentPage());
    }

    private DockerWorkbenchDialog(Texteditor editor) {
        super(editor, "Docker Workbench", false);
        this.editor = editor;
        setDefaultCloseOperation(DISPOSE_ON_CLOSE);
        KeyboardFocusSupport.installEscape(getRootPane(), this::dispose);
        setLayout(new BorderLayout(8, 8));
        add(header(), BorderLayout.NORTH);
        add(content(), BorderLayout.CENTER);
        add(actions(), BorderLayout.SOUTH);
        editor.editorUiController.prepareDialog(this, 1180, 720);
        pack();
        setLocationRelativeTo(editor);
    }

    private JPanel header() {
        JPanel panel = new JPanel(new BorderLayout(8, 0));
        panel.setBorder(BorderFactory.createEmptyBorder(8, 8, 0, 8));
        JLabel title = new JLabel("Docker Workbench — local Docker CLI and daemon");
        panel.add(title, BorderLayout.WEST);
        state.setHorizontalAlignment(SwingConstants.RIGHT);
        panel.add(state, BorderLayout.CENTER);
        progress.setIndeterminate(true);
        progress.setVisible(false);
        panel.add(progress, BorderLayout.EAST);
        return panel;
    }

    private JSplitPane content() {
        for (Resource resource : Resource.values()) {
            Page page = new Page(resource);
            pages.put(resource, page);
            AccessibilitySupport.describe(page.table, resource.label + " table", "Local Docker " + resource.label.toLowerCase(Locale.ROOT) + ".");
            page.table.getSelectionModel().addListSelectionListener(event -> {
                if (!event.getValueIsAdjusting()) updateActions();
            });
            JScrollPane scroll = new JScrollPane(page.table);
            scroll.setBorder(BorderFactory.createTitledBorder(resource.label));
            tabs.addTab(resource.label, scroll);
        }
        ChangeListener selection = event -> {
            Page page = currentPage();
            updateActions();
            if (!page.loaded) refreshPage(page);
        };
        tabs.addChangeListener(selection);
        JScrollPane detail = new JScrollPane(output);
        detail.setBorder(BorderFactory.createTitledBorder("Docker output and inspection"));
        JSplitPane split = new JSplitPane(JSplitPane.VERTICAL_SPLIT, tabs, detail);
        split.setResizeWeight(0.68);
        return split;
    }

    private JPanel actions() {
        refresh.addActionListener(event -> refreshPage(currentPage()));
        inspect.addActionListener(event -> inspect());
        create.addActionListener(event -> createResource());
        pull.addActionListener(event -> pullImage());
        runImage.addActionListener(event -> runImage());
        start.addActionListener(event -> containerAction("start"));
        stop.addActionListener(event -> containerAction("stop"));
        restart.addActionListener(event -> containerAction("restart"));
        pause.addActionListener(event -> containerAction("pause"));
        unpause.addActionListener(event -> containerAction("unpause"));
        logs.addActionListener(event -> containerOutput("logs", List.of("docker", "container", "logs", "--tail", "500", selectedId())));
        stats.addActionListener(event -> containerOutput("stats", List.of("docker", "container", "stats", "--no-stream", selectedId())));
        processes.addActionListener(event -> containerOutput("processes", List.of("docker", "container", "top", selectedId())));
        terminal.addActionListener(event -> openTerminal());
        execute.addActionListener(event -> executeInContainer());
        openWorkspace.addActionListener(event -> openWorkspace());
        connect.addActionListener(event -> networkAttachment(true));
        disconnect.addActionListener(event -> networkAttachment(false));
        remove.addActionListener(event -> removeResource());
        command.addActionListener(event -> runArbitraryDockerCommand());
        cancel.addActionListener(event -> cancel());
        cancel.setEnabled(false);
        JButton close = new JButton("Close");
        close.addActionListener(event -> dispose());

        JPanel panel = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 5));
        for (JButton button : List.of(refresh, inspect, create, pull, runImage, start, stop, restart, pause, unpause,
            logs, stats, processes, terminal, execute, openWorkspace, connect, disconnect, remove, command, cancel, close)) {
            panel.add(button);
        }
        updateActions();
        return panel;
    }

    private Page currentPage() {
        int index = Math.max(0, tabs.getSelectedIndex());
        return pages.get(Resource.values()[index]);
    }

    private void refreshPage(Page page) {
        if (page == null || isBusy()) return;
        startJob("Refresh " + page.resource.label.toLowerCase(Locale.ROOT), page.resource.listCommand, (result, error) -> {
            if (error != null) {
                showResult("Refresh " + page.resource.label, error, true);
                return;
            }
            page.model.setRowCount(0);
            for (String[] row : parseRows(result, page.resource.columns.length)) page.model.addRow(row);
            page.loaded = true;
            output.setText("");
            state.setText(page.model.getRowCount() + " " + page.resource.label.toLowerCase(Locale.ROOT) + " loaded.");
            updateActions();
        });
    }

    private void inspect() {
        Page page = currentPage();
        if (!requireSelection(page)) return;
        runOutput("Inspect " + page.resource.cli, List.of("docker", page.resource.cli, "inspect", page.selectedId()), false);
    }

    private void createResource() {
        Page page = currentPage();
        switch (page.resource) {
            case VOLUMES -> createNamed("Create volume", "Volume name:", "volume", "create");
            case NETWORKS -> createNamed("Create network", "Network name:", "network", "create");
            default -> { }
        }
    }

    private void createNamed(String title, String prompt, String group, String operation) {
        String value = prompt(title, prompt, "");
        if (value == null || value.isBlank()) return;
        if (!safeResourceName(value)) {
            showMessage("Names may contain letters, digits, dots, underscores, and hyphens.");
            return;
        }
        runOutput(title, List.of("docker", group, operation, value), true);
    }

    private void pullImage() {
        String image = prompt("Pull Docker image", "Image reference:", "");
        if (image == null || image.isBlank()) return;
        if (!safeArgument(image)) {
            showMessage("Image reference contains an invalid control character.");
            return;
        }
        runOutput("Pull image " + image, List.of("docker", "image", "pull", image), true);
    }

    private void runImage() {
        String image = currentPage().selectedImageReference();
        if (image.isBlank()) image = prompt("Run Docker image", "Image reference:", "");
        if (image == null || image.isBlank() || !safeArgument(image)) return;
        String rawCommand = prompt("Run " + image, "Optional container command (direct arguments):", "");
        if (rawCommand == null) return;
        List<String> command;
        try {
            command = ShellCommand.directCommand(rawCommand);
        } catch (IllegalArgumentException error) {
            showMessage("Container command invalid: " + error.getMessage());
            return;
        }
        List<String> invocation = new ArrayList<>(List.of("docker", "container", "run", "-d", image));
        invocation.addAll(command);
        runOutput("Run image " + image, invocation, true);
    }

    private void containerAction(String operation) {
        if (!requireContainer()) return;
        runOutput("Container " + operation, List.of("docker", "container", operation, selectedId()), true);
    }

    private void containerOutput(String label, List<String> invocation) {
        if (!requireContainer()) return;
        runOutput("Container " + label, invocation, false);
    }

    private void openTerminal() {
        if (!requireContainer()) return;
        String result = editor.handleDockerCommand("terminal " + selectedId());
        state.setText(result);
    }

    private void executeInContainer() {
        if (!requireContainer()) return;
        String raw = prompt("Execute in " + selectedId(), "Command (direct arguments):", "/bin/sh -lc");
        if (raw == null || raw.isBlank()) return;
        List<String> command;
        try {
            command = ShellCommand.directCommand(raw);
        } catch (IllegalArgumentException error) {
            showMessage("Container command invalid: " + error.getMessage());
            return;
        }
        List<String> invocation = new ArrayList<>(List.of("docker", "container", "exec", selectedId()));
        invocation.addAll(command);
        runOutput("Container exec", invocation, false);
    }

    private void openWorkspace() {
        if (!requireContainer()) return;
        String path = prompt("Open container workspace", "Absolute path inside " + selectedId() + ":", "/workspace");
        if (path == null || path.isBlank()) return;
        if (!path.startsWith("/") || !safeArgument(path)) {
            showMessage("The container path must be absolute and contain no control characters.");
            return;
        }
        state.setText(editor.handleDockerCommand("open " + selectedId() + " " + path));
    }

    private void networkAttachment(boolean attach) {
        Page page = currentPage();
        if (page.resource != Resource.NETWORKS || !requireSelection(page)) return;
        String container = prompt((attach ? "Connect" : "Disconnect") + " network", "Container name or id:", "");
        if (container == null || container.isBlank() || !safeResourceName(container)) return;
        runOutput((attach ? "Connect" : "Disconnect") + " network", List.of("docker", "network", attach ? "connect" : "disconnect", page.selectedId(), container), true);
    }

    private void removeResource() {
        Page page = currentPage();
        if (!requireSelection(page)) return;
        String id = page.selectedId();
        String message = "Remove " + page.resource.label.toLowerCase(Locale.ROOT) + " '" + id + "'? Docker may reject resources that are still in use.";
        if (JOptionPane.showConfirmDialog(this, message, "Confirm Docker removal", JOptionPane.YES_NO_OPTION,
            JOptionPane.WARNING_MESSAGE) != JOptionPane.YES_OPTION) return;
        runOutput("Remove " + page.resource.cli, List.of("docker", page.resource.cli, "rm", id), true);
    }

    private void runArbitraryDockerCommand() {
        String raw = prompt("Docker command", "Arguments after docker (direct argv, no shell):", "container ls --all");
        if (raw == null || raw.isBlank()) return;
        List<String> tokens;
        try {
            tokens = ShellCommand.directCommand(raw);
        } catch (IllegalArgumentException error) {
            showMessage("Docker command invalid: " + error.getMessage());
            return;
        }
        if (tokens.isEmpty()) return;
        if (requiresConfirmation(tokens) && JOptionPane.showConfirmDialog(this,
            "Run docker " + raw + "? This command can change or remove local Docker resources.", "Confirm Docker command",
            JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE) != JOptionPane.YES_OPTION) return;
        List<String> invocation = new ArrayList<>(List.of("docker"));
        invocation.addAll(tokens);
        runOutput("Docker command", invocation, false);
    }

    private void runOutput(String label, List<String> invocation, boolean refreshAfter) {
        if (isBusy()) return;
        Page page = currentPage();
        startJob(label, invocation, (result, error) -> {
            showResult(label, error == null ? result : error, error != null);
            if (error == null && refreshAfter) refreshPage(page);
        });
    }

    private void startJob(String label, List<String> invocation, ResultHandler handler) {
        if (isBusy()) return;
        setBusy(true);
        state.setText(label + " running…");
        output.setText("$ " + String.join(" ", invocation) + "\n\n");
        activeJob = editor.asyncJobService.submit(label, token -> DockerRuntime.run(invocation, token), (snapshot, result, error) -> {
            if (!isDisplayable() || snapshot.getId() != activeJob) return;
            activeJob = -1;
            setBusy(false);
            if (snapshot.getStatus() == AsyncJobService.Status.CANCELLED) {
                state.setText(label + " cancelled.");
                return;
            }
            handler.handle(result, error == null ? null : DockerRuntime.concise(error));
        });
    }

    private void showResult(String label, String value, boolean failed) {
        String text = value == null || value.isBlank() ? "(no output)\n" : value;
        output.append(text.endsWith("\n") ? text : text + "\n");
        output.setCaretPosition(0);
        state.setText(label + (failed ? " failed." : " completed."));
    }

    private boolean requireContainer() {
        Page page = currentPage();
        if (page.resource == Resource.CONTAINERS && requireSelection(page)) return true;
        showMessage("Select one container first.");
        return false;
    }

    private boolean requireSelection(Page page) {
        if (page != null && !page.selectedId().isBlank()) return true;
        showMessage("Select one " + (page == null ? "resource" : page.resource.label.toLowerCase(Locale.ROOT).replace("s", "")) + " first.");
        return false;
    }

    private String selectedId() {
        return currentPage().selectedId();
    }

    private void cancel() {
        if (activeJob >= 0 && editor.asyncJobService.cancel(activeJob)) {
            cancel.setEnabled(false);
            state.setText("Cancellation requested…");
        }
    }

    private boolean isBusy() {
        return activeJob >= 0;
    }

    private void setBusy(boolean busy) {
        progress.setVisible(busy);
        cancel.setEnabled(busy);
        tabs.setEnabled(!busy);
        updateActions();
    }

    private void updateActions() {
        Page page = pages.get(Resource.values()[Math.max(0, tabs.getSelectedIndex())]);
        if (page == null) return;
        boolean busy = isBusy();
        boolean selected = !page.selectedId().isBlank();
        boolean containers = page.resource == Resource.CONTAINERS;
        boolean images = page.resource == Resource.IMAGES;
        boolean volumes = page.resource == Resource.VOLUMES;
        boolean networks = page.resource == Resource.NETWORKS;
        refresh.setEnabled(!busy);
        inspect.setVisible(true);
        inspect.setEnabled(!busy && selected);
        create.setVisible(volumes || networks);
        create.setText(volumes ? "Create Volume…" : "Create Network…");
        create.setEnabled(!busy);
        pull.setVisible(images);
        pull.setEnabled(!busy);
        runImage.setVisible(containers || images);
        runImage.setEnabled(!busy && (containers || images));
        for (JButton button : List.of(start, stop, restart, pause, unpause, logs, stats, processes, terminal, execute, openWorkspace)) {
            button.setVisible(containers);
            button.setEnabled(!busy && selected && containers);
        }
        connect.setVisible(networks);
        disconnect.setVisible(networks);
        connect.setEnabled(!busy && selected && networks);
        disconnect.setEnabled(!busy && selected && networks);
        remove.setEnabled(!busy && selected);
        command.setEnabled(!busy);
        if (getContentPane() != null) {
            getContentPane().revalidate();
            getContentPane().repaint();
        }
    }

    private String prompt(String title, String message, String initial) {
        Object result = JOptionPane.showInputDialog(this, message, title, JOptionPane.PLAIN_MESSAGE, null, null, initial);
        return result == null ? null : result.toString().trim();
    }

    private void showMessage(String message) {
        JOptionPane.showMessageDialog(this, message, "Docker Workbench", JOptionPane.INFORMATION_MESSAGE);
    }

    private static boolean safeArgument(String value) {
        return value != null && !value.isBlank() && value.indexOf('\0') < 0 && value.indexOf('\n') < 0 && value.indexOf('\r') < 0;
    }

    private static boolean safeResourceName(String value) {
        return value != null && value.matches("[A-Za-z0-9][A-Za-z0-9_.-]*");
    }

    private static boolean requiresConfirmation(List<String> tokens) {
        return tokens.stream().map(value -> value.toLowerCase(Locale.ROOT))
            .anyMatch(value -> value.equals("rm") || value.equals("prune") || value.equals("kill") || value.equals("down"));
    }

    static List<String[]> parseRows(String output, int columns) {
        if (output == null || output.isBlank() || columns < 1) return List.of();
        List<String[]> rows = new ArrayList<>();
        for (String line : output.split("\\R")) {
            if (line.isBlank()) continue;
            String[] source = line.split("\\t", -1);
            String[] row = new String[columns];
            for (int column = 0; column < columns; column++) row[column] = column < source.length ? source[column] : "";
            rows.add(row);
        }
        return List.copyOf(rows);
    }

    private static JTextArea readOnlyArea() {
        JTextArea area = new JTextArea();
        area.setEditable(false);
        area.setLineWrap(false);
        area.setFont(Font.decode(Font.MONOSPACED));
        AccessibilitySupport.describe(area, "Docker output", "Read-only Docker command output and selected resource inspection.");
        return area;
    }

    @FunctionalInterface
    private interface ResultHandler {
        void handle(String result, String error);
    }
}
