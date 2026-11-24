package shed;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class EditorProjectChangesTest {
    @TempDir Path temporary;
    private Map<String,Object> read(WorkspaceBridgeClient c,String path) throws Exception {
        var p=EditorControlTest.params(EditorControlTest.context(c));p.put("path",path);p.put("offset",0L);p.put("limit",2048L);
        return MiniJson.asObject(EditorControlTest.result(EditorControlTest.exchange(c,"editor/project/read",p)).get("file"));
    }
    private Map<String,Object> propose(WorkspaceBridgeClient c,List<?> changes,String id) throws Exception {
        var p=EditorControlTest.params(EditorControlTest.context(c));p.put("changes",changes);p.put("requestId",id);return EditorControlTest.exchange(c,"editor/project/propose",p);
    }
    @Test void oneReviewOwnsMultipleTextAndResourceChanges() throws Exception {
        try(var f=new EditorControlTest.Fixture(temporary);var c=WorkspaceBridgeClient.control(f.server.descriptor(),f.server.credential())){
            Files.writeString(f.root.resolve("Old.txt"),"rename me\n");Files.writeString(f.root.resolve("Delete.txt"),"remove me\n");
            var one=read(c,"One.java");var old=read(c,"Old.txt");var deleted=read(c,"Delete.txt");
            var changes=List.of(Map.of("kind","text","path","One.java","version",one.get("version"),"text","class One { int changed; }\n"),
                Map.of("kind","create","path","New.java","text","class New {}\n"),Map.of("kind","rename","path","Old.txt","version",old.get("version"),"destination","Renamed.txt"),
                Map.of("kind","delete","path","Delete.txt","version",deleted.get("version")));
            var proposal=EditorControlTest.result(propose(c,changes,"bundle"));String id=(String)proposal.get("proposalId");
            assertEquals(true,proposal.get("reviewRequired"));assertFalse(Files.exists(f.root.resolve("New.java")));assertTrue(Files.exists(f.root.resolve("Delete.txt")));
            var human=f.human();var preview=human.preview(id,0);var applied=human.apply(id,((Number)preview.proposal().get("revision")).intValue());
            assertEquals("APPLIED",applied.get("status"));assertEquals("class New {}\n",Files.readString(f.root.resolve("New.java")));
            assertEquals("rename me\n",Files.readString(f.root.resolve("Renamed.txt")));assertFalse(Files.exists(f.root.resolve("Old.txt")));assertFalse(Files.exists(f.root.resolve("Delete.txt")));
            EditorControlTest.edt(()->{var b=f.pane.getBuffer();assertTrue(b.isModified());assertEquals("class One { int changed; }\n",b.getContent());return null;});
            assertEquals("UNDONE",human.undo(id,((Number)applied.get("revision")).intValue()).get("status"));
            assertFalse(Files.exists(f.root.resolve("New.java")));assertTrue(Files.exists(f.root.resolve("Old.txt")));assertTrue(Files.exists(f.root.resolve("Delete.txt")));
            assertFalse(Files.exists(f.root.resolve("Renamed.txt")));assertEquals(one.get("content"),EditorControlTest.edt(()->f.pane.getBuffer().getContent()));
            assertEquals(one.get("content"),Files.readString(f.root.resolve("One.java")),"Text proposals do not implicitly save");
        }
    }
    @Test void staleAndEscapingAndProtectedChangesAreRefusedBeforeReview() throws Exception {
        try(var f=new EditorControlTest.Fixture(temporary);var c=WorkspaceBridgeClient.control(f.server.descriptor(),f.server.credential())){
            for(String path:List.of("../outside.txt","/absolute","private.env","README.md")){
                var response=propose(c,List.of(Map.of("kind","create","path",path,"text","unwanted")),"bad-"+Math.abs(path.hashCode()));assertTrue(response.containsKey("error"),response::toString);
            }
            var source=read(c,"One.java");EditorControlTest.edt(()->{f.pane.getBuffer().getDocument().insertString(0,"// human\n",null);return null;});
            assertEquals("STALE_VERSION",EditorControlTest.code(propose(c,List.of(Map.of("kind","text","path","One.java","version",source.get("version"),"text","overwrite")),"stale")));
            assertNull(f.reviewed);assertFalse(Files.exists(f.root.resolve("README.md")));
        }
    }
}
