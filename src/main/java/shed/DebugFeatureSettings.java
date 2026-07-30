package shed;

record DebugFeatureSettings(boolean enabled, boolean breakpoints, boolean threads, boolean stackTrace, boolean scopes, boolean variables,
    boolean evaluate, boolean attach) {
    static DebugFeatureSettings defaults() {
        return new DebugFeatureSettings(false, true, true, true, true, true, true, true);
    }
}
