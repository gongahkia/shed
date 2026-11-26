package shed;

import java.util.*;
import java.util.function.LongSupplier;

/** A bounded permission lease; source and process ownership stay with their existing controllers. */
final class AgentDelegation {
    private final LongSupplier clock;
    private final Set<String> retired = new LinkedHashSet<>();
    private boolean required;
    private String id = "", agent = "", task = "", state = "idle", summary = "";
    private long deadline;
    AgentDelegation() { this(System::currentTimeMillis); }
    AgentDelegation(LongSupplier clock) { this.clock = clock; }
    synchronized void requireApproval() { required = true; }
    synchronized boolean request(String request, String name, String description) {
        validate(request,name,description);
        expire();
        if (request.equals(id)) {
            if (!agent.equals(name) || !task.equals(description)) throw fail(WorkspaceApiException.Code.CONFLICT);
            return false;
        }
        if (retired.contains(request)) throw fail(WorkspaceApiException.Code.SESSION_INVALID);
        if (running()) throw fail(WorkspaceApiException.Code.BUSY);
        if (!id.isEmpty()) { retired.add(id); if(retired.size()>16) retired.remove(retired.iterator().next()); }
        required = true; id = request; agent = name; task = description; state = "awaiting-approval"; summary = "";
        deadline = clock.getAsLong() + 60_000; return true;
    }
    synchronized void approve(String request) { check(request); if(!state.equals("awaiting-approval"))throw fail(WorkspaceApiException.Code.SESSION_INVALID);state="preparing";deadline=clock.getAsLong()+45_000; }
    synchronized void activate(String request) { check(request);if(!state.equals("preparing"))throw fail(WorkspaceApiException.Code.SESSION_INVALID);state="active";deadline=clock.getAsLong()+8*60_000; }
    synchronized void finish(String request,String result) { finish(request,result,true); }
    synchronized void finish(String request,String result,boolean succeeded) {
        check(request);if(result==null||result.length()>4096||result.codePoints().anyMatch(c->Character.isISOControl(c)&&c!='\n'&&c!='\t'))throw fail(WorkspaceApiException.Code.INVALID_REQUEST);
        if (!state.equals("active")) throw fail(WorkspaceApiException.Code.SESSION_INVALID);
        state=succeeded?"completed":"failed";summary=result;
    }
    synchronized void revoke(String reason) { if(running()){state=Set.of("rejected","failed","expired").contains(reason)?reason:"cancelled";} }
    synchronized boolean allowed(String request) { expire(); return !required || state.equals("active") && id.equals(request); }
    synchronized boolean running() { expire();return Set.of("awaiting-approval","preparing","active").contains(state); }
    synchronized Map<String,Object> status(String request) { check(request);return Map.of("kind","delegation","requestId",id,"agent",agent,"task",task,"state",state,"summary",summary); }
    synchronized String state() { expire();return state; }
    synchronized String id() { return id; }
    synchronized String agent() { return agent; }
    synchronized String task() { return task; }
    private void check(String request) { expire();if(!id.equals(request)||id.isEmpty())throw fail(WorkspaceApiException.Code.SESSION_INVALID); }
    private void expire() { if(deadline>0&&clock.getAsLong()>=deadline&&Set.of("awaiting-approval","preparing","active").contains(state))state="expired"; }
    private static void validate(String id,String agent,String task) {
        if(id==null||!id.matches("[A-Za-z0-9_-]{1,64}")||agent==null||agent.isBlank()||agent.length()>40||agent.codePoints().anyMatch(Character::isISOControl)
            ||task==null||task.isBlank()||task.length()>4096||task.codePoints().anyMatch(c->Character.isISOControl(c)&&c!='\n'&&c!='\t'))throw fail(WorkspaceApiException.Code.INVALID_REQUEST);
    }
    private static WorkspaceApiException fail(WorkspaceApiException.Code code) { return EditorMetadata.failure(code); }
}
