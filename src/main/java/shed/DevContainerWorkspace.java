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
        return requireAbsolutePosixPath(candidate);
    }

    static String requireAbsolutePosixPath(String value) {
        if (value == null || value.isBlank() || value.indexOf('\u0000') >= 0 || value.indexOf('\n') >= 0 || value.indexOf('\r') >= 0) {
            throw new IllegalArgumentException("devcontainer exec did not report an absolute POSIX workspace path");
        }
        String candidate = value.trim();
        if (!candidate.startsWith("/") || candidate.length() > 16 * 1024) {
            throw new IllegalArgumentException("devcontainer exec did not report an absolute POSIX workspace path");
        }
        return candidate.length() > 1 && candidate.endsWith("/") ? candidate.substring(0, candidate.length() - 1) : candidate;
    }
}
