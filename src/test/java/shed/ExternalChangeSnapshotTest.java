package shed;
import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
class ExternalChangeSnapshotTest {
    @TempDir Path temporary;
    @Test void externalWritesCreateModifyDeleteAndExactRenamesWithoutMutatingOtherFiles() throws Exception {
        Path root=Files.createDirectory(temporary.resolve("project"));Files.writeString(root.resolve("keep.txt"),"human é 😀\n");Files.writeString(root.resolve("edit.txt"),"old\n");Files.writeString(root.resolve("rename.txt"),"rename unchanged\n");Files.writeString(root.resolve("delete.txt"),"gone\n");
        var baseline=ExternalChangeSnapshot.capture(root);
        Files.writeString(root.resolve("edit.txt"),"new\n");Files.writeString(root.resolve("added.txt"),"new file\n");Files.move(root.resolve("rename.txt"),root.resolve("renamed.txt"));Files.delete(root.resolve("delete.txt"));
        var changes=ExternalChangeSnapshot.compare(baseline,ExternalChangeSnapshot.capture(root));
        assertEquals(Set.of("A","M","D","R"),new HashSet<>(changes.stream().map(ExternalChangeSnapshot.Change::kind).toList()));
        assertEquals(4,changes.size());assertEquals("rename.txt",changes.stream().filter(c->c.kind().equals("R")).findFirst().orElseThrow().previous());
        assertEquals("human é 😀\n",Files.readString(root.resolve("keep.txt")));assertEquals("old\n",baseline.get("edit.txt").text());
    }
    @Test void privateGeneratedSymlinkAndOversizeContentAreBounded() throws Exception {
        Path root=Files.createDirectory(temporary.resolve("project"));Files.writeString(root.resolve(".env"),"synthetic-secret");Files.createDirectory(root.resolve("node_modules"));Files.writeString(root.resolve("node_modules/generated.js"),"generated");
        Path outside=temporary.resolve("outside.txt");Files.writeString(outside,"sentinel");Files.createSymbolicLink(root.resolve("escape.txt"),outside);
        Files.writeString(root.resolve("large.txt"),"x".repeat(ExternalChangeSnapshot.MAX_FILE+1));
        Path nested=Files.createDirectory(root.resolve("nested"));Files.writeString(nested.resolve("independent.txt"),"another project");
        var snapshot=ExternalChangeSnapshot.capture(root,List.of(nested));assertEquals(Set.of("large.txt"),snapshot.keySet());assertFalse(snapshot.get("large.txt").textual());assertEquals("sentinel",Files.readString(outside));
    }
    @Test void boundedWatcherReportsNormalExternalWritesAndCloses() throws Exception {
        Path root=Files.createDirectory(temporary.resolve("project"));var changed=new CompletableFuture<Path>();
        try(var watch=TestWatchService.start(root,changed::complete)){
            Files.writeString(root.resolve("external.txt"),"external");assertNotNull(changed.get(5,TimeUnit.SECONDS));assertTrue(watch.watching());watch.close();assertFalse(watch.watching());
        }
    }
    @Test void fileTreeRefreshRetainsSurvivorsAndRemovesDeletedNodes() throws Exception {
        Path root=Files.createDirectory(temporary.resolve("tree"));Path keep=Files.writeString(root.resolve("keep.txt"),"keep"),gone=Files.writeString(root.resolve("gone.txt"),"gone");
        var model=new FileTreeModel(root.toFile(),directory->new java.io.File[]{keep.toFile(),gone.toFile()});var survivor=model.rootNode().getChildAt(0);
        Path added=Files.writeString(root.resolve("added.txt"),"added");
        model.refreshListings(Map.of(root.toFile(),new java.io.File[]{keep.toFile(),added.toFile()}));assertSame(survivor,model.rootNode().getChildAt(0));
        assertEquals("added.txt",model.rootNode().getChildAt(1).toString());assertEquals(2,model.rootNode().getChildCount());assertEquals("keep",Files.readString(keep));
    }
    @Test void cancelledDiffDoesNotContinueProcessingSnapshots(){
        var before=Map.of("one.txt",new ExternalChangeSnapshot.File("before","a",true));var after=Map.of("one.txt",new ExternalChangeSnapshot.File("after","b",true));
        try{Thread.currentThread().interrupt();assertThrows(CancellationException.class,()->ExternalChangeSnapshot.compare(before,after));}
        finally{Thread.interrupted();}
    }
    @Test void unifiedDiffPaintIsPresentationOnly() throws Exception {
        EditorControlTest.edt(()->{
            var view=new ChangeDiffView();view.setSize(700,400);var source=new ExternalChangeSnapshot.Change("M","Example.java",null,"old value\n","new value\n",WorkspaceTextEdits.diff("old value\n","new value\n"));
            view.showChange(source,BundledEditorFont.atSize(16));assertTrue(view.getText().contains("-old value"));assertTrue(view.getText().contains("+new value"));
            var insertion=new ExternalChangeSnapshot.Change("M","insert.txt",null,"keep\n","added\nkeep\n",WorkspaceTextEdits.diff("keep\n","added\nkeep\n"));
            view.showChange(insertion,BundledEditorFont.atSize(16));assertTrue(view.getText().contains("+added\n"));assertFalse(view.getText().contains("-keep"));assertFalse(view.getText().contains("+keep"));
            String headers="file\n@@ first @@\n-text\n@@ second @@\n+text";int first=ChangeDiffView.nextHunk(headers,0,1),second=ChangeDiffView.nextHunk(headers,first,1);
            assertEquals(headers.indexOf("@@ first"),first);assertEquals(headers.indexOf("@@ second"),second);assertEquals(first,ChangeDiffView.nextHunk(headers,second,-1));assertEquals(-1,ChangeDiffView.nextHunk(headers,second,1));
            var word=new ExternalChangeSnapshot.Change("M","word.txt",null,"value\n","public value\n",WorkspaceTextEdits.diff("value\n","public value\n"));assertEquals(1,word.added());assertEquals(1,word.removed());assertEquals(1,insertion.added());assertEquals(0,insertion.removed());
            String displayed=view.getText();view.select(1,4);view.refreshAppearance(BundledEditorFont.atSize(16));assertEquals(displayed,view.getText());assertEquals(1,view.getSelectionStart());assertEquals(4,view.getSelectionEnd());
            var image=new java.awt.image.BufferedImage(700,400,java.awt.image.BufferedImage.TYPE_INT_RGB);var graphics=image.createGraphics();view.paint(graphics);graphics.dispose();assertEquals("old value\n",source.before());return null;
        });
    }
}
