package shed;

import java.io.*;
import java.nio.file.Path;
import java.util.*;

/** Harness-neutral frontend for semantic controls; neither a shell driver nor a desktop robot. */
final class ShedWorkbenchCli {
    static final Set<String> COMMANDS=Set.of("ui-targets","ui-inspect","ui-do","ui-scroll","ui-click","ui-double-click","ui-context","ui-hover","ui-type","ui-key","ui-select","ui-drag","ui-copy","ui-paste","ui-read","ui-run","ui-wait","ui-sequence");
    static boolean readonly(String command){return Set.of("ui-targets","ui-inspect","ui-wait").contains(command);}
    private final WorkspaceBridgeClient client;
    private final WorkspaceStatusAttachment.Descriptor identity;
    private final InputStream input;
    private ShedWorkbenchCli(WorkspaceBridgeClient client,WorkspaceStatusAttachment.Descriptor identity,InputStream input){this.client=client;this.identity=identity;this.input=input;}
    static int run(WorkspaceBridgeClient client,WorkspaceStatusAttachment.Descriptor identity,String command,String target,Map<String,String> options,InputStream input,PrintWriter out)throws IOException{
        var cli=new ShedWorkbenchCli(client,identity,input);Map<String,Object> result;
        if(command.equals("ui-targets")){if(target!=null)throw new IllegalArgumentException();int offset=number(options,"--offset",0);if(!options.isEmpty())throw new IllegalArgumentException();result=cli.exchange("state",Map.of("offset",offset));}
        else if(command.equals("ui-inspect")){if(target==null)throw new IllegalArgumentException();int offset=number(options,"--offset",0);if(!options.isEmpty())throw new IllegalArgumentException();result=cli.exchange("inspect",Map.of("target",target,"offset",offset));}
        else if(command.equals("ui-wait")){if(target!=null)throw new IllegalArgumentException();int timeout=number(options,"--timeout-ms",2500);String id=options.remove("--request-id");if(timeout<50||timeout>3000||!options.isEmpty())throw new IllegalArgumentException();long until=System.nanoTime()+timeout*1_000_000L;int polls=0;do{result=cli.exchange("state",Map.of("offset",0));var state=MiniJson.asObject(result.get("observation"));var last=MiniJson.asObject(state.get("last"));if((id==null||id.equals(String.valueOf(last.get("id"))))&&Set.of("painted","ui-updated","failed","idle").contains(last.get("stage"))&&!Boolean.TRUE.equals(state.get("busy")))break;if(System.nanoTime()>until||++polls>60)throw new WorkspaceApiException(WorkspaceApiException.Code.TIMEOUT,"UI is still pending; inspect the active dialog or tool");try{Thread.sleep(50);}catch(InterruptedException interrupted){Thread.currentThread().interrupt();throw new WorkspaceApiException(WorkspaceApiException.Code.CANCELLED,"UI wait cancelled");}}while(true);}
        else if(command.equals("ui-sequence")){
            if(target!=null)throw new IllegalArgumentException();String file=required(options,"--steps-file"),agent=options.remove("--agent");if(agent==null)agent="Agent";if(!options.isEmpty())throw new IllegalArgumentException();
            var steps=MiniJson.asArray(MiniJson.parseStrict(cli.file(file,24_000)));if(steps==null||steps.isEmpty()||steps.size()>32)throw new IllegalArgumentException();
            var results=new ArrayList<Object>();for(Object raw:steps){var step=MiniJson.asObject(raw);if(step==null||!step.keySet().equals(Set.of("kind","target","args")))throw new IllegalArgumentException();results.add(cli.perform((String)step.get("kind"),(String)step.get("target"),agent,null,MiniJson.asObject(step.get("args"))));}
            result=Map.of("schemaVersion",2,"results",results);
        }else{
            if(target==null)throw new IllegalArgumentException();String agent=options.remove("--agent");if(agent==null)agent="Agent";String expected=options.remove("--ui-revision");
            String kind;Map<String,Object> args;
            switch(command){
                case "ui-do"->{kind=required(options,"--kind");args=MiniJson.asObject(MiniJson.parseStrict(cli.file(required(options,"--args-file"),24_000)));}
                case "ui-scroll"->{kind="scroll";String axis=options.remove("--axis"),unit=options.remove("--unit");String amount=required(options,"--amount");args=Map.of("axis",axis==null?"vertical":axis,"unit",unit==null?"lines":unit,"amount",Long.parseLong(amount));}
                case "ui-type"->{kind="text";String mode=options.remove("--mode");args=Map.of("mode",mode==null?"set":mode,"text",cli.file(required(options,"--text-file"),AgentUiRequest.MAX_TEXT*4),"start",(long)number(options,"--start",0),"end",(long)number(options,"--end",0));}
                case "ui-key"->{kind="key";args=Map.of("key",required(options,"--key"));}
                case "ui-read","ui-drag"->{kind=command.substring(3);args=Map.of("start",(long)number(options,"--start",0),"end",(long)number(options,"--end",0));}
                case "ui-select","ui-double-click","ui-context"->{kind=command.substring(3);args=Map.of("index",(long)number(options,"--index",0));}
                case "ui-run"->{kind="action";String file=options.remove("--value-file");args=Map.of("value",file==null?"":cli.file(file,4096*4));if(!target.startsWith("action/"))target="action/"+target;}
                default->{kind=command.substring(3);args=Map.of();}
            }
            if(!options.isEmpty())throw new IllegalArgumentException();result=cli.perform(kind,target,agent,expected,args);
        }
        out.println(MiniJson.stringify(Map.of("jsonrpc","2.0","id","ctl","result",result)));return out.checkError()?74:0;
    }
    private Map<String,Object> perform(String kind,String target,String agent,String expected,Map<String,Object> args)throws IOException{
        long revision=expected==null?((Number)MiniJson.asObject(exchange("state",Map.of("offset",0)).get("observation")).get("revision")).longValue():Long.parseLong(expected);
        var request=new AgentUiRequest(kind,target,agent,revision,args);
        return exchange("perform",Map.of("request",Map.of("schemaVersion",2,"kind",request.kind(),"target",request.target(),"agent",request.agent(),"expectedUiRevision",request.revision(),"args",request.args())));
    }
    private Map<String,Object> exchange(String operation,Map<String,Object> extras)throws IOException{
        var params=new LinkedHashMap<String,Object>();params.put("schemaVersion",1);params.put("instanceId",identity.instanceId());params.put("windowId",identity.windowId());params.put("projectId",identity.projectId());params.putAll(extras);
        return ShedControlCli.result(client.exchange(ShedControlCli.request("editor/ui/workbench-"+operation,params)));
    }
    private String file(String value,int maximum)throws IOException{byte[] bytes=value.equals("-")?input.readNBytes(maximum+1):ShedControlCli.boundedFile(Path.of(value),maximum);if(bytes.length>maximum)throw new IllegalArgumentException();return WorkspaceFrames.decode(bytes);}
    private static String required(Map<String,String> options,String key){String value=options.remove(key);if(value==null)throw new IllegalArgumentException();return value;}
    private static int number(Map<String,String> options,String key,int fallback){String value=options.remove(key);long n=value==null?fallback:Long.parseLong(value);if(n<0||n>1_048_576)throw new IllegalArgumentException();return (int)n;}
}
