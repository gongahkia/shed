package shed.api;

import java.nio.file.Path;
import java.util.List;

/** Direct process arguments and optional report paths for a test operation. */
public record TestCommand(List<String> arguments, List<Path> reports) {
    public TestCommand {
        arguments = arguments == null ? List.of() : List.copyOf(arguments);
        reports = reports == null ? List.of() : List.copyOf(reports);
        for (String argument : arguments) {
            if (argument == null || argument.isBlank() || argument.indexOf('\0') >= 0 || argument.indexOf('\n') >= 0 || argument.indexOf('\r') >= 0) {
                throw new IllegalArgumentException("test command arguments must be non-empty single-line values");
            }
        }
    }
}
