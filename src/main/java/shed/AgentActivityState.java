package shed;

import java.util.Map;
import java.util.Set;

/** One bounded display session per attachment; names are labels, not authenticated identities. */
final class AgentActivityState {
    record State(String sessionId,String agent,String phase,long completed,long total) {
        Map<String,Object> json(){return Map.of("sessionId",sessionId,"agent",agent,"phase",phase,"completed",completed,"total",total);}
        boolean terminal(){return Set.of("complete","cancelled","failed").contains(phase);}
        String label(){return agent+" · "+phase+(total==0?"":" · "+completed+"/"+total);}
    }
    private State state;
    synchronized State update(String session,String agent,String phase,long completed,long total){
        if(!session.matches("[A-Za-z0-9_-]{1,64}") || agent.isBlank() || agent.length()>40 || agent.codePoints().anyMatch(Character::isISOControl)
                || !Set.of("reading","thinking","proposing","complete","cancelled","failed").contains(phase)
                || completed<0 || total<0 || total>1000000 || completed>total)throw invalid();
        if(state!=null && state.sessionId().equals(session) && state.terminal())throw new WorkspaceApiException(WorkspaceApiException.Code.CANCELLED,"Session is finished; start a new session ID");
        if(state!=null && !state.sessionId().equals(session) && !state.terminal())throw new WorkspaceApiException(WorkspaceApiException.Code.BUSY,"Another agent display session is active");
        state=new State(session,agent,phase,completed,total);return state;
    }
    synchronized void requireActive(String session) {
        if(state==null || !state.sessionId().equals(session) || state.terminal())throw new WorkspaceApiException(WorkspaceApiException.Code.CANCELLED,"Agent session is not active");
    }
    synchronized State cancel(String session){
        if(state==null || !state.sessionId().equals(session))throw invalid();
        state=new State(session,state.agent(),"cancelled",state.completed(),state.total());return state;
    }
    synchronized Map<String,Object> snapshot(){return state==null?Map.of("phase","idle"):state.json();}
    synchronized void close(){state=null;}
    private static WorkspaceApiException invalid(){return WorkspaceApiException.invalidRequest("Invalid agent activity");}
}
