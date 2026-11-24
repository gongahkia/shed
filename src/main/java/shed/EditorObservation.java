package shed;

import java.util.*;
import java.awt.image.BufferedImage;

/** Existing workbench observations only; no process launch, document mutation or desktop capture. */
final class EditorObservation {
    private EditorObservation() { }
    static Map<String,Object> read(Texteditor editor,String operation) {
        if(!javax.swing.SwingUtilities.isEventDispatchThread())throw new IllegalStateException("Workbench observation belongs to the EDT");
        var buffer=editor.getCurrentBuffer();
        if(operation.equals("editor/diagnostics")) {
            var client=editor.lspController.existingLspClient(buffer);
            var items=new ArrayList<Map<String,Object>>();
            if(client!=null)for(var d:client.cachedDiagnostics(editor.lspController.bufferUri(buffer),32))
                items.add(Map.of("line",Math.max(0,d.getLine()),"column",Math.max(0,d.getCharacter()),"endLine",Math.max(0,d.getEndLine()),
                    "endColumn",Math.max(0,d.getEndCharacter()),"severity",d.getSeverity(),"message",bounded(d.getMessage(),256)));
            return Map.of("kind","diagnostics","cached",true,"freshness","unverified","serverConnected",client!=null,"items",items,"limit",32);
        }
        if(operation.equals("editor/feedback")) {
            var counts=new LinkedHashMap<String,Object>();for(var status:AsyncJobService.Status.values())counts.put(status.name(),0L);
            for(var job:editor.asyncJobService.list())counts.compute(job.getStatus().name(),(k,v)->(Long)v+1);
            return Map.of("kind","feedback","jobs",counts,"scope","application-counts","executionAllowed",false);
        }
        if(buffer==null||buffer.getFileType()!=FileType.MARKDOWN||buffer.getDocument().getLength()>4096)
            throw new WorkspaceApiException(WorkspaceApiException.Code.UNSUPPORTED,"Preview observation requires a small Markdown source");
        if(operation.equals("editor/preview/open")) {
            editor.markdownController.openPreviewForAgent();
        }
        var preview=editor.markdownController.previewForAgent(editor.getActivePane());
        return Map.of("kind","preview","open",preview!=null,"current",preview!=null&&preview.currentForAgent(),"sourceRevision",buffer.documentRevision(),"textAuthority","source");
    }
    static BufferedImage capture(Texteditor editor) {
        read(editor,"editor/preview/state");
        var preview=editor.markdownController.previewForAgent(editor.getActivePane());
        if(preview==null||!preview.currentForAgent()||!preview.isShowing())throw new WorkspaceApiException(WorkspaceApiException.Code.APP_NOT_READY,"Preview is not ready for capture");
        var viewport=preview.viewportForAgent();
        return SwingComponentCapture.capture(viewport,0,0,Math.min(256,viewport.getWidth()),Math.min(160,viewport.getHeight()),262144);
    }
    static String bounded(String text,int maximum){return text==null?"":text.substring(0,Math.min(text.length(),maximum));}
}
