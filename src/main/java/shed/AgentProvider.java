package shed;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Explicit invocation, captured before launch; arguments are never interpreted by a shell. */
record AgentProvider(String id, List<String> command, Map<String, String> environment,
                     Duration startupTimeout, Duration turnTimeout) {
    AgentProvider {
        if (id == null || !id.matches("[A-Za-z0-9_-]{1,64}")) throw new IllegalArgumentException("Invalid agent provider name");
        command = List.copyOf(command); environment = Map.copyOf(environment);
        if (command.isEmpty() || command.size() > 128 || command.getFirst().isBlank()
            || command.stream().anyMatch(value -> value.length() > 32768 || value.indexOf('\0') >= 0))
            throw new IllegalArgumentException("A bounded agent command is required");
        if (environment.size() > 128 || environment.entrySet().stream().anyMatch(entry -> !entry.getKey().matches("[A-Za-z_][A-Za-z0-9_]*")
            || entry.getValue().length() > 32768 || entry.getValue().indexOf('\0') >= 0)) throw new IllegalArgumentException("Invalid agent environment");
        if (startupTimeout.compareTo(Duration.ofMillis(100)) < 0 || startupTimeout.compareTo(Duration.ofMinutes(2)) > 0
            || turnTimeout.compareTo(Duration.ofMillis(100)) < 0 || turnTimeout.compareTo(Duration.ofHours(24)) > 0)
            throw new IllegalArgumentException("Invalid agent timeout");
    }

    static AgentProvider configured(ConfigManager config, String id) {
        if (id == null || !id.matches("[A-Za-z0-9_-]{1,64}")) throw new IllegalArgumentException("Invalid agent provider name");
        String prefix = "agent." + id + ".", executable = config.get(prefix + "command");
        if (executable == null || executable.isBlank()) throw new IllegalArgumentException("Configure " + prefix + "command before connecting");
        List<String> command = new ArrayList<>(ShellCommand.directCommand(executable));
        String args = config.get(prefix + "args"); if (args != null && !args.isBlank()) command.addAll(ShellCommand.directCommand(args));
        return new AgentProvider(id, command, Map.of(), Duration.ofMillis(timeout(config, prefix + "startup.timeout.ms", 15000)),
            Duration.ofMillis(timeout(config, prefix + "turn.timeout.ms", 600000)));
    }
    private static long timeout(ConfigManager config, String key, long fallback) {
        String value = config.get(key); return value == null ? fallback : Long.parseLong(value);
    }
    @Override public String toString() { return "AgentProvider[" + id + "]"; }
}
