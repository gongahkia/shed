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
    @Test void hiddenTemporaryTailDoesNotMaskTheSourceWrite() throws Exception {
        Path root=Files.createDirectory(temporary.resolve("atomic"));var changed=new CompletableFuture<Path>();
        try(var watch=TestWatchService.start(root,path->ExternalChangeSnapshot.allowed(root,path),changed::complete)){
            Files.writeString(root.resolve("source.txt"),"final source\n");
            Path hidden=Files.writeString(root.resolve(".save.tmp"),"temporary");Files.delete(hidden);
            assertEquals(root.resolve("source.txt"),changed.get(3,TimeUnit.SECONDS));
        }
    }
    @Test void continuousWritesStillPublishBeforeTheWriterFinishes() throws Exception {
        Path root=Files.createDirectory(temporary.resolve("stream")),source=Files.writeString(root.resolve("source.txt"),"start");
        var changed=new CompletableFuture<Path>();var writer=Executors.newSingleThreadScheduledExecutor();
        try(var watch=TestWatchService.start(root,changed::complete)){
            var writes=writer.scheduleAtFixedRate(()->{try{Files.writeString(source,"stream "+System.nanoTime());}catch(java.io.IOException e){changed.completeExceptionally(e);}},0,20,TimeUnit.MILLISECONDS);
            // macOS WatchService adds its own polling interval before this service sees events.
            assertNotNull(changed.get(6,TimeUnit.SECONDS));assertFalse(writes.isDone());
        }finally{writer.shutdownNow();assertTrue(writer.awaitTermination(3,TimeUnit.SECONDS));}
    }
    @Test void atomicReplacementAndBulkChangesConvergeToExactFinalContents() throws Exception {
        Path root=Files.createDirectory(temporary.resolve("bulk"));Files.writeString(root.resolve("source.txt"),"old\n");var base=ExternalChangeSnapshot.capture(root);
        var observed=new CompletableFuture<Path>();
        try(var watcher=TestWatchService.start(root,p->ExternalChangeSnapshot.allowed(root,p),observed::complete)){
            for(int i=0;i<40;i++){Path pending=Files.writeString(root.resolve(".save.tmp"),"version "+i+"\n");Files.move(pending,root.resolve("source.txt"),StandardCopyOption.REPLACE_EXISTING,StandardCopyOption.ATOMIC_MOVE);}
            for(int i=0;i<200;i++)Files.writeString(root.resolve("new-"+i+".txt"),"created "+i+"\n");
            assertNotNull(observed.get(5,TimeUnit.SECONDS));var now=ExternalChangeSnapshot.capture(root);assertEquals("version 39\n",now.get("source.txt").text());
            assertEquals(201,ExternalChangeSnapshot.compare(base,now).size());assertFalse(now.containsKey(".save.tmp"));
        }
    }
    @Test void sourceLargerThanOldLimitAndThousandsOfFilesRemainBounded() throws Exception {
        Path root=Files.createDirectory(temporary.resolve("large-project"));
        for(int i=0;i<5000;i++)Files.writeString(root.resolve("file-"+i+".txt"),"line "+i+"\n");
        String text="source line\n".repeat(20000);Files.writeString(root.resolve("large.txt"),text);
        var base=ExternalChangeSnapshot.capture(root);assertEquals(5001,base.size());assertTrue(base.get("large.txt").textual());
        Files.writeString(root.resolve("large.txt"),text+"new last line\n");
        var changes=ExternalChangeSnapshot.compare(base,ExternalChangeSnapshot.capture(root));assertEquals(1,changes.size());assertEquals(1,changes.getFirst().added());
        String patch=ChangeDiffView.format(changes.getFirst());assertTrue(patch.contains("+new last line"));
    }
    @Test void oversizeDiffClearlyReportsTruncation(){
        var added=new ExternalChangeSnapshot.Change("A","long.txt",null,"","line\n".repeat(25000),List.of(new WorkspaceTextEdits.Hunk(0,0,"","line\n".repeat(25000))));
        String patch=ChangeDiffView.format(added);assertTrue(patch.contains("Diff display truncated"));assertTrue(patch.length()<100100);
    }
    @Test void safeConfigurationIsIncludedButPrivateHiddenPathsRemainExcluded() throws Exception {
        Path root=Files.createDirectory(temporary.resolve("config"));Files.writeString(root.resolve(".gitignore"),"build/\n");Files.writeString(root.resolve(".prettierrc"),"{}\n");
        Files.createDirectories(root.resolve(".github/workflows"));Files.writeString(root.resolve(".github/workflows/build.yml"),"name: Build\n");Files.writeString(root.resolve(".github/secrets.key"),"synthetic secret");
        Files.createDirectories(root.resolve(".ssh"));Files.writeString(root.resolve(".ssh/config"),"private");Files.writeString(root.resolve(".npmrc"),"synthetic token");Files.writeString(root.resolve("auth.json"),"synthetic credential");
        assertEquals(Set.of(".gitignore",".prettierrc",".github/workflows/build.yml"),ExternalChangeSnapshot.capture(root).keySet());
    }
    @Test void editedMovesUseIdentityAndSimilarityRemainsExplicitlyAdvisory() throws Exception {
        Path root=Files.createDirectory(temporary.resolve("moves"));Files.createDirectories(root.resolve("old"));Files.createDirectories(root.resolve("new"));
        String text="first stable line\nsecond stable line\nthird stable line\nfourth stable line\nfifth stable line\n";
        Files.writeString(root.resolve("old/a.txt"),text);Files.writeString(root.resolve("old/b.txt"),text+"b\n");var base=ExternalChangeSnapshot.capture(root);
        Files.move(root.resolve("old/a.txt"),root.resolve("new/a.txt"));Files.move(root.resolve("old/b.txt"),root.resolve("new/b.txt"));Files.writeString(root.resolve("new/a.txt"),text+"edited\n");
        var changes=ExternalChangeSnapshot.compare(base,ExternalChangeSnapshot.capture(root));assertEquals(2,changes.size());assertTrue(changes.stream().allMatch(row->row.previous()!=null));assertTrue(ExternalChangeSnapshot.moveSummary(changes).contains("2 files"));
        var a=new ExternalChangeSnapshot.File(text,"old",true);var b=new ExternalChangeSnapshot.File(text+"edited\n","new",true);
        var inferred=ExternalChangeSnapshot.compare(Map.of("old.txt",a),Map.of("new.txt",b));assertEquals("R?",inferred.getFirst().kind());assertTrue(ChangeDiffView.format(inferred.getFirst()).contains("Possible move"));
        var ambiguous=ExternalChangeSnapshot.compare(Map.of("a.txt",a,"b.txt",a),Map.of("c.txt",b));assertTrue(ambiguous.stream().noneMatch(row->row.kind().startsWith("R")));
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
    @Test void diffTextContrastUsesItsActualTintedLineBackground() throws Exception {
        EditorControlTest.edt(()->{
            var keys=List.of("TextArea.background","TextArea.foreground","shed.success","shed.error");var old=new HashMap<String,Object>();keys.forEach(key->old.put(key,javax.swing.UIManager.get(key)));
            try{
                for(int background:new int[]{0xf9f9fb,0x282a36}){
                    javax.swing.UIManager.put("TextArea.background",new java.awt.Color(background));javax.swing.UIManager.put("TextArea.foreground",background==0x282a36?java.awt.Color.WHITE:java.awt.Color.BLACK);
                    javax.swing.UIManager.put("shed.success",new java.awt.Color(0x27815c));javax.swing.UIManager.put("shed.error",new java.awt.Color(0xd63e54));
                    var view=new ChangeDiffView();view.showPatch("-removed\n+added",BundledEditorFont.atSize(16));
                    for(int offset:new int[]{0,9}){var attributes=view.getStyledDocument().getCharacterElement(offset).getAttributes();assertTrue(AccessibilitySupport.meetsTextContrast(javax.swing.text.StyleConstants.getForeground(attributes),javax.swing.text.StyleConstants.getBackground(attributes)),"Diff line foreground loses contrast against its tint");}
                }
            }finally{keys.forEach(key->javax.swing.UIManager.put(key,old.get(key)));}
            return null;
        });
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
