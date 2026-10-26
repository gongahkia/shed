package shed;

import java.time.Duration;

/** Explicit user opt-in; all four workflows use the same OpenCode installation and model. */
record InlineCompletionSettings(boolean enabled, AgentProvider provider, int delayMillis, Duration timeout) {
    InlineCompletionSettings {
        java.util.Objects.requireNonNull(provider);
        if (delayMillis < 100 || delayMillis > 5000 || timeout == null
            || timeout.compareTo(Duration.ofMillis(100)) < 0 || timeout.compareTo(Duration.ofSeconds(30)) > 0)
            throw new IllegalArgumentException("Inline completion delay or timeout is outside its supported bounds");
    }

    static InlineCompletionSettings configured(ConfigManager config) {
        var configured = AgentProvider.configured(config, "opencode");
        Duration timeout = Duration.ofMillis(Long.parseLong(config.get("ai.completion.timeout.ms", "10000")));
        var provider = new AgentProvider("opencode-completion", configured.command(),
            OpenCodePreset.completionEnvironment(configured.environment()), configured.startupTimeout(), timeout);
        return new InlineCompletionSettings(Boolean.parseBoolean(config.get("ai.completion.enabled", "false")), provider,
            Integer.parseInt(config.get("ai.completion.delay.ms", "500")), timeout);
    }
}
