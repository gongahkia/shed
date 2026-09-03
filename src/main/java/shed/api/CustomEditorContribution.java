package shed.api;

import java.nio.file.Path;
import javax.swing.JComponent;

/** A custom visual representation for a file type; Shed remains the persistence owner. */
public interface CustomEditorContribution {
    String id();

    String displayName();

    boolean supports(Path file);

    JComponent createComponent(Path file, String content) throws Exception;
}
