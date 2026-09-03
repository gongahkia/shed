package shed.api;

/** Result of an explicitly requested command in a connected remote workspace. */
public record RemoteCommandResult(int exitCode, String output) {
    public RemoteCommandResult {
        output = output == null ? "" : output;
    }
}
