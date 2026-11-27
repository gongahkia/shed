package shed;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.*;
import java.io.IOException;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LocalHistoryStoreTest {
    @TempDir Path root;
    @Test void savesAreOrderedDeduplicatedBoundedAndPersistExactUtf16()throws Exception{
        var store=new LocalHistoryStore(root.resolve("history"));Path file=root.resolve("Source.java");
        String text="é\r\nक्‍ष 👩‍💻\ud800";store.record(file,text);store.record(file,text);assertEquals(1,store.versions(file).size());
        assertEquals(text,store.read(file,store.versions(file).getFirst().id()));
        for(int i=0;i<25;i++)store.record(file,"version "+i);
        assertEquals(LocalHistoryStore.PER_FILE,store.versions(file).size());assertEquals("version 24",store.read(file,store.versions(file).getFirst().id()));
        var reopened=new LocalHistoryStore(root.resolve("history"));assertEquals(store.versions(file),reopened.versions(file));
        reopened.clear(file);assertTrue(store.versions(file).isEmpty());assertFalse(Files.exists(file));
    }
    @Test void rejectsSensitivePathsEscapesSharedPermissionsAndCorruption()throws Exception{
        var store=new LocalHistoryStore(root.resolve("history"));Path file=root.resolve("Source.java");
        assertThrows(IOException.class,()->store.record(root.resolve(".env"),"secret"));
        store.record(file,"safe");String id=store.versions(file).getFirst().id();assertThrows(IOException.class,()->store.read(file,"../outside"));
        Path saved;try(var paths=Files.walk(root.resolve("history"))){saved=paths.filter(Files::isRegularFile).findFirst().orElseThrow();}
        Files.write(saved,new byte[]{0,65});assertThrows(IOException.class,()->store.read(file,id));
        Path external=root.resolve("outside");Files.createDirectories(external);Path link=root.resolve("alias");Files.createSymbolicLink(link,external);
        assertThrows(IOException.class,()->new LocalHistoryStore(link.resolve("history")).record(file,"never"));try(var paths=Files.list(external)){assertEquals(0,paths.count());}
    }
    @Test void successfulSyncAndAsyncSavesRecordVersionsFailedSavesDoNot()throws Exception{
        Path file=root.resolve("Source.java");Files.writeString(file,"old\r\n");var store=new LocalHistoryStore(root.resolve("history"));
        FileBuffer b=new FileBuffer(file.toFile());b.localHistory(store);b.setContent("new\n");b.save();assertEquals(2,store.versions(file).size());
        assertEquals("new\r\n",Files.readString(file));b.setContent("third\n");var snapshot=b.captureSaveSnapshot(null);b.writeSaveSnapshot(snapshot);b.completeSaveSnapshot(snapshot);assertEquals(3,store.versions(file).size());
        Files.writeString(file,"external");b.setContent("stale");assertThrows(IOException.class,b::save);assertEquals(3,store.versions(file).size());assertEquals("external",Files.readString(file));
        Path blocked=root.resolve("not-a-directory");Files.writeString(blocked,"sentinel");var fresh=new FileBuffer(file.toFile());fresh.localHistory(new LocalHistoryStore(blocked));fresh.setContent("saved despite history failure");fresh.save();assertEquals("saved despite history failure",Files.readString(file));assertFalse(fresh.localHistoryError().isBlank());assertEquals("sentinel",Files.readString(blocked));
    }
    @Test void restoreRejectsStaleRevisionAndPreservesUndoAndDisk()throws Exception{
        Path file=root.resolve("Source.java");Files.writeString(file,"saved");
        SwingUtilities.invokeAndWait(()->{try{var b=new FileBuffer(file.toFile());long revision=b.documentRevision();LocalHistoryDialog.restore(b,revision,"restored 👩‍💻");assertEquals("restored 👩‍💻",b.getContent());assertEquals("saved",Files.readString(file));assertThrows(IllegalStateException.class,()->LocalHistoryDialog.restore(b,revision,"stale"));b.getUndoManager().undo();assertEquals("saved",b.getContent());b.getUndoManager().redo();assertEquals("restored 👩‍💻",b.getContent());}catch(Exception e){throw new RuntimeException(e);}});
    }
    @Test void boundsGlobalStorageAndSkipsOversizedContent()throws Exception{
        Path disabled=root.resolve("disabled");new LocalHistoryStore(disabled,()->false).record(root.resolve("Source.java"),"not retained");assertFalse(Files.exists(disabled));
        var store=new LocalHistoryStore(root.resolve("history"));for(int i=0;i<260;i++)store.record(root.resolve("file"+(i%20)+".txt"),"version"+i);
        long count;try(var paths=Files.walk(root.resolve("history"))){count=paths.filter(Files::isRegularFile).count();}assertEquals(LocalHistoryStore.MAX_VERSIONS,count);
        Path file=root.resolve("large.txt");store.record(file,"x".repeat(LocalHistoryStore.MAX_UNITS+1));assertTrue(store.versions(file).isEmpty());
    }
}
