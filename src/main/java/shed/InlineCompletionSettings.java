package shed;

import java.net.URI;
import java.time.Duration;
import java.util.Set;

/** Explicit user configuration; project settings cannot opt into sending editor text. */
record InlineCompletionSettings(boolean enabled, URI endpoint, String model, int delayMillis, Duration timeout) {
    InlineCompletionSettings {
        if (endpoint == null || !"http".equals(endpoint.getScheme())
            || !Set.of("localhost", "127.0.0.1", "[::1]").contains(endpoint.getHost())
            || endpoint.getUserInfo() != null || endpoint.getQuery() != null || endpoint.getFragment() != null
            || !"/api/generate".equals(endpoint.getPath()))
            throw new IllegalArgumentException("Inline completion requires a local Ollama /api/generate endpoint");
        // Use a literal address for localhost so a DNS/proxy configuration cannot redirect source text.
        if ("localhost".equals(endpoint.getHost()))
            endpoint = URI.create("http://127.0.0.1" + (endpoint.getPort() < 0 ? "" : ":" + endpoint.getPort()) + "/api/generate");
        if (model == null || model.length() > 256 || model.chars().anyMatch(Character::isISOControl)
            || enabled && model.isBlank()) throw new IllegalArgumentException("Choose an installed fill-in-the-middle model for inline completion");
        if (delayMillis < 100 || delayMillis > 5000 || timeout == null
            || timeout.compareTo(Duration.ofMillis(100)) < 0 || timeout.compareTo(Duration.ofSeconds(30)) > 0)
            throw new IllegalArgumentException("Inline completion delay or timeout is outside its supported bounds");
    }

    static InlineCompletionSettings configured(ConfigManager config) {
        return new InlineCompletionSettings(Boolean.parseBoolean(config.get("ai.completion.enabled", "false")),
            URI.create(config.get("ai.completion.endpoint", "http://127.0.0.1:11434/api/generate")),
            config.get("ai.completion.model", ""), Integer.parseInt(config.get("ai.completion.delay.ms", "500")),
            Duration.ofMillis(Long.parseLong(config.get("ai.completion.timeout.ms", "5000"))));
    }
}
