package shed;

final class WorkspaceEditApplyResult {
    int appliedEditCount;
    int touchedFiles;
    int failedFiles;

    WorkspaceEditApplyResult() {
        this.appliedEditCount = 0;
        this.touchedFiles = 0;
        this.failedFiles = 0;
    }
}
