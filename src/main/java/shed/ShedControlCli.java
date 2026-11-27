package shed;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** Small human/script frontend to the existing explicitly authorized editor-control attachment. */
final class ShedControlCli {
    private ShedControlCli() { }
    static int run(String[] args, InputStream input, OutputStream output, OutputStream errors) {
        var out = new PrintWriter(new OutputStreamWriter(output, StandardCharsets.UTF_8), true);
        boolean mutationSent = false;
        try {
            var options = new LinkedHashMap<String,String>(); String command = null, path = null;
            for (int i=1;i<args.length;i++) {
                String arg=args[i];
                if (arg.equals("--json")) continue;
                if (arg.startsWith("--")) {
                    if (!Set.of("--connect","--credential","--offset","--limit","--start","--end","--revision","--document","--text-file","--request-id","--session-id","--agent","--phase","--completed","--total","--changes-file","--commands-file","--output","--kind","--status","--ranges","--focus","--sha256","--timeout-ms","--delegation","--task-file","--ui-revision","--args-file","--steps-file","--axis","--unit","--amount","--mode","--key","--index","--value-file").contains(arg)
                            || ++i==args.length || options.putIfAbsent(arg,args[i])!=null) throw new IllegalArgumentException();
                } else if(command==null) command=arg;
                else if(path==null) path=arg;
                else throw new IllegalArgumentException();
            }
            if(command==null) throw new IllegalArgumentException();
            String endpoint=options.remove("--connect");
            if(endpoint==null) endpoint=System.getenv("SHED_CONTROL");
            if(endpoint==null || endpoint.isBlank()) throw new IllegalArgumentException();
            Path descriptor=Path.of(endpoint); var identity=WorkspaceStatusAttachment.Descriptor.read(descriptor);
            String key=options.remove("--credential");
            Path credential=key==null?descriptor.resolveSibling("status-"+identity.attachmentId()+".key"):Path.of(key);
            try(var client=WorkspaceBridgeClient.control(descriptor,credential)) {
                String delegation=options.remove("--delegation");if(delegation==null)delegation=System.getenv("SHED_DELEGATION");client.delegation(delegation);
                if(ShedWorkbenchCli.COMMANDS.contains(command)){mutationSent=!ShedWorkbenchCli.readonly(command);return ShedWorkbenchCli.run(client,identity,command,path,options,input,out);}
                if(Set.of("session-request","session-status","session-finish").contains(command)) {
                    if(path!=null)throw new IllegalArgumentException();String id=required(options,"--request-id"),agent="",task="";
                    if(command.equals("session-request")) { agent=required(options,"--agent");String file=required(options,"--task-file");task=WorkspaceFrames.decode(file.equals("-")?input.readNBytes(4097):boundedFile(Path.of(file),4096)); }
                    if(command.equals("session-finish") && options.containsKey("--task-file")){String file=required(options,"--task-file");task=WorkspaceFrames.decode(file.equals("-")?input.readNBytes(4097):boundedFile(Path.of(file),4096));}
                    if(!id.matches("[A-Za-z0-9_-]{1,64}")||!options.isEmpty())throw new IllegalArgumentException();
                    mutationSent=!command.equals("session-status");
                    return emit(client.exchange(request("editor/delegation",Map.of("schemaVersion",1,"instanceId",identity.instanceId(),"windowId",identity.windowId(),"projectId",identity.projectId(),"action",command.substring(8),"requestId",id,"agent",agent,"task",task))),out);
                }
                if (command.equals("sync")) {
                    if (path == null) throw new IllegalArgumentException();
                    String hash = required(options,"--sha256"); long timeout = number(options,"--timeout-ms",2500);
                    if (!hash.matches("[a-fA-F0-9]{64}") || timeout < 50 || timeout > 3000 || !options.isEmpty()) throw new IllegalArgumentException();
                    return emit(client.exchange(request("editor/source/sync",Map.of("schemaVersion",1,"instanceId",identity.instanceId(),
                        "windowId",identity.windowId(),"projectId",identity.projectId(),"path",path,"sha256",hash,"timeoutMs",timeout))),out);
                }
                if(command.equals("state")) {
                    if(path!=null || !options.isEmpty()) throw new IllegalArgumentException();
                    return emit(client.exchange(client.navigationContextRequest()),out);
                }
                if(Set.of("terminal-run","terminal-status","terminal-cancel").contains(command)){
                    if(path!=null)throw new IllegalArgumentException();
                    String id=options.remove("--request-id"),agent="",body="";
                    String action=command.equals("terminal-run")?"request":command.equals("terminal-status")?"status":"cancel";
                    if(action.equals("request")){
                        String file=required(options,"--commands-file");byte[] bytes=file.equals("-")?input.readNBytes(24001):boundedFile(Path.of(file),24000);if(bytes.length>24000)throw new IllegalArgumentException();
                        body=WorkspaceFrames.decode(bytes);MiniJson.parseStrict(body);agent=options.remove("--agent");if(agent==null)agent="Agent";if(id==null)id=UUID.randomUUID().toString();
                    }
                    if(id==null||!id.matches("[A-Za-z0-9_-]{1,64}")||!options.isEmpty())throw new IllegalArgumentException();
                    mutationSent=!action.equals("status");
                    return emit(client.exchange(request("editor/terminal",Map.of("schemaVersion",1,"instanceId",identity.instanceId(),"windowId",identity.windowId(),"projectId",identity.projectId(),"action",action,"requestId",id,"agent",agent,"commands",body))),out);
                }
                if(Set.of("watch","watch-state","watch-stop","follow","pause").contains(command)){
                    if(path!=null)throw new IllegalArgumentException();String name=options.remove("--agent");if(!options.isEmpty())throw new IllegalArgumentException();
                    String action=switch(command){case "watch"->"start";case "watch-state"->"state";case "watch-stop"->"stop";default->command;};
                    mutationSent=!command.equals("watch-state");
                    return emit(client.exchange(request("editor/watch",Map.of("schemaVersion",1,"instanceId",identity.instanceId(),"windowId",identity.windowId(),"projectId",identity.projectId(),"action",action,"agent",name==null?"Agent":name))),out);
                }
                if(command.equals("intent")){
                    var intent=intent(options);
                    String document=options.remove("--document"),revision=options.remove("--revision");
                    if((document==null)!=(revision==null)||!options.isEmpty())throw new IllegalArgumentException();
                    Long expectedRevision=revision==null?null:Long.parseLong(revision);
                    if(expectedRevision!=null&&expectedRevision<0)throw new IllegalArgumentException();
                    Map<String,Object> opened=null;
                    if(path!=null){
                        mutationSent=true;
                        opened=result(client.exchange(request("editor/file/open",Map.of("schemaVersion",1,"instanceId",identity.instanceId(),"windowId",identity.windowId(),"projectId",identity.projectId(),"path",path))));
                    }
                    var settled=result(client.exchange(client.navigationContextRequest()));
                    if(opened!=null)for(String field:List.of("paneId","documentId","documentRevision","caretOffset","selectionStart","selectionEnd","dirty"))
                        if(!Objects.equals(opened.get(field),settled.get(field)))throw new WorkspaceApiException(WorkspaceApiException.Code.CONTEXT_CHANGED,"Opened source changed before intent");
                    if(document!=null&&(!document.equals(settled.get("documentId"))||!expectedRevision.equals(settled.get("documentRevision"))))throw new WorkspaceApiException(WorkspaceApiException.Code.STALE_REVISION,"Intent requires the observed source revision");
                    var params=context(settled);params.put("intent",intent.json());mutationSent=true;
                    return emit(client.exchange(request("editor/agent/intent",params)),out);
                }
                if(command.equals("open")||command.equals("read")&&path!=null){
                    if(path==null)throw new IllegalArgumentException();
                    long offset=command.equals("read")?number(options,"--offset",0):0,limit=command.equals("read")?number(options,"--limit",EditorControl.MAX_READ):1;
                    String agent=command.equals("read")?options.remove("--agent"):null;if(agent==null)agent="Agent";
                    if(!options.isEmpty()||offset>Integer.MAX_VALUE||limit<1||limit>EditorControl.MAX_READ||agent.isBlank()||agent.length()>40||agent.codePoints().anyMatch(Character::isISOControl))throw new IllegalArgumentException();
                    var opening=new LinkedHashMap<String,Object>(Map.of("schemaVersion",1,"instanceId",identity.instanceId(),"windowId",identity.windowId(),"projectId",identity.projectId(),"path",path));
                    mutationSent=true;String response=client.exchange(request("editor/file/open",opening));
                    if(command.equals("open"))return emit(response,out);
                    var opened=result(response);var settled=result(client.exchange(client.navigationContextRequest()));
                    for(String field:List.of("paneId","documentId","documentRevision","caretOffset","selectionStart","selectionEnd","dirty"))
                        if(!Objects.equals(opened.get(field),settled.get(field)))throw new WorkspaceApiException(WorkspaceApiException.Code.CONTEXT_CHANGED,"Opened source changed before reading");
                    var visible=context(settled);visible.put("offset",offset);visible.put("limit",limit);visible.put("agent",agent);
                    return emit(client.exchange(request("editor/source/read-visible",visible)),out);
                }
                Map<String,Object> context=result(client.exchange(client.navigationContextRequest()));
                var params=context(context); String method;
                switch(command) {
                    case "ui-actions", "ui-state" -> {
                        if(path!=null)throw new IllegalArgumentException();method="editor/ui/"+command.substring(3);
                    }
                    case "ui-action", "ui-text" -> {
                        if(command.equals("ui-action") && path==null || command.equals("ui-text") && path!=null)throw new IllegalArgumentException();
                        String action=command.equals("ui-text")?"find.query":path;
                        long revision=number(options,"--ui-revision",-1);String agent=options.remove("--agent");if(agent==null)agent="Agent";
                        String text="";
                        if(command.equals("ui-text")) {
                            String file=required(options,"--text-file");byte[] bytes=file.equals("-")?input.readNBytes(AgentUiActions.MAX_TEXT*4+1):boundedFile(Path.of(file),AgentUiActions.MAX_TEXT*4);
                            text=WorkspaceFrames.decode(bytes);
                        }
                        new AgentUiActions.Command(action,revision,agent,text);
                        params.put("action",action);params.put("expectedUiRevision",revision);params.put("agent",agent);params.put("text",text);method="editor/ui/invoke";
                    }
                    case "open" -> { if(path==null)throw new IllegalArgumentException();params.put("path",path);method="editor/file/open"; }
                    case "read" -> {
                        if(path!=null)throw new IllegalArgumentException();
                        params.put("offset",number(options,"--offset",0));params.put("limit",number(options,"--limit",EditorControl.MAX_READ));method="editor/source/read";
                    }
                    case "activity" -> {
                        if(path!=null)throw new IllegalArgumentException();
                        params.put("start",number(options,"--start",0));params.put("end",number(options,"--end",0));method="editor/agent/activity";
                    }
                    case "read-file" -> {
                        if(path==null)throw new IllegalArgumentException();params.put("path",path);
                        params.put("offset",number(options,"--offset",0));params.put("limit",number(options,"--limit",EditorControl.MAX_READ));method="editor/project/read";
                    }
                    case "propose-files" -> {
                        if(path!=null)throw new IllegalArgumentException();String file=required(options,"--changes-file");
                        byte[] bytes=file.equals("-")?input.readNBytes(30001):boundedFile(Path.of(file),30000);
                        if(bytes.length>30000)throw new IllegalArgumentException();
                        params.put("changes",MiniJson.parseStrict(WorkspaceFrames.decode(bytes)));String id=options.remove("--request-id");
                        params.put("requestId",id==null?UUID.randomUUID().toString():id);String session=options.remove("--session-id");if(session!=null)params.put("sessionId",session);
                        method="editor/project/propose";
                    }
                    case "watch", "watch-state", "watch-stop", "follow", "pause" -> {
                        if(path!=null)throw new IllegalArgumentException();
                        String action=switch(command){case "watch"->"start";case "watch-state"->"state";case "watch-stop"->"stop";default->command;};
                        String name=options.remove("--agent");params.put("action",action);params.put("agent",name==null?"Agent":name);method="editor/watch";
                    }
                    case "diagnostics", "feedback", "preview", "preview-state", "preview-capture" -> {
                        if(path!=null)throw new IllegalArgumentException();
                        method=switch(command){case "diagnostics"->"editor/diagnostics";case "feedback"->"editor/feedback";case "preview"->"editor/preview/open";case "preview-state"->"editor/preview/state";default->"editor/preview/capture";};
                    }
                    case "progress" -> {
                        if(path!=null)throw new IllegalArgumentException();
                        params.put("sessionId",required(options,"--session-id"));params.put("agent",required(options,"--agent"));params.put("phase",required(options,"--phase"));
                        params.put("completed",number(options,"--completed",0));params.put("total",number(options,"--total",0));
                        params.put("start",number(options,"--start",0));params.put("end",number(options,"--end",0));method="editor/agent/progress";
                    }
                    case "activity-state" -> { if(path!=null)throw new IllegalArgumentException();method="editor/agent/state"; }
                    case "cancel" -> { if(path!=null)throw new IllegalArgumentException();params.put("sessionId",required(options,"--session-id"));method="editor/agent/cancel"; }
                    case "propose", "replace" -> {
                        if(path!=null)throw new IllegalArgumentException();
                        String document=options.remove("--document");long revision=number(options,"--revision",-1);
                        if(document==null || !document.equals(context.get("documentId")) || !Long.valueOf(revision).equals(context.get("documentRevision")))
                            throw new WorkspaceApiException(WorkspaceApiException.Code.STALE_REVISION,"Read the intended document before proposing edits");
                        String textFile=options.remove("--text-file");if(textFile==null)throw new IllegalArgumentException();
                        byte[] bytes=textFile.equals("-")?input.readNBytes(EditorControl.MAX_REPLACEMENT*4+1):boundedFile(Path.of(textFile),EditorControl.MAX_REPLACEMENT*4);
                        String replacement=WorkspaceFrames.decode(bytes);
                        if(replacement.length()>EditorControl.MAX_REPLACEMENT)throw new IllegalArgumentException();
                        params.put("start",command.equals("replace")?0L:number(options,"--start",-1));
                        params.put("end",command.equals("replace")?context.get("documentLength"):number(options,"--end",-1));
                        params.put("text",replacement);String id=options.remove("--request-id");params.put("requestId",id==null?UUID.randomUUID().toString():id);
                        String session=options.remove("--session-id");if(session!=null)params.put("sessionId",session);
                        method="editor/edits/propose";
                    }
                    default -> throw new IllegalArgumentException();
                }
                String captureOutput=command.equals("preview-capture")?required(options,"--output"):null;
                if(!options.isEmpty())throw new IllegalArgumentException();
                mutationSent=EditorControl.mutation(method);
                String response=client.exchange(request(method,params));
                if(captureOutput!=null){
                    var observation=MiniJson.asObject(result(response).get("observation"));byte[] png=Base64.getDecoder().decode((String)observation.get("data"));
                    if(png.length>20000)throw new IllegalArgumentException();Files.write(Path.of(captureOutput),png,StandardOpenOption.CREATE_NEW);
                    var clean=new LinkedHashMap<>(observation);clean.remove("data");out.println(MiniJson.stringify(clean));return 0;
                }
                return emit(response,out);
            }
        } catch(WorkspaceApiException failure) {
            boolean unknown=mutationSent && Set.of(WorkspaceApiException.Code.TIMEOUT,WorkspaceApiException.Code.SESSION_INVALID,WorkspaceApiException.Code.SESSION_EXPIRED,WorkspaceApiException.Code.OUTCOME_UNKNOWN).contains(failure.code());
            out.println(WorkspaceRpc.error("ctl",-32000,"Control request failed",Map.of("code",unknown?"OUTCOME_UNKNOWN":failure.code().name(),"outcome",unknown?"unknown":"rejected")));return unknown?79:65;
        } catch(IllegalArgumentException invalid) {
            out.println(WorkspaceRpc.error("ctl",-32602,"Usage: shed ctl --connect <descriptor> state|open <path>|read|sync|session-request|session-status|session-finish|intent|activity|propose|replace|terminal-run|terminal-status|terminal-cancel [options]",Map.of("code","INVALID_REQUEST")));return 64;
        } catch(IOException failure) {
            out.println(WorkspaceRpc.error("ctl",-32000,"Control connection or input unavailable",Map.of("code",mutationSent?"OUTCOME_UNKNOWN":"TRANSPORT_ERROR","outcome",mutationSent?"unknown":"rejected")));return mutationSent?79:74;
        }
    }
    static byte[] boundedFile(Path path,int maximum) throws IOException {
        try(var stream=Files.newInputStream(path)){byte[] bytes=stream.readNBytes(maximum+1);if(bytes.length>maximum)throw new IllegalArgumentException();return bytes;}
    }
    private static AgentBufferIntent intent(Map<String,String> options){
        String kind=required(options,"--kind"),status=options.remove("--status"),agent=options.remove("--agent"),raw=options.remove("--ranges");
        var ranges=new ArrayList<AgentEditPresentation.Range>();
        if(raw==null){long start=number(options,"--start",-1),end=number(options,"--end",-1);if(start>Integer.MAX_VALUE||end>Integer.MAX_VALUE)throw new IllegalArgumentException();ranges.add(new AgentEditPresentation.Range((int)start,(int)end));}
        else{
            if(raw.length()>1024||options.containsKey("--start")||options.containsKey("--end"))throw new IllegalArgumentException();
            var pairs=raw.split(",",-1);if(pairs.length>32)throw new IllegalArgumentException();
            for(String pair:pairs){var ends=pair.split(":",-1);if(ends.length!=2)throw new IllegalArgumentException();ranges.add(new AgentEditPresentation.Range(Integer.parseInt(ends[0]),Integer.parseInt(ends[1])));}
        }
        long focus=number(options,"--focus",0);if(focus>Integer.MAX_VALUE)throw new IllegalArgumentException();
        return new AgentBufferIntent(kind,status==null?(kind.equals("verify")?"checking":"active"):status,agent==null?"Agent":agent,ranges,(int)focus);
    }
    static String request(String method,Map<String,Object> params){return MiniJson.stringify(Map.of("jsonrpc","2.0","id","ctl","method",method,"params",params));}
    static Map<String,Object> context(Map<String,Object> value){
        var p=new LinkedHashMap<String,Object>();
        for(String key:List.of("schemaVersion","instanceId","windowId","projectId","paneId","documentId","documentRevision","contextRevision","caretOffset","selectionStart","selectionEnd"))p.put(key,value.get(key));
        return p;
    }
    static Map<String,Object> result(String response){
        var envelope=MiniJson.asObject(MiniJson.parseStrict(response));
        if(envelope.containsKey("error")){
            var error=MiniJson.asObject(envelope.get("error"));var data=MiniJson.asObject(error.get("data"));
            throw new WorkspaceApiException(WorkspaceApiException.Code.valueOf((String)data.get("code")),"Control request rejected");
        }
        return MiniJson.asObject(envelope.get("result"));
    }
    private static String required(Map<String,String> options,String key){String value=options.remove(key);if(value==null)throw new IllegalArgumentException();return value;}
    private static long number(Map<String,String> options,String key,long fallback){String raw=options.remove(key);long n=raw==null?fallback:Long.parseLong(raw);if(n<0)throw new IllegalArgumentException();return n;}
    private static int emit(String response,PrintWriter output){output.println(response);return output.checkError()?74:MiniJson.asObject(MiniJson.parseStrict(response)).containsKey("error")?65:0;}
}
