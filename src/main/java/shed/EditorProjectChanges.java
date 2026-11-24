package shed;

import java.util.*;

/** Bounded project proposal parsing; application, recovery and Undo remain owned by the shared service. */
final class EditorProjectChanges {
    static final int MAX_FILES=8;
    private EditorProjectChanges() { }
    static String path(Map<String,Object> value,String key) {
        String name=EditorControl.text(value,key,4096);
        try { ProjectBoundFileAcquisition.checkedRelativePath(name); }
        catch(java.io.IOException invalid){throw WorkspaceApiException.invalidRequest("Expected a project-relative source path");}
        return name;
    }
    static List<WorkspaceProposalService.Input> parse(Object raw){
        var changes=MiniJson.asArray(raw);
        if(changes==null || changes.isEmpty() || changes.size()>MAX_FILES)throw WorkspaceApiException.invalidRequest("Expected one to eight reviewed file changes");
        var inputs=new ArrayList<WorkspaceProposalService.Input>();int units=0;
        for(Object item:changes){
            var value=MiniJson.asObject(item);if(value==null)throw WorkspaceApiException.invalidRequest("Invalid change");
            String kind=EditorControl.text(value,"kind",16),path=path(value,"path");
            Set<String> fields=switch(kind){case "text"->Set.of("kind","path","version","text");case "create"->Set.of("kind","path","text");
                case "delete"->Set.of("kind","path","version");case "rename"->Set.of("kind","path","version","destination");default->throw WorkspaceApiException.invalidRequest("Unknown file change kind");};
            if(!value.keySet().equals(fields))throw WorkspaceApiException.invalidRequest("Unexpected file change field");
            String text=value.containsKey("text")?EditorControl.text(value,"text",EditorControl.MAX_REPLACEMENT):"";
            units+=text.length();if(units>EditorControl.MAX_REPLACEMENT)throw new WorkspaceApiException(WorkspaceApiException.Code.LIMIT_EXCEEDED,"Proposal text exceeds its bounded limit");
            String version=value.containsKey("version")?EditorControl.text(value,"version",512):"";
            inputs.add(switch(kind){case "text"->new WorkspaceProposalService.Input(path,version,text);case "create"->WorkspaceProposalService.Input.create(path,text);
                case "delete"->WorkspaceProposalService.Input.delete(path,version);default->WorkspaceProposalService.Input.rename(path,version,path(value,"destination"));});
        }
        return List.copyOf(inputs);
    }
}
