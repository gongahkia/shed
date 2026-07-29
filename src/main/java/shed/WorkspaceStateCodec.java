package shed;

final class WorkspaceStateCodec {
    private WorkspaceState lastKnownGood;

    LoadResult read(String json) {
        try {
            WorkspaceState state = WorkspaceState.parse(json);
            lastKnownGood = state;
            return new LoadResult(state, false, null);
        } catch (IllegalArgumentException error) {
            return new LoadResult(lastKnownGood, lastKnownGood != null, error.getMessage());
        }
    }

    WorkspaceState lastKnownGood() {
        return lastKnownGood;
    }

    record LoadResult(WorkspaceState state, boolean retainedLastKnownGood, String error) {
        boolean accepted() {
            return error == null;
        }
    }
}
