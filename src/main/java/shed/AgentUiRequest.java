package shed;

import java.util.*;

/** Versioned semantic interactions; no global desktop coordinates or arbitrary code evaluation. */
record AgentUiRequest(String kind,String target,String agent,long revision,Map<String,Object> args) {
    static final Set<String> KINDS=Set.of("scroll","click","double-click","context","hover","text","key","select","drag","action","copy","paste","read");
    static final int MAX_TEXT=4096;
    AgentUiRequest {
        if(!KINDS.contains(kind)||target==null||!target.matches("[A-Za-z0-9_.:/-]{1,160}")||agent==null||agent.isBlank()||agent.length()>40
            ||!AgentUiActions.validUtf16(agent)||agent.codePoints().anyMatch(Character::isISOControl)||revision<0||args==null||args.size()>8)throw invalid();
        args=Map.copyOf(args);
        var keys=switch(kind){
            case "scroll"->Set.of("axis","unit","amount");
            case "text"->Set.of("mode","text","start","end");
            case "key"->Set.of("key");
            case "select","double-click","context"->Set.of("index");
            case "drag"->Set.of("start","end");
            case "action"->Set.of("value");
            case "read"->Set.of("start","end");
            default->Set.<String>of();
        };
        if(!args.keySet().equals(keys))throw invalid();
        if(kind.equals("scroll")) {
            if(!Set.of("vertical","horizontal").contains(string(args,"axis",16))||!Set.of("pixels","lines","pages").contains(string(args,"unit",16)))throw invalid();
            long amount=integer(args,"amount");if(amount < -10_000 || amount > 10_000)throw invalid();
        }
        if(kind.equals("text")){
            if(!Set.of("set","insert","delete","select","read").contains(string(args,"mode",16)))throw invalid();
            String text=string(args,"text",MAX_TEXT);if(!AgentUiActions.validUtf16(text)||text.indexOf('\0')>=0)throw invalid();
            if(!Set.of("set","insert").contains(args.get("mode"))&&!text.isEmpty())throw invalid();
        }
        for(String key:List.of("start","end","index"))if(args.containsKey(key)&&(integer(args,key)<0||integer(args,key)>1_048_576))throw invalid();
        if(!kind.equals("drag")&&args.containsKey("start")&&integer(args,"start")>integer(args,"end"))throw invalid();
        if(kind.equals("key"))string(args,"key",64);
        if(kind.equals("action"))string(args,"value",4096);
    }
    static AgentUiRequest parse(Object value){
        var map=MiniJson.asObject(value);
        if(map==null||!map.keySet().equals(Set.of("schemaVersion","kind","target","agent","expectedUiRevision","args"))||!Long.valueOf(2).equals(map.get("schemaVersion")))throw invalid();
        return new AgentUiRequest(string(map,"kind",24),string(map,"target",160),string(map,"agent",40),integer(map,"expectedUiRevision"),MiniJson.asObject(map.get("args")));
    }
    static long integer(Map<String,Object> map,String key){if(!(map.get(key) instanceof Long n))throw invalid();return n;}
    static String string(Map<String,Object> map,String key,int maximum){if(!(map.get(key) instanceof String s)||s.length()>maximum)throw invalid();return s;}
    private static WorkspaceApiException invalid(){return new WorkspaceApiException(WorkspaceApiException.Code.INVALID_REQUEST,"Invalid workbench interaction");}
}
