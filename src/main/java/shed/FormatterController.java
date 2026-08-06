package shed;

import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Objects;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTextField;

final class FormatterController {
    private record Request(FileBuffer buffer, String content) { }
    private final Texteditor editor;

    FormatterController(Texteditor editor) { this.editor = editor; }

    String formatCurrent() {
        FileBuffer buffer = editor.getCurrentBuffer();
        if (buffer == null || !buffer.hasFilePath() || buffer.isLargeFile()) return "Formatting requires a file-backed buffer";
        FormatterPolicy policy = policy(buffer);
        if (policy.mode() == FormatterPolicy.Mode.DISABLED) return "Formatting is disabled for ." + policy.extension();
        if (policy.mode() == FormatterPolicy.Mode.LSP) return editor.lspController.lspFormat();
        String content = editor.writingArea.getText();
        try {
            Path file = new File(buffer.getFilePath()).toPath().toAbsolutePath().normalize();
            Path workspace = editor.lspController.resolveWorkspaceRoot(file.getParent());
            Request request = new Request(buffer, content);
            int jobId = editor.asyncJobService.submit("External format", token -> {
                ExternalFormatter.Result result = ExternalFormatter.format(policy, workspace, file, content,
                    editor.configManager.getProcessTimeoutMs(), editor.configManager.getProcessOutputMaxBytes(), token);
                if (!result.succeeded()) throw new IOException(result.error());
                return new ExternalFormat(request, result.text());
            }, this::completeExternalFormat);
            return "External formatting requested (job " + jobId + ").";
        } catch (IOException error) {
            return "External formatting failed: " + error.getMessage();
        }
    }

    String showPolicyDialog() {
        FileBuffer buffer = editor.getCurrentBuffer();
        if (buffer == null || !buffer.hasFilePath()) return "Formatter policy requires a file-backed buffer";
        FormatterPolicy policy = policy(buffer);
        JDialog dialog = new JDialog(editor, "Formatter Policy — ." + policy.extension(), true);
        JPanel panel = new JPanel(new GridBagLayout());
        GridBagConstraints constraints = new GridBagConstraints();
        constraints.insets = new Insets(5, 6, 5, 6);
        constraints.anchor = GridBagConstraints.WEST;
        JComboBox<FormatterPolicy.Mode> mode = new JComboBox<>(FormatterPolicy.Mode.values());
        mode.setSelectedItem(policy.mode());
        JTextField command = new JTextField(policy.command(), 34);
        JTextField args = new JTextField(String.join(" ", policy.args()), 34);
        JCheckBox onSave = new JCheckBox("Format on save", policy.formatOnSave());
        add(panel, constraints, 0, "Mode", mode);
        add(panel, constraints, 1, "Command", command);
        add(panel, constraints, 2, "Arguments", args);
        constraints.gridx = 1; constraints.gridy = 3; panel.add(onSave, constraints);
        JButton save = new JButton("Save");
        save.addActionListener(event -> {
            FormatterPolicy.Mode selected = (FormatterPolicy.Mode) mode.getSelectedItem();
            String prefix = "formatter." + policy.extension() + ".";
            String result = editor.setConfigOptionPersistent(prefix + "mode", selected == null ? "lsp" : selected.name().toLowerCase());
            if (result.startsWith("Error")) { editor.showMessage(result); return; }
            result = editor.setConfigOptionPersistent(prefix + "command", command.getText());
            if (result.startsWith("Error")) { editor.showMessage(result); return; }
            result = editor.setConfigOptionPersistent(prefix + "args", args.getText());
            if (result.startsWith("Error")) { editor.showMessage(result); return; }
            result = editor.setConfigOptionPersistent(prefix + "format.on.save", Boolean.toString(onSave.isSelected()));
            editor.showMessage(result.startsWith("Error") ? result : "Saved formatter policy for ." + policy.extension());
            if (!result.startsWith("Error")) dialog.dispose();
        });
        JButton cancel = new JButton("Cancel"); cancel.addActionListener(event -> dialog.dispose());
        constraints.gridx = 1; constraints.gridy = 4; panel.add(save, constraints);
        constraints.gridx = 2; panel.add(cancel, constraints);
        dialog.setContentPane(panel);
        dialog.pack();
        dialog.setLocationRelativeTo(editor);
        dialog.setVisible(true);
        return "Formatter policy dialog closed";
    }

    private record ExternalFormat(Request request, String content) { }

    private void completeExternalFormat(AsyncJobService.JobSnapshot job, ExternalFormat result, Exception error) {
        if (job.getStatus() == AsyncJobService.Status.CANCELLED) { editor.showMessage("External formatting cancelled"); return; }
        if (error != null || result == null) { editor.showMessage("External formatting failed: " + (error == null ? "unknown error" : error.getMessage())); return; }
        FileBuffer buffer = result.request().buffer();
        if (buffer != editor.getCurrentBuffer() || !Objects.equals(result.request().content(), editor.writingArea.getText())) {
            editor.showMessage("External formatting became stale");
            return;
        }
        String formatted = result.content() == null ? "" : result.content();
        if (formatted.equals(result.request().content())) { editor.showMessage("No formatting changes"); return; }
        editor.paneBufferController.withSuppressedDocumentEvents(() -> buffer.setContent(formatted, true));
        editor.syncLspChange(buffer);
        editor.applySyntaxHighlighting();
        editor.refreshLineNumberPanel();
        editor.updateStatusBar();
        editor.showMessage("Applied external formatting");
    }

    private FormatterPolicy policy(FileBuffer buffer) {
        return editor.configManager.getFormatterPolicy(editor.lspController.bufferExtension(buffer));
    }

    private static void add(JPanel panel, GridBagConstraints constraints, int row, String label, java.awt.Component component) {
        constraints.gridx = 0; constraints.gridy = row; panel.add(new JLabel(label), constraints);
        constraints.gridx = 1; constraints.gridwidth = 2; constraints.fill = GridBagConstraints.HORIZONTAL; constraints.weightx = 1; panel.add(component, constraints);
        constraints.gridwidth = 1; constraints.fill = GridBagConstraints.NONE; constraints.weightx = 0;
    }
}
