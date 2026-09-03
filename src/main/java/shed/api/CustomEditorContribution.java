package shed.api;

import java.nio.file.Path;
import javax.swing.JComponent;

/** A custom, read/write-capable visual representation for a file type. */
public interface CustomEditorContribution {
    String id();

    String displayName();

    boolean supports(Path file);

    JComponent createComponent(Path file, String content) throws Exception;
}
