package shed;

final class CommandResult {
    final int exitCode;
    final String stdout;
    final String stderr;

    CommandResult(int exitCode, String stdout, String stderr) {
        this.exitCode = exitCode;
        this.stdout = stdout == null ? "" : stdout;
        this.stderr = stderr == null ? "" : stderr;
    }
}
