package shed.api;

/** An explicit, provider-declared action available in a workspace integration. */
public record WorkspaceToolAction(String id, String displayName) {
    public WorkspaceToolAction {
        if (id == null || !id.matches("[A-Za-z0-9._-]+")) {
            throw new IllegalArgumentException("workspace tool action id is invalid");
        }
        if (displayName == null || displayName.isBlank()) {
            throw new IllegalArgumentException("workspace tool action display name is required");
        }
    }
}
