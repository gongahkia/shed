package shed;

/** Result of an explicitly requested command in a connected remote workspace. */
record RemoteCommandResult(int exitCode, String output) {
    public RemoteCommandResult {
        output = output == null ? "" : output;
    }
}
