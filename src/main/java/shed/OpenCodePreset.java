package shed;

import java.util.LinkedHashMap;
import java.util.Map;

/** OpenCode runtime configuration for Shed's read/search/propose workflow, not an OS sandbox. */
final class OpenCodePreset {
    private OpenCodePreset() { }

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
        configuration.put("agent", Map.of("shed-review", Map.of("mode", "primary", "description", "Read and search code; return changes for Shed review",
            "prompt", "You assist a programmer in Shed. Read and search as needed. Return the requested answer or structured proposal. "
                + "Do not modify files, execute shell commands, delegate work, or claim proposed changes are already applied.", "permission", permissions)));
        if (!model.isBlank()) configuration.put("model", model);
        return Map.of("OPENCODE_CONFIG_CONTENT", MiniJson.stringify(configuration));
    }
}
