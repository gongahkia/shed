package shed;

import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.util.List;
import javax.swing.BorderFactory;
import javax.swing.DefaultListCellRenderer;
import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTextArea;
import javax.swing.ListSelectionModel;

/** Explicit local detection and consent-gated language-service installation. */
final class ManagedLanguageServicesDialog extends JDialog {
    private final Texteditor editor;
    private final ManagedLanguageSupportService service;
    private final JList<ManagedLanguageCatalog.Entry> services = new JList<>();
    private final JTextArea details = new JTextArea();
    private final JLabel status = new JLabel("Select a language service.");
    private final JButton detect = new JButton("Detect Local");
    private final JButton install = new JButton("Install / Update");
    private final JButton remove = new JButton("Remove Managed");
    private final JButton cancel = new JButton("Cancel");
    private int activeJobId = -1;

    static void showFor(Texteditor editor, ManagedLanguageSupportService service) {
        showFor(editor, service, null);
    }

    static void showFor(Texteditor editor, ManagedLanguageSupportService service, ManagedLanguageCatalog.Entry selected) {
        new ManagedLanguageServicesDialog(editor, service, selected).setVisible(true);
    }

    private ManagedLanguageServicesDialog(Texteditor editor, ManagedLanguageSupportService service, ManagedLanguageCatalog.Entry selected) {
        super(editor, "Language Services", false);
        this.editor = editor;
        this.service = service;
        setDefaultCloseOperation(DISPOSE_ON_CLOSE);
        KeyboardFocusSupport.installEscape(getRootPane(), this::dispose);
        setLayout(new BorderLayout(8, 8));
        add(content(), BorderLayout.CENTER);
        add(actions(), BorderLayout.SOUTH);
        setPreferredSize(new Dimension(900, 540));
        pack();
        setLocationRelativeTo(editor);
        services.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        services.setModel(new javax.swing.DefaultListModel<>());
        javax.swing.DefaultListModel<ManagedLanguageCatalog.Entry> model = (javax.swing.DefaultListModel<ManagedLanguageCatalog.Entry>) services.getModel();
        for (ManagedLanguageCatalog.Entry entry : ManagedLanguageCatalog.entries()) model.addElement(entry);
        services.setCellRenderer(new DefaultListCellRenderer() {
            @Override public java.awt.Component getListCellRendererComponent(JList<?> list, Object value, int index,
                boolean selected, boolean focused) {
                super.getListCellRendererComponent(list, value, index, selected, focused);
                setText(value instanceof ManagedLanguageCatalog.Entry entry ? entry.displayName() : "");
                return this;
            }
        });
        services.addListSelectionListener(event -> { if (!event.getValueIsAdjusting()) refresh(); });
        detect.addActionListener(event -> detect());
        install.addActionListener(event -> install());
        remove.addActionListener(event -> remove());
        cancel.addActionListener(event -> cancel());
        addWindowListener(new WindowAdapter() {
            @Override public void windowClosed(WindowEvent event) { cancel(); }
        });
        if (selected != null) services.setSelectedValue(selected, true);
        if (services.getSelectedIndex() < 0 && !model.isEmpty()) services.setSelectedIndex(0);
        refresh();
    }

    private JPanel content() {
        JScrollPane list = new JScrollPane(services);
        list.setBorder(BorderFactory.createTitledBorder("Language services"));
        details.setEditable(false);
        details.setLineWrap(true);
        details.setWrapStyleWord(true);
        JScrollPane detailScroll = new JScrollPane(details);
        detailScroll.setBorder(BorderFactory.createTitledBorder("Status and install review"));
        JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, list, detailScroll);
        split.setResizeWeight(0.30);
        JPanel panel = new JPanel(new BorderLayout(8, 8));
        panel.setBorder(BorderFactory.createEmptyBorder(8, 8, 0, 8));
        panel.add(split, BorderLayout.CENTER);
        return panel;
    }

    private JPanel actions() {
        JPanel panel = new JPanel(new BorderLayout());
        panel.setBorder(BorderFactory.createEmptyBorder(0, 8, 8, 8));
        panel.add(status, BorderLayout.CENTER);
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        buttons.add(detect);
        buttons.add(install);
        buttons.add(remove);
        cancel.setEnabled(false);
        buttons.add(cancel);
        JButton close = new JButton("Close");
        close.addActionListener(event -> dispose());
        buttons.add(close);
        panel.add(buttons, BorderLayout.EAST);
        return panel;
    }

    private void refresh() {
        ManagedLanguageCatalog.Entry entry = selected();
        if (entry == null) {
            details.setText("");
            install.setEnabled(false);
            return;
        }
        ManagedLanguageDistributionCatalog.Distribution distribution = service.distributionFor(entry);
        StringBuilder text = new StringBuilder(entry.displayName()).append("\n\n");
        text.append("Extensions: ").append(String.join(", ", entry.extensions())).append("\n");
        text.append("Runtime: ").append(entry.installMetadata().runtimeName()).append(" ")
            .append(entry.installMetadata().minimumRuntimeVersion()).append("+\n");
        text.append("Local detection: use Detect Local; this never uses the network.\n\n");
        if (distribution == null) {
            text.append("Managed install: not available for this language yet.\n\n").append(service.manualInstructions(entry));
        } else {
            text.append("Managed install: available only after you approve this exact action.\n");
            if (distribution.usesPinnedArchive()) {
                text.append("Source: ").append(distribution.artifact().source()).append("\n");
                text.append("SHA-256: ").append(distribution.artifact().sha256()).append("\n");
            } else {
                text.append("Installer: npm (local cache only; no global install)\n");
                text.append("Packages: ").append(String.join(", ", distribution.npmPackages())).append("\n");
                text.append("Source: https://registry.npmjs.org\n");
            }
            text.append("License: ").append(entry.installMetadata().licenseName()).append("\n");
            text.append("Verification: ").append(distribution.verificationNotice()).append("\n");
        }
        details.setText(text.toString());
        details.setCaretPosition(0);
        install.setEnabled(distribution != null && activeJobId < 0);
        remove.setEnabled(activeJobId < 0);
    }

    private void detect() {
        ManagedLanguageCatalog.Entry entry = selected();
        if (entry == null || activeJobId >= 0) return;
        setBusy("Detecting " + entry.displayName() + " locally...");
        activeJobId = editor.asyncJobService.submit("LSP detection: " + entry.displayName(), token -> service.detect(entry),
            (snapshot, result, error) -> {
                activeJobId = -1;
                status.setText(error == null && result != null ? result.status().detail() : failure(error, snapshot));
                setIdle();
            });
    }

    private void install() {
        ManagedLanguageCatalog.Entry entry = selected();
        ManagedLanguageDistributionCatalog.Distribution distribution = service.distributionFor(entry);
        if (entry == null || distribution == null || activeJobId >= 0) return;
        String review = installReview(entry, distribution);
        if (JOptionPane.showConfirmDialog(this, review, "Approve language-service install", JOptionPane.YES_NO_OPTION,
            JOptionPane.WARNING_MESSAGE) != JOptionPane.YES_OPTION) return;
        ManagedLanguageInstallApproval approval = ManagedLanguageInstallApproval.approvedInLanguageServicesPanel(entry);
        setBusy("Installing " + entry.displayName() + "...");
        activeJobId = editor.asyncJobService.submit("Install " + entry.displayName(), token -> service.install(entry, approval, token),
            (snapshot, result, error) -> {
                activeJobId = -1;
                if (error != null || result == null || !result.installed()) {
                    status.setText(error == null ? result == null ? "Install failed" : result.detail() : failure(error, snapshot));
                    setIdle();
                    return;
                }
                try {
                    configureManagedLsp(entry, result);
                    status.setText(result.detail() + "; configured and restarted " + extensionList(entry) + " LSP");
                } catch (Exception configurationError) {
                    status.setText(result.detail() + "; set lsp command manually: " + configurationError.getMessage());
                }
                setIdle();
            });
    }

    private void remove() {
        ManagedLanguageCatalog.Entry entry = selected();
        if (entry == null || activeJobId >= 0) return;
        if (JOptionPane.showConfirmDialog(this, "Remove only Shed-managed files for " + entry.displayName() + "?",
            "Remove managed language service", JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE) != JOptionPane.YES_OPTION) return;
        status.setText(service.remove(entry).detail());
        refresh();
    }

    private void cancel() {
        if (activeJobId >= 0) editor.asyncJobService.cancel(activeJobId);
    }

    private void setBusy(String message) {
        status.setText(message);
        detect.setEnabled(false);
        install.setEnabled(false);
        remove.setEnabled(false);
        cancel.setEnabled(true);
    }

    private void setIdle() {
        cancel.setEnabled(false);
        refresh();
    }

    private ManagedLanguageCatalog.Entry selected() { return services.getSelectedValue(); }

    private static String primaryExtension(ManagedLanguageCatalog.Entry entry) {
        return entry.extensions().stream().sorted().findFirst().orElse(entry.languageId());
    }

    private String installReview(ManagedLanguageCatalog.Entry entry, ManagedLanguageDistributionCatalog.Distribution distribution) {
        StringBuilder review = new StringBuilder(entry.displayName()).append("\n\n");
        if (distribution.usesPinnedArchive()) {
            review.append("Source:\n").append(distribution.artifact().source()).append("\n\nSHA-256:\n")
                .append(distribution.artifact().sha256()).append("\n\n");
        } else {
            review.append("Installer: npm\nPackages:\n");
            for (String npmPackage : distribution.npmPackages()) review.append("  ").append(npmPackage).append("\n");
            review.append("\nSource: https://registry.npmjs.org\n")
                .append("Install location: ~/.shed/managed-languages only (not global npm)\n")
                .append("npm lifecycle scripts, audit, funding, and update notifications are disabled.\n\n");
        }
        return review.append("License: ").append(entry.installMetadata().licenseName()).append("\n\n")
            .append(distribution.verificationNotice()).append("\n\n")
            .append("Proceed with this one-time download, installation, and LSP configuration?\n")
            .append("Nothing is installed or updated unless you choose Yes.").toString();
    }

    private void configureManagedLsp(ManagedLanguageCatalog.Entry entry, ManagedLanguageSupportService.InstallResult result) throws Exception {
        String arguments = String.join(" ", result.arguments());
        for (String extension : entry.extensions()) {
            editor.configManager.setAndPersist("lsp." + extension + ".command", result.command().toString());
            editor.configManager.setAndPersist("lsp." + extension + ".args", arguments);
        }
        for (String extension : entry.extensions()) editor.lspRestart(extension);
    }

    private static String extensionList(ManagedLanguageCatalog.Entry entry) {
        return entry.extensions().stream().sorted().map(extension -> "." + extension).collect(java.util.stream.Collectors.joining(", "));
    }

    private static String failure(Exception error, AsyncJobService.JobSnapshot snapshot) {
        if (error != null && error.getMessage() != null) return error.getMessage();
        return snapshot == null ? "Operation failed" : snapshot.getErrorMessage();
    }
}
