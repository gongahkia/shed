package shed;

import java.util.*;

/** Agent-reported presentation intent, never an instruction to change source or run a tool. */
record AgentBufferIntent(String kind,String status,String agent,List<AgentEditPresentation.Range> ranges,int focus) {
    static final Set<String> KINDS=Set.of("search","navigate","target","verify","conflict");
    AgentBufferIntent {
        if(kind==null||!KINDS.contains(kind)||agent==null||agent.isBlank()||agent.length()>40||agent.codePoints().anyMatch(Character::isISOControl)
                ||status==null||!(kind.equals("verify")?Set.of("checking","passed","failed").contains(status):status.equals("active"))
                ||ranges==null||ranges.isEmpty()||ranges.size()>32||focus<0||focus>=ranges.size())throw invalid();
        ranges=List.copyOf(ranges);
        for(var r:ranges)if(r.start()<0||r.end()<r.start()||r.end()>EditorControl.MAX_DOCUMENT)throw invalid();
    }
    static AgentBufferIntent parse(Object value){
        try{
            var m=MiniJson.asObject(value);
            if(m==null||!m.keySet().equals(Set.of("kind","status","agent","ranges","focus"))||!(m.get("ranges") instanceof List<?> list)||list.size()>32)throw invalid();
            var ranges=new ArrayList<AgentEditPresentation.Range>();
            for(Object item:list){var r=MiniJson.asObject(item);if(r==null||!r.keySet().equals(Set.of("start","end")))throw invalid();ranges.add(new AgentEditPresentation.Range(integer(r.get("start")),integer(r.get("end"))));}
            return new AgentBufferIntent((String)m.get("kind"),(String)m.get("status"),(String)m.get("agent"),ranges,integer(m.get("focus")));
        }catch(ClassCastException|NullPointerException invalid){throw invalid();}
    }
    private static int integer(Object value){if(!(value instanceof Long n)||n<0||n>Integer.MAX_VALUE)throw invalid();return n.intValue();}
    void validateSource(String source){
        if(source.length()>65536)throw new WorkspaceApiException(WorkspaceApiException.Code.LIMIT_EXCEEDED,"Intent source exceeds presentation limit");
        for(var r:ranges)if(r.end()>source.length()||!GraphemeBoundary.isBoundary(source,r.start())||!GraphemeBoundary.isBoundary(source,r.end()))
            throw new WorkspaceApiException(WorkspaceApiException.Code.INVALID_OFFSET,"Intent range is not a valid source boundary");
    }
    Map<String,Object> json(){return Map.of("kind",kind,"status",status,"agent",agent,"ranges",ranges.stream().map(r->Map.of("start",r.start(),"end",r.end())).toList(),"focus",focus);}
    boolean examining(){return kind.equals("navigate")||kind.equals("target");}
    String role(int index){return examining()?"navigation":kind.equals("search")?"info":kind.equals("conflict")||kind.equals("verify")&&status.equals("failed")?"error":kind.equals("verify")&&status.equals("passed")?"success":"warning";}
    String label(int index){return agent+" "+activity();}
    String activity(){return switch(kind){
        case "search"->"searching";case "navigate","target"->"examining";
        case "verify"->status.equals("checking")?"checking":"check "+status;default->"needs attention";};}
    private static WorkspaceApiException invalid(){return WorkspaceApiException.invalidRequest("Invalid buffer intent");}
}
