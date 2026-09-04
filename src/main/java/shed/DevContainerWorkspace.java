package shed;

/** Validates the runtime workspace path reported by the user-installed Dev Container CLI. */
final class DevContainerWorkspace {
    private DevContainerWorkspace() { }

    static String remoteWorkingDirectory(String output) {
        if (output == null) throw new IllegalArgumentException("devcontainer exec produced no workspace path");
        String candidate = "";
        for (String line : output.split("\\R")) {
            if (line.startsWith("/")) candidate = line;
        }
        if (!candidate.startsWith("/") || candidate.indexOf('\u0000') >= 0 || candidate.indexOf('\n') >= 0 || candidate.indexOf('\r') >= 0) {
            throw new IllegalArgumentException("devcontainer exec did not report an absolute POSIX workspace path");
        }
        return candidate;
    }
}
