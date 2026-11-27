package shed;

import java.util.*;

/** Strict, versioned wire shapes for the deliberately small UI action surface. */
final class AgentUiContract {
    private AgentUiContract() { }
    static AgentUiActions.Command command(Map<String,Object> params) {
        if (!(params.get("expectedUiRevision") instanceof Long revision)) throw invalid();
        return new AgentUiActions.Command(EditorControl.text(params,"action",40), revision,
            EditorControl.text(params,"agent",40), EditorControl.text(params,"text",AgentUiActions.MAX_TEXT));
    }
    static boolean accepts(Object value, boolean catalog) {
        try {
            var state = MiniJson.asObject(value);
            if (state == null || !Long.valueOf(1).equals(state.get("schemaVersion"))) return false;
            if (catalog) {
                if (!state.keySet().equals(Set.of("kind","schemaVersion","actions")) || !"ui-actions".equals(state.get("kind"))
                    || !(state.get("actions") instanceof List<?> actions) || actions.size() != AgentUiActions.IDS.size()) return false;
                var ids = new HashSet<String>();
                for (Object item : actions) {
                    var action = MiniJson.asObject(item);
                    if (action == null || !action.keySet().equals(Set.of("id","kind","enabled")) || !(action.get("id") instanceof String id)
                        || !AgentUiActions.IDS.contains(id) || !ids.add(id) || !(action.get("enabled") instanceof Boolean)
                        || !Objects.equals(id.equals("find.query") ? "text" : "control",action.get("kind"))) return false;
                }
                return true;
            }
            if (!state.keySet().equals(Set.of("kind","schemaVersion","revision","visible","mode","query","caret","start","end",
                "case","regex","word","phase","matches","selected","truncated")) || !"ui".equals(state.get("kind"))
                || !(state.get("revision") instanceof Long revision) || revision < 0
                || !(state.get("query") instanceof String text) || text.length() > AgentUiActions.MAX_TEXT
                || !Set.of("find","replace","go_to_line").contains(state.get("mode"))
                || !Set.of("idle","searching","ready","error").contains(state.get("phase"))) return false;
            for (String key : List.of("visible","case","regex","word","truncated")) if (!(state.get(key) instanceof Boolean)) return false;
            for (String key : List.of("caret","start","end")) if (!(state.get(key) instanceof Long n) || n < 0 || n > text.length()) return false;
            return (Long)state.get("start") <= (Long)state.get("end")
                && state.get("matches") instanceof Long matches && matches >= 0 && matches <= 200_000
                && state.get("selected") instanceof Long selected && selected >= -1 && selected < matches;
        } catch (RuntimeException invalid) { return false; }
    }
    private static WorkspaceApiException invalid() { return new WorkspaceApiException(WorkspaceApiException.Code.INVALID_REQUEST,"Invalid UI request"); }
}
