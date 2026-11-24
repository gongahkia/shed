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
        try {
            var options = new LinkedHashMap<String,String>(); String command = null, path = null;
            for (int i=1;i<args.length;i++) {
                String arg=args[i];
                if (arg.equals("--json")) continue;
                if (arg.startsWith("--")) {
                    if (!Set.of("--connect","--credential","--offset","--limit","--start","--end","--revision","--document","--text-file","--request-id").contains(arg)
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
                if(command.equals("state")) {
                    if(path!=null || !options.isEmpty()) throw new IllegalArgumentException();
                    return emit(client.exchange(client.navigationContextRequest()),out);
                }
                Map<String,Object> context=result(client.exchange(client.navigationContextRequest()));
                var params=context(context); String method;
                switch(command) {
                    case "open" -> { if(path==null)throw new IllegalArgumentException();params.put("path",path);method="editor/file/open"; }
                    case "read" -> {
                        if(path!=null)throw new IllegalArgumentException();
                        params.put("offset",number(options,"--offset",0));params.put("limit",number(options,"--limit",EditorControl.MAX_READ));method="editor/source/read";
                    }
                    case "activity" -> {
                        if(path!=null)throw new IllegalArgumentException();
                        params.put("start",number(options,"--start",0));params.put("end",number(options,"--end",0));method="editor/agent/activity";
                    }
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
                        method="editor/edits/propose";
                    }
                    default -> throw new IllegalArgumentException();
                }
                if(!options.isEmpty())throw new IllegalArgumentException();
                return emit(client.exchange(request(method,params)),out);
            }
        } catch(WorkspaceApiException failure) {
            out.println(WorkspaceRpc.error("ctl",-32000,"Control request failed",Map.of("code",failure.code().name())));return 65;
        } catch(IllegalArgumentException invalid) {
            out.println(WorkspaceRpc.error("ctl",-32602,"Usage: shed ctl --connect <descriptor> state|open <path>|read|activity|propose|replace [options]",Map.of("code","INVALID_REQUEST")));return 64;
        } catch(IOException failure) {
            out.println(WorkspaceRpc.error("ctl",-32000,"Control connection or input unavailable",Map.of("code","TRANSPORT_ERROR")));return 74;
        }
    }
    static byte[] boundedFile(Path path,int maximum) throws IOException {
        try(var stream=Files.newInputStream(path)){byte[] bytes=stream.readNBytes(maximum+1);if(bytes.length>maximum)throw new IllegalArgumentException();return bytes;}
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
    private static long number(Map<String,String> options,String key,long fallback){String raw=options.remove(key);long n=raw==null?fallback:Long.parseLong(raw);if(n<0)throw new IllegalArgumentException();return n;}
    private static int emit(String response,PrintWriter output){output.println(response);return output.checkError()?74:MiniJson.asObject(MiniJson.parseStrict(response)).containsKey("error")?65:0;}
}
