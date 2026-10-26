package shed;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Model selectors advertised by ACP; mode/tool policy selectors deliberately remain separate. */
record AgentModelOptions(String configId, String current, List<Choice> choices, boolean legacy) {
    record Choice(String value, String name) { @Override public String toString() { return name + " · " + value; } }
    static AgentModelOptions empty() { return new AgentModelOptions("", "", List.of(), false); }
    AgentModelOptions { choices = List.copyOf(choices); }
    AgentModelOptions withCurrent(String value) { return new AgentModelOptions(configId, value, choices, legacy); }
    static AgentModelOptions parse(Map<String, Object> response) {
        if (response.get("configOptions") instanceof List<?> configs) {
            if (configs.size() > 128) throw new IllegalArgumentException("Too many agent configuration options");
            for (Object value : configs) {
                var option = MiniJson.asObject(value);
                if (option == null || !"model".equals(option.get("category")) || !"select".equals(option.get("type"))) continue;
                var choices = new ArrayList<Choice>();
                collect(option.get("options"), choices, false);
                return new AgentModelOptions(text(option, "id"), text(option, "currentValue"), choices, false);
            }
        }
        var models = MiniJson.asObject(response.get("models"));
        if (models != null && models.get("availableModels") instanceof List<?> available) {
            if (available.size() > 2000) throw new IllegalArgumentException("Too many advertised models");
            var choices = new ArrayList<Choice>();
            for (Object value : available) { var option = MiniJson.asObject(value); choices.add(new Choice(text(option, "modelId"), text(option, "name"))); }
            return new AgentModelOptions("", text(models, "currentModelId"), choices, true);
        }
        return empty();
    }
    private static void collect(Object values, List<Choice> result, boolean inGroup) {
        if (!(values instanceof List<?> options) || options.size() > 2000) throw new IllegalArgumentException("Invalid model options");
        for (Object value : options) {
            var option = MiniJson.asObject(value);
            if (option != null && option.containsKey("options") && !inGroup) collect(option.get("options"), result, true);
            else result.add(new Choice(text(option, "value"), text(option, "name")));
            if (result.size() > 2000) throw new IllegalArgumentException("Too many advertised models");
        }
    }
    private static String text(Map<String, Object> value, String key) {
        if (value == null || !(value.get(key) instanceof String text) || text.isBlank() || text.length() > 256 || text.chars().anyMatch(Character::isISOControl))
            throw new IllegalArgumentException("Invalid model selector " + key);
        return text;
    }
}
