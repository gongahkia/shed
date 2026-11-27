package shed;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;

/** Bounded directory operations; recursive destructive operations are deliberately unavailable. */
final class AgentProjectFolders {
    record Request(String kind,String path,String destination){}
    static Request parse(String json){
        final Map<String,Object> value;try{value=MiniJson.asObject(MiniJson.parseStrict(json));}catch(IllegalArgumentException malformed){throw invalid();}if(value==null)throw invalid();
        String kind=EditorControl.text(value,"kind",16),path=EditorProjectChanges.path(value,"path");
        if(!Set.of("create","rename","delete").contains(kind)||!value.keySet().equals(kind.equals("rename")?Set.of("kind","path","destination"):Set.of("kind","path")))throw invalid();
        return new Request(kind,path,kind.equals("rename")?EditorProjectChanges.path(value,"destination"):null);
    }
    static String apply(Path root,Request request,AgentPolicy policy,java.util.function.BooleanSupplier current)throws IOException{
        if(!Set.of("create","rename","delete").contains(request.kind()))throw invalid();
        Path path=resolve(root,request.path(),policy),destination=request.destination()==null?null:resolve(root,request.destination(),policy);
        if(!current.getAsBoolean())throw new WorkspaceApiException(WorkspaceApiException.Code.AUTHORIZATION_REVOKED,"Project session ended");
        if(request.kind().equals("create")){if(Files.exists(path,LinkOption.NOFOLLOW_LINKS))throw new FileAlreadyExistsException(request.path());if(!Files.isDirectory(path.getParent(),LinkOption.NOFOLLOW_LINKS))throw new IOException("Parent folder must already exist");Files.createDirectory(path);}
        else {
            if(!Files.isDirectory(path,LinkOption.NOFOLLOW_LINKS))throw new IOException("Expected a real project folder");
            try(var contents=Files.newDirectoryStream(path)){if(contents.iterator().hasNext())throw new IOException("Only empty folders can be renamed or deleted through this interface");}
            if(destination==null)Files.delete(path);else {if(Files.exists(destination,LinkOption.NOFOLLOW_LINKS))throw new FileAlreadyExistsException(request.destination());Files.move(path,destination);}
        }
        return "Project folder "+request.kind()+" completed";
    }
    private static Path resolve(Path root,String relative,AgentPolicy policy){Path path=WorkspacePaths.resolve(root,relative);if(path.equals(root)||!path.equals(root.resolve(relative).normalize()))throw invalid();policy.checkPath(root.relativize(path),AgentPolicy.Capability.APPLY);return path;}
    private static WorkspaceApiException invalid(){return WorkspaceApiException.invalidRequest("Expected a bounded project folder operation");}
}
