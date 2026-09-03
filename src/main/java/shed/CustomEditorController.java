package shed;

import java.awt.Component;
import java.nio.file.Path;
import java.util.List;
import javax.swing.JComponent;
import shed.api.CustomEditorContribution;

/** Chooses an installed custom editor without replacing Shed's buffer and save model. */
final class CustomEditorController {
    private final Texteditor editor;

    CustomEditorController(Texteditor editor) {
        this.editor = editor;
    }

    boolean showIfAvailable(EditorPane pane, FileBuffer buffer) {
        if (pane == null || buffer == null || !buffer.hasFilePath() || editor.extensionManager == null) return false;
        Path file;
        try {
            file = Path.of(buffer.getFilePath()).toAbsolutePath().normalize();
        } catch (RuntimeException error) {
            return false;
        }
        for (ExtensionRegistry.Owned<CustomEditorContribution> owned : editor.extensionManager.customEditors()) {
            try {
                if (!owned.value().supports(file)) continue;
                JComponent component = owned.value().createComponent(file, buffer.textSnapshot().text());
                if (component == null) {
                    editor.showMessage("Custom editor " + name(owned) + " returned no component");
                    return false;
                }
                install(pane, component);
                editor.renderWindowLayout();
                editor.showMessage("Opened with custom editor " + name(owned));
                return true;
            } catch (Exception error) {
                editor.showMessage("Custom editor " + name(owned) + " failed: " + concise(error));
                return false;
            }
        }
        return false;
    }

    String handle(String argument) {
        String value = argument == null ? "" : argument.trim();
        if (value.isEmpty() || "list".equalsIgnoreCase(value)) {
            StringBuilder output = new StringBuilder("Custom Editors\n\n");
            List<ExtensionRegistry.Owned<CustomEditorContribution>> editors = editor.extensionManager == null ? List.of() : editor.extensionManager.customEditors();
            if (editors.isEmpty()) output.append("No custom editors installed.\n");
            else for (ExtensionRegistry.Owned<CustomEditorContribution> candidate : editors) {
                output.append("  ").append(name(candidate)).append("  ").append(candidate.value().displayName()).append("\n");
            }
            output.append("\nCustom editors are selected when a file is opened. They receive the file path and current buffer text; Shed remains the owner of persistence.\n");
            editor.showScratchBuffer("[custom editors]", output.toString());
            return "Showing custom editors";
        }
        if ("reopen".equalsIgnoreCase(value)) {
            return showIfAvailable(editor.getActivePane(), editor.getCurrentBuffer()) ? "Custom editor reopened" : "No installed custom editor supports the current file";
        }
        return "Usage: :customeditor [list|reopen]";
    }

    private void install(EditorPane pane, JComponent component) {
        pane.setCustomEditorComponent(component);
        component.addFocusListener(new java.awt.event.FocusAdapter() {
            @Override public void focusGained(java.awt.event.FocusEvent event) { editor.activateEditorPane(pane); }
        });
        component.addMouseListener(new java.awt.event.MouseAdapter() {
            @Override public void mousePressed(java.awt.event.MouseEvent event) { editor.activateEditorPane(pane); }
        });
    }

    private static String name(ExtensionRegistry.Owned<CustomEditorContribution> value) { return value.extensionId() + ":" + value.value().id(); }
    private static String concise(Exception error) {
        String message = error.getMessage();
        return message == null || message.isBlank() ? error.getClass().getSimpleName() : message.replace('\n', ' ').replace('\r', ' ');
    }
}
