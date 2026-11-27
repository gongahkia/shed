package shed;

/** Session-local permissions, explicitly selected at the existing project handoff. */
enum AgentUiPermission {
    NAVIGATE("Navigate, read and inspect this project"),
    EDIT("Edit and save this project's source files"),
    FILES("Create, rename and delete project files"),
    VCS("Run version-control actions for this project"),
    TOOLS("Run project terminals, tasks, tests and debugging"),
    APPEARANCE("Change Shed appearance and settings"),
    SYSTEM_CLIPBOARD("Use the system clipboard (otherwise session-private)");
    final String label;
    AgentUiPermission(String label){this.label=label;}
}
