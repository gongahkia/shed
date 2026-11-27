package shed;

import java.util.*;

/** Bounded workbench observations are distinct from canonical source/navigation responses. */
final class AgentWorkbenchContract {
    private AgentWorkbenchContract(){}
    private static final Set<String> TARGET=Set.of("id","label","role","permission","enabled","visible","length","editable","count","selected","caret","start","end","documentRevision");
    static boolean accepts(String method,Object raw){
        try{
            if(!Set.of("editor/ui/workbench-state","editor/ui/workbench-inspect","editor/ui/workbench-perform").contains(method))return false;
            var value=MiniJson.asObject(raw);if(value==null||!Long.valueOf(2).equals(value.get("schemaVersion"))||MiniJson.stringify(value).length()>28_000||!nonnegative(value.get("revision")))return false;
            if(method.endsWith("state")){
                if(!value.keySet().equals(Set.of("kind","schemaVersion","revision","targets","offset","nextOffset","total","last","busy"))||!"workbench".equals(value.get("kind"))||!(value.get("targets") instanceof List<?> targets)||targets.size()>48||!(value.get("busy") instanceof Boolean)||!event(value.get("last")))return false;
                for(String k:List.of("offset","nextOffset","total"))if(!nonnegative(value.get(k))||(Long)value.get(k)>4096)return false;
                if((Long)value.get("nextOffset")!=(Long)value.get("offset")+targets.size()||(Long)value.get("nextOffset")>(Long)value.get("total"))return false;
                var ids=new HashSet<>();for(var target:targets){var item=MiniJson.asObject(target);if(!target(item,false)||!ids.add(item.get("id")))return false;}return true;
            }
            if(method.endsWith("inspect"))return value.keySet().equals(Set.of("kind","schemaVersion","revision","target"))&&"ui-target".equals(value.get("kind"))&&target(MiniJson.asObject(value.get("target")),true);
            if(!"ui-result".equals(value.get("kind"))||!value.keySet().containsAll(Set.of("kind","schemaVersion","revision","id","stage","agent","operation","target")))return false;
            var extras=new HashSet<>(value.keySet());extras.removeAll(Set.of("kind","schemaVersion","revision","id","stage","agent","operation","target"));
            if(!Set.of("viewport","content","start","end","tooltip").containsAll(extras))return false;
            if(value.containsKey("viewport")&&!viewport(value.get("viewport")))return false;
            if(value.containsKey("content")&&(!bounded(value.get("content"),AgentUiRequest.MAX_TEXT)||!nonnegative(value.get("start"))||!nonnegative(value.get("end"))||(Long)value.get("end")-(Long)value.get("start")!=((String)value.get("content")).length()))return false;
            if(value.containsKey("tooltip")&&!bounded(value.get("tooltip"),513))return false;
            var event=new LinkedHashMap<>(value);event.keySet().retainAll(Set.of("id","stage","agent","operation","target"));return event(event);
        }catch(RuntimeException invalid){return false;}
    }
    private static boolean target(Map<String,Object> target,boolean items){
        if(target==null||!target.keySet().containsAll(Set.of("id","label","role","permission","enabled","visible")))return false;
        var fields=new HashSet<>(TARGET);if(items)fields.addAll(Set.of("items","viewport"));if(!fields.containsAll(target.keySet()))return false;
        if(!bounded(target.get("id"),160)||!bounded(target.get("label"),101)||!bounded(target.get("role"),24)||!bounded(target.get("permission"),32)||!(target.get("enabled") instanceof Boolean)||!(target.get("visible") instanceof Boolean))return false;
        for(String key:List.of("length","count","caret","start","end","documentRevision"))if(target.containsKey(key)&&!nonnegative(target.get(key)))return false;
        if(target.containsKey("editable")&&!(target.get("editable") instanceof Boolean))return false;
        if(target.containsKey("selected")&&!(target.get("selected") instanceof Long n&&n>= -1)&&!(target.get("selected") instanceof Boolean&&Set.of("button","action").contains(target.get("role"))))return false;
        if(items){
            if(!(target.get("items") instanceof List<?> rows)||rows.size()>24)return false;
            for(var row:rows){var m=MiniJson.asObject(row);if(m==null||!m.keySet().equals(Set.of("index","label"))||!nonnegative(m.get("index"))||!bounded(m.get("label"),257))return false;}
            if(target.containsKey("viewport")&&!viewport(target.get("viewport")))return false;
        }
        return true;
    }
    private static boolean event(Object raw){var m=MiniJson.asObject(raw);if(m==null)return false;if(m.equals(Map.of("stage","idle")))return true;
        if(!Set.of("id","stage","agent","operation","target","code").containsAll(m.keySet())||!m.keySet().containsAll(Set.of("id","stage","agent","operation","target")))return false;
        return nonnegative(m.get("id"))&&Set.of("requested","ui-updated","painted","failed").contains(m.get("stage"))&&bounded(m.get("agent"),40)&&AgentUiRequest.KINDS.contains(m.get("operation"))&&bounded(m.get("target"),160)&&(!m.containsKey("code")||bounded(m.get("code"),48));
    }
    private static boolean viewport(Object raw){var m=MiniJson.asObject(raw);if(m==null)return false;var keys=new HashSet<>(m.keySet());keys.remove("units");if(!keys.equals(Set.of("x","y","width","height","contentWidth","contentHeight"))||m.containsKey("units")&&!"scrollbar-model".equals(m.get("units")))return false;for(var entry:m.entrySet())if(!entry.getKey().equals("units"))if(!(entry.getValue() instanceof Long n)||n< -10_000_000||n>10_000_000||!Set.of("x","y").contains(entry.getKey())&&n<0)return false;return true;}
    private static boolean nonnegative(Object value){return value instanceof Long n&&n>=0;}
    private static boolean bounded(Object value,int length){return value instanceof String s&&s.length()<=length;}
}
