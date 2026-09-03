package shed.api;

import java.nio.file.Path;
import javax.swing.JComponent;

/** A custom visual representation for a text or binary file type. */
public interface CustomEditorContribution {
    String id();

    String displayName();

    boolean supports(Path file);

    /**
     * Legacy text-editor callback. New editors should override
     * {@link #createComponent(CustomEditorDocument)} to receive bytes and an
     * explicit atomic-save operation.
     */
    JComponent createComponent(Path file, String content) throws Exception;

    /**
     * Creates a custom editor using Shed's resource model. The default keeps
     * API-v1 text editors source-compatible.
     */
    default JComponent createComponent(CustomEditorDocument document) throws Exception {
        return createComponent(document.file(), document.text());
    }
}
