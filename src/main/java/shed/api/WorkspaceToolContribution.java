package shed.api;

import java.nio.file.Path;
import java.util.List;

/** A workspace-aware database, deployment, collaboration, or container integration. */
public interface WorkspaceToolContribution {
    String id();

    String displayName();

    WorkspaceToolKind kind();

    /** Returns true only for workspaces this provider can safely handle. */
    boolean supports(Path workspaceRoot);

    /** Shed exposes only these declared action ids to users. */
    List<WorkspaceToolAction> actions();

    /** Executes one declared action after explicit user selection. */
    String execute(Path workspaceRoot, String action, String arguments) throws Exception;
}
