package shed;

import java.nio.file.Path;
import java.util.*;
import java.util.function.BooleanSupplier;
import javax.swing.*;

/** Per-attachment command requests; every execution has its own visible human decision. */
final class AgentTerminalRequests implements AutoCloseable {
    private final Texteditor editor;
    private final Map<String,Request> requests=new LinkedHashMap<>();
    private static final class Request {
        final String owner,id,agent,body;final Path root;final long generation;final AgentCommandPlan plan;final BooleanSupplier authorized;
        String state="awaiting-approval",output="",error="";int job=-1;JDialog dialog;javax.swing.Timer expiry;
        Request(String owner,String id,String agent,String body,Path root,long generation,AgentCommandPlan plan,BooleanSupplier authorized){this.owner=owner;this.id=id;this.agent=agent;this.body=body;this.root=root;this.generation=generation;this.plan=plan;this.authorized=authorized;}
        Map<String,Object> snapshot(){return Map.of("requestId",id,"agent",agent,"state",state,"result",boundedResult(output),"error",error);}
    }
    static Object boundedResult(String text){
        if(text.isEmpty())return Map.of();
        var result=MiniJson.asObject(MiniJson.parseStrict(text));var entries=MiniJson.asArray(result.get("results"));
        var bounded=new ArrayList<Map<String,Object>>();
        for(var value:entries){
            var entry=new LinkedHashMap<>(MiniJson.asObject(value));String output=Objects.toString(entry.get("output"),"");
            String error=Objects.toString(entry.get("error"),"");if(error.length()>512)entry.put("error",error.substring(0,512));
            while(MiniJson.stringify(entry).getBytes(java.nio.charset.StandardCharsets.UTF_8).length>4096){
                output=output.substring(0,GraphemeBoundary.floor(output,output.length()/2));
                entry.put("output",output+"\n[Shed: terminal feedback truncated; inspect the terminal scrollback]");
            }
            bounded.add(entry);
        }
        return Map.of("approved",true,"results",bounded);
    }
    AgentTerminalRequests(Texteditor editor){this.editor=editor;}
    Map<String,Object> action(String owner,String action,String id,String agent,String body,BooleanSupplier authorized){
        if(!SwingUtilities.isEventDispatchThread())throw new IllegalStateException("Command requests belong on the EDT");
        if(!id.matches("[A-Za-z0-9_-]{1,64}"))throw fail("Invalid terminal request ID");
        var existing=requests.get(id);
        if(action.equals("request")){
            if(agent.isBlank()||agent.length()>40||agent.codePoints().anyMatch(Character::isISOControl)||body.length()>24000)throw fail("Invalid terminal request");
            if(existing!=null){if(!existing.owner.equals(owner)||!existing.body.equals(body)||!existing.agent.equals(agent))throw fail("Terminal request ID is already used");return existing.snapshot();}
            if(requests.values().stream().anyMatch(r->r.state.equals("running")||r.state.equals("awaiting-approval")))throw new WorkspaceApiException(WorkspaceApiException.Code.BUSY,"A terminal command request is already active");
            while(requests.size()>=8)requests.remove(requests.keySet().iterator().next());
            Path root=editor.workspaceController.activeRoot();if(root==null)throw fail("Select a workspace folder first");
            var plan=AgentCommandPlan.requested(body,editor.workspaceQueries(root,AgentPolicy.readOnly()));if(plan==null)throw fail("A command plan is required");
            var request=new Request(owner,id,agent,body,root,editor.workspaceRoots.generation(),plan,authorized);requests.put(id,request);
            SwingUtilities.invokeLater(()->review(request));return request.snapshot();
        }
        if(existing==null||!existing.owner.equals(owner))throw fail("Terminal request is unavailable");
        if(action.equals("cancel"))cancel(existing,"Cancelled by agent");else if(!action.equals("status"))throw fail("Unknown terminal action");
        return existing.snapshot();
    }
    private boolean current(Request request){return request.authorized.getAsBoolean()&&!editor.closingDown&&request.generation==editor.workspaceRoots.generation()&&request.root.equals(editor.workspaceController.activeRoot());}
    private void review(Request request){
        if(!request.state.equals("awaiting-approval"))return;
        if(!current(request)){cancel(request,"Workspace or authorization changed");return;}
        var details=new JTextArea(request.plan.describe()+"\nThese commands will be visibly typed into the normal Shed terminal. Human keyboard input takes control.");details.setEditable(false);details.setLineWrap(true);details.setWrapStyleWord(true);details.setRows(16);details.setColumns(70);details.setFont(editor.resolveEditorFont());
        var option=new JOptionPane(new JScrollPane(details),JOptionPane.QUESTION_MESSAGE,JOptionPane.DEFAULT_OPTION,null,new Object[]{"Run once","Reject"},"Reject");
        request.dialog=option.createDialog(editor,request.agent+" requests terminal commands");request.dialog.setModal(false);
        option.addPropertyChangeListener(event->{
            if(!event.getPropertyName().equals(JOptionPane.VALUE_PROPERTY)||option.getValue()==JOptionPane.UNINITIALIZED_VALUE||!request.state.equals("awaiting-approval"))return;
            boolean accepted="Run once".equals(option.getValue());disposeReview(request);
            if(accepted&&current(request))run(request);else{request.state="rejected";request.error=accepted?"Workspace or authorization changed":"Commands were not approved";}
        });
        request.dialog.addWindowListener(new java.awt.event.WindowAdapter(){@Override public void windowClosing(java.awt.event.WindowEvent event){cancel(request,"Approval dismissed");}});
        request.expiry=new javax.swing.Timer(60000,event->cancel(request,"Approval expired"));request.expiry.setRepeats(false);request.expiry.start();request.dialog.setVisible(true);
    }
    private void run(Request request){
        request.state="running";
        request.job=editor.asyncJobService.submitLongRunning("Agent terminal commands",token->{
            if(!request.authorized.getAsBoolean())throw new java.util.concurrent.CancellationException();
            var policy=new AgentPolicy(Set.of(AgentPolicy.Capability.READ,AgentPolicy.Capability.RUN_HEADLESS,AgentPolicy.Capability.RUN_INTERACTIVE,AgentPolicy.Capability.OPEN_UI),List.of(),List.of("**/*.env","**/*.env.*"),false);
            var queries=editor.workspaceQueries(request.root,policy);
            return request.plan.execute(queries,token,(agent,command,cancel)->{
                if(!request.authorized.getAsBoolean())throw new java.util.concurrent.CancellationException();
                return editor.runAgentCommand(agent,command,cancel);
            },request.agent);
        },(job,result,error)->{
            if(!request.state.equals("running"))return;
            request.state=job.getStatus()==AsyncJobService.Status.CANCELLED?"cancelled":error==null?"completed":"failed";
            request.output=result==null?"":result;request.error=error==null?"":Objects.toString(error.getMessage(),"Command failed");
            if(request.error.length()>1024)request.error=request.error.substring(0,1024);
        });
    }
    private void disposeReview(Request request){if(request.expiry!=null){request.expiry.stop();request.expiry=null;}if(request.dialog!=null){var dialog=request.dialog;request.dialog=null;dialog.dispose();}}
    private void cancel(Request request,String reason){if(!request.state.equals("running")&&!request.state.equals("awaiting-approval"))return;request.state="cancelled";request.error=reason;disposeReview(request);if(request.job>=0)editor.asyncJobService.cancel(request.job);}
    void revoke(String owner){for(var request:List.copyOf(requests.values()))if(request.owner.equals(owner)){cancel(request,"Terminal authorization revoked");requests.remove(request.id);}}
    public void close(){for(var request:requests.values())cancel(request,"Shed is closing");requests.clear();}
    private static WorkspaceApiException fail(String text){return new WorkspaceApiException(WorkspaceApiException.Code.INVALID_REQUEST,text);}
}
