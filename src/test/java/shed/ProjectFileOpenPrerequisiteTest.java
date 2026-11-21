package shed;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** prerequisite characterizations, not qualification of an implemented file-open command. */
class ProjectFileOpenPrerequisiteTest {
    @TempDir Path temporary;

    static Object key(Path path) throws Exception {
        Object identity=Files.readAttributes(path,BasicFileAttributes.class,LinkOption.NOFOLLOW_LINKS).fileKey();
        assertNotNull(identity);return identity;
    }

    @Test void canonicalPreflightAndRestoredIdentityDoNotPinTheBytesReadByLegacyLoad() throws Exception {
        Path root=Files.createDirectory(temporary.resolve("project")).toRealPath();
        Path directory=Files.createDirectory(root.resolve("nested"));
        Path target=Files.writeString(directory.resolve("Example.java"),"approved fixture\n");
        Path outside=Files.createDirectory(temporary.resolve("outside"));
        Path denied=Files.writeString(outside.resolve("Example.java"),"unapproved synthetic fixture\n");
        Path validated=WorkspacePaths.resolve(root,"nested/Example.java");
        Object rootKey=key(root),targetKey=key(target);Path saved=root.resolve("held");
        var store=new LegacyDocumentStore(null);FileBuffer loaded;WorkspaceTextFile.Snapshot snapshot;
        Files.move(directory,saved);
        try {
            Files.createSymbolicLink(directory,outside);
            try {
                // deterministic interposition between preflight and the existing pathname loader
                loaded=store.load(validated);
                snapshot=WorkspaceTextFile.capture(WorkspacePaths.id(root),validated,false);
            } finally {Files.delete(directory);}
        } finally {Files.move(saved,directory);}
        assertEquals(rootKey,key(root));assertEquals(targetKey,key(target));
        assertEquals(validated,WorkspacePaths.resolve(root,"nested/Example.java"));
        assertEquals(validated,loaded.getFile().toPath().toRealPath());
        assertEquals(Files.readString(denied),loaded.getContent());
        assertEquals(loaded.getContent(),snapshot.document().content());
        assertNotEquals(Files.readString(target),loaded.getContent());
        assertEquals("approved fixture\n",Files.readString(target));
        assertEquals("unapproved synthetic fixture\n",Files.readString(denied));
        assertTrue(store.legacyBuffers().isEmpty());
        System.out.println("A221_BOUNDARY "+MiniJson.stringify(Map.of("root_identity_restored",true,"target_identity_restored",true,
            "loaded_path_approved_after_restore",true,"loaded_bytes_from_unapproved_synthetic_sibling",true,
            "bounded_snapshot_also_follows_replaced_ancestor",true,"installed_documents",0)));
    }

    @Test void missingOrRemovedTargetsBecomeEmptyNamedBuffersInTheLegacyStore() throws Exception {
        Path root=Files.createDirectory(temporary.resolve("project")).toRealPath();
        var store=new LegacyDocumentStore(null);
        for(boolean previouslyPresent:List.of(false,true)) {
            Path file=root.resolve(previouslyPresent?"vanished.java":"missing.java");
            if(previouslyPresent)Files.writeString(file,"approved fixture\n");
            Path validated=WorkspacePaths.resolve(root,file.getFileName().toString());
            if(previouslyPresent)Files.delete(file);
            var loaded=store.load(validated);
            assertEquals(file.toFile(),loaded.getFile());assertTrue(loaded.hasFilePath());
            assertEquals("",loaded.getContent());assertEquals("",loaded.getSavedContent());
            assertFalse(loaded.isModified());assertFalse(Files.exists(file));
        }
        assertTrue(store.legacyBuffers().isEmpty());
    }

    @Test void existingResolverIsBroaderThanRelativeExistingRegularFileContract() throws Exception {
        Path root=Files.createDirectory(temporary.resolve("project")).toRealPath();
        Path nested=Files.createDirectory(root.resolve("nested space"));
        Path target=Files.writeString(nested.resolve("文-é.java"),"approved fixture\n");
        assertEquals(target,WorkspacePaths.resolve(root,"nested space/文-é.java"));
        assertEquals(target,WorkspacePaths.resolve(root,target.toString()));
        assertEquals(target,WorkspacePaths.resolve(root,target.toUri().toString()));
        assertEquals(nested,WorkspacePaths.resolve(root,"nested space"));
        assertEquals(root.resolve("missing.java"),WorkspacePaths.resolve(root,"missing.java"));
        assertEquals(target,WorkspacePaths.resolve(root,"nested space/../nested space/文-é.java"));
        Path outside=Files.createDirectory(temporary.resolve("outside"));
        Path denied=Files.writeString(outside.resolve("Example.java"),"unapproved fixture\n");
        Files.createSymbolicLink(root.resolve("escape"),outside);
        Files.createSymbolicLink(nested.resolve("escape"),outside);
        Files.createSymbolicLink(root.resolve("cycle"),Path.of("cycle"));
        for(String path:List.of("../outside/Example.java",denied.toString(),denied.toUri().toString(),"escape/Example.java","nested space/escape/Example.java")) {
            var failure=assertThrows(WorkspaceApiException.class,()->WorkspacePaths.resolve(root,path));
            assertEquals(WorkspaceApiException.Code.OUTSIDE_WORKSPACE,failure.code());
            assertFalse(failure.getMessage().contains(root.toString()));assertFalse(failure.getMessage().contains(outside.toString()));
        }
        var cycle=assertThrows(WorkspaceApiException.class,()->WorkspacePaths.resolve(root,"cycle/Example.java"));
        assertEquals(WorkspaceApiException.Code.IO_ERROR,cycle.code());
    }

    @Test void statusMetadataAndNavigationGrantsKeepFileOpeningUnavailableOverProductionBridge() throws Exception {
        for(String scope:List.of("status","metadata","navigation")) {
            Path parent=Files.createDirectory(temporary.resolve(scope));
            try(var f=new EditorNavigationTest.Fixture(parent)) {
                EditorMetadataTest.edt(()->{
                    var b=f.editor.active.getBuffer();b.getDocument().insertString(0,"human ",null);
                    b.getDocument().insertString(0,"draft ",null);b.getUndoManager().undo();b.setModified(true);return null;
                });
                var before=EditorNavigationTest.source(f);var pane=f.editor.active;
                byte[] disk=Files.readAllBytes(f.editor.root.resolve("private-name.java"));
                var server=switch(scope) {
                    case "status"->WorkspaceBridgeServer.status(f.editor.application,parent.resolve("private"),List.of(f.editor.root));
                    case "metadata"->WorkspaceBridgeServer.metadata(f.editor.application,f.editor.metadata,parent.resolve("private"),List.of(f.editor.root));
                    default->WorkspaceBridgeServer.metadata(f.editor.application,f.grant,parent.resolve("private"),List.of(f.editor.root));
                };
                try(server;var client=switch(scope) {
                    case "status"->WorkspaceBridgeClient.status(server.descriptor(),server.credential());
                    case "metadata"->WorkspaceBridgeClient.metadata(server.descriptor(),server.credential());
                    default->WorkspaceBridgeClient.navigation(server.descriptor(),server.credential());
                }) {
                    for(String path:List.of("private-name.java","missing.java","../outside","nested space/文-é.java")) {
                        String response=client.exchange(WorkspaceRpcTest.request("closed","editor/file/open",Map.of("schemaVersion",1,"projectId",f.grant.projectId,"path",path,"capabilities",List.of("editor-file-open"))));
                        assertEquals("UNSUPPORTED",WorkspaceStatusContractTest.code(response));
                        assertFalse(response.contains("private-name"));assertFalse(response.contains(f.editor.root.toString()));
                    }
                    assertEquals("UNSUPPORTED",WorkspaceStatusContractTest.code(client.exchange(WorkspaceRpcTest.request("closed","authorization/grant",Map.of("capability","editor-file-open")))));
                }
                assertSame(pane,f.editor.active);assertEquals(1,f.editor.buffers.size());
                assertEquals(before,EditorNavigationTest.source(f));assertArrayEquals(disk,Files.readAllBytes(f.editor.root.resolve("private-name.java")));
                assertFalse(Files.exists(f.editor.root.resolve("missing.java")));
                WorkspaceStatusTransportTest.await(()->server.connectionCount()==0);
            }
        }
    }
}
