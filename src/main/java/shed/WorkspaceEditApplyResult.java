package shed;

final class WorkspaceEditApplyResult {
    int appliedEditCount;
    int appliedResourceOperationCount;
    int touchedFiles;
    int failedFiles;
    String failureReason;

    WorkspaceEditApplyResult() {
        this.appliedEditCount = 0;
        this.appliedResourceOperationCount = 0;
        this.touchedFiles = 0;
        this.failedFiles = 0;
        this.failureReason = "";
    }
}
