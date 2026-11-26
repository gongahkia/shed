package shed;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** Explicit launch/attach helper; approval remains inside the real Shed window. */
final class ShedDelegateCli {
    private ShedDelegateCli(){}
    static void run(String[] args)throws Exception {
        Path project=Path.of(".");String agent="Agent",connect=null,file=null;boolean projectGiven=false;
        for(int i=1;i<args.length;i++){
            switch(args[i]){
                case "--agent" -> {if(++i==args.length)throw new IllegalArgumentException();agent=args[i];}
                case "--connect" -> {if(++i==args.length)throw new IllegalArgumentException();connect=args[i];}
                case "--task-file" -> {if(++i==args.length)throw new IllegalArgumentException();file=args[i];}
                default -> {if(projectGiven||args[i].startsWith("--"))throw new IllegalArgumentException();project=Path.of(args[i]);projectGiven=true;}
            }
        }
        if(file==null||connect!=null&&projectGiven||agent.isBlank()||agent.length()>40||agent.codePoints().anyMatch(Character::isISOControl))throw new IllegalArgumentException("Usage: shed delegate [project | --connect descriptor] --agent name --task-file path-or-dash");
        String task=WorkspaceFrames.decode(file.equals("-")?System.in.readNBytes(4097):ShedControlCli.boundedFile(Path.of(file),4096));
        if(task.isBlank()||task.length()>4096)throw new IllegalArgumentException("Use a bounded task description");
        new AgentDelegation().request("validate",agent,task);
        Path descriptor=connect==null?Path.of((String)ShedVisualize.launch(project,agent,true).get("descriptor")):Path.of(connect);
        var identity=WorkspaceStatusAttachment.Descriptor.read(descriptor);Path key=descriptor.resolveSibling("status-"+identity.attachmentId()+".key");
        String id=UUID.randomUUID().toString();Map<String,Object> status;
        try(var c=WorkspaceBridgeClient.control(descriptor,key)) { status=call(c,identity,"request",id,agent,task); }
        long until=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(110);
        while(Set.of("awaiting-approval","preparing").contains(status.get("state"))&&System.nanoTime()<until) {
            Thread.sleep(300);try(var c=WorkspaceBridgeClient.control(descriptor,key)){status=call(c,identity,"status",id,"","");}
        }
        if(!"active".equals(status.get("state")))throw new WorkspaceApiException(WorkspaceApiException.Code.AUTHORIZATION_REVOKED,"Delegation was not approved or could not become ready");
        System.out.println(MiniJson.stringify(Map.of("schemaVersion",1,"state","active","descriptor",descriptor.toAbsolutePath().toString(),"delegationId",id,
            "environment",Map.of("SHED_CONTROL",descriptor.toAbsolutePath().toString(),"SHED_DELEGATION",id),"instructions",AgentTaskInstructions.prompt(task,agent))));
    }
    private static Map<String,Object> call(WorkspaceBridgeClient c,WorkspaceStatusAttachment.Descriptor d,String action,String id,String agent,String task)throws IOException {
        var reply=ShedControlCli.result(c.exchange(ShedControlCli.request("editor/delegation",Map.of("schemaVersion",1,"instanceId",d.instanceId(),"windowId",d.windowId(),"projectId",d.projectId(),"action",action,"requestId",id,"agent",agent,"task",task))));
        return MiniJson.asObject(reply.get("observation"));
    }
}
