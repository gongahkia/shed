package shed.api;

import java.nio.file.Path;
import java.util.List;

/** A source-control provider that contributes status and explicit actions. */
public interface ScmContribution {
    String id();

    String displayName();

    boolean supports(Path workspaceRoot);

    String status(Path workspaceRoot) throws Exception;

    List<String> actions();

    String execute(Path workspaceRoot, String action, String arguments) throws Exception;
}
