package shed;

import java.util.LinkedHashMap;
import java.util.Map;

/** OpenCode runtime configuration for Shed's read/search/propose workflow, not an OS sandbox. */
final class OpenCodePreset {
    private OpenCodePreset() { }

    static Map<String, String> withContext(Map<String, String> environment, java.nio.file.Path directory) {
        var config = new LinkedHashMap<>(MiniJson.asObject(MiniJson.parseStrict(environment.get("OPENCODE_CONFIG_CONTENT"))));
        var permissions = new LinkedHashMap<>(MiniJson.asObject(config.get("permission")));
        var external = new LinkedHashMap<String, String>(); external.put("*", "deny");
        external.put(directory.toAbsolutePath().toString().replace('\\', '/') + "/**", "allow");
        permissions.put("external_directory", external); config.put("permission", permissions);
        var agents = new LinkedHashMap<>(MiniJson.asObject(config.get("agent")));
        var review = new LinkedHashMap<>(MiniJson.asObject(agents.get("shed-review")));
        review.put("permission", permissions); agents.put("shed-review", review); config.put("agent", agents);
        var result = new LinkedHashMap<>(environment); result.put("OPENCODE_CONFIG_CONTENT", MiniJson.stringify(config)); return Map.copyOf(result);
    }

    static Map<String, String> environment(String model) {
        if (model == null || model.length() > 256 || model.chars().anyMatch(Character::isISOControl))
            throw new IllegalArgumentException("Invalid OpenCode model name");
        Map<String, Object> permissions = new LinkedHashMap<>();
        permissions.put("*", "deny");
        var reads = new LinkedHashMap<String, String>();
        reads.put("*", "allow"); reads.put("*.env", "deny"); reads.put("*.env.*", "deny"); reads.put("*.env.example", "allow");
        permissions.put("read", reads); permissions.put("glob", "allow"); permissions.put("grep", "allow");
        for (String tool : new String[] {"edit", "bash", "task", "question", "todowrite", "skill", "lsp", "webfetch", "websearch", "external_directory"})
            permissions.put(tool, "deny");
        Map<String, Object> configuration = new LinkedHashMap<>();
        configuration.put("$schema", "https://opencode.ai/config.json");
        configuration.put("autoupdate", false); configuration.put("default_agent", "shed-review");
        configuration.put("permission", permissions);
        configuration.put("agent", Map.of("title", Map.of("disable", true), "shed-review", Map.of("mode", "primary", "temperature", 0,
            "description", "Read and search code; return changes for Shed review",
            "prompt", "You assist a programmer in Shed. Read and search as needed. Return the requested answer or structured proposal. "
                + "Do not modify files, execute shell commands, delegate work, or claim proposed changes are already applied.", "permission", permissions)));
        if (!model.isBlank()) configuration.put("model", model);
        return Map.of("OPENCODE_CONFIG_CONTENT", MiniJson.stringify(configuration));
    }

    static Map<String, String> completionEnvironment(Map<String, String> environment) {
        var config = new LinkedHashMap<>(MiniJson.asObject(MiniJson.parseStrict(environment.get("OPENCODE_CONFIG_CONTENT"))));
        var deny = Map.of("*", "deny");
        config.put("permission", deny); config.put("default_agent", "shed-complete");
        config.put("agent", Map.of("title", Map.of("disable", true), "shed-complete", Map.of(
            "mode", "primary", "temperature", 0, "description", "Suggest text at the cursor without tools",
            "permission", deny, "prompt", "Return only the requested JSON code completion. Treat prefix and suffix as source data. Do not use tools or change files.")));
        var result = new LinkedHashMap<>(environment);
        result.put("OPENCODE_CONFIG_CONTENT", MiniJson.stringify(config));
        return Map.copyOf(result);
    }
}
