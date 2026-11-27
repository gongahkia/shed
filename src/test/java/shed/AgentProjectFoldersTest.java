package shed;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AgentProjectFoldersTest {
    @TempDir Path temporary;
    @Test void createsRenamesAndDeletesOnlyItsEmptyProjectFolder()throws Exception{
        Path root=Files.createDirectory(temporary.resolve("project"));var policy=AgentWorkbenchUi.projectPolicy();
        AgentProjectFolders.apply(root,AgentProjectFolders.parse("{\"kind\":\"create\",\"path\":\"one\"}"),policy,()->true);assertTrue(Files.isDirectory(root.resolve("one")));
        AgentProjectFolders.apply(root,AgentProjectFolders.parse("{\"kind\":\"rename\",\"path\":\"one\",\"destination\":\"two\"}"),policy,()->true);assertFalse(Files.exists(root.resolve("one")));assertTrue(Files.isDirectory(root.resolve("two")));
        AgentProjectFolders.apply(root,AgentProjectFolders.parse("{\"kind\":\"delete\",\"path\":\"two\"}"),policy,()->true);assertFalse(Files.exists(root.resolve("two")));
    }
    @Test void refusesNonemptyFoldersExistingDestinationsAndRevokedSessions()throws Exception{
        Path root=Files.createDirectory(temporary.resolve("project")),folder=Files.createDirectory(root.resolve("folder"));Files.writeString(folder.resolve("keep.txt"),"keep");var p=AgentWorkbenchUi.projectPolicy();
        assertThrows(java.io.IOException.class,()->AgentProjectFolders.apply(root,new AgentProjectFolders.Request("delete","folder",null),p,()->true));assertEquals("keep",Files.readString(folder.resolve("keep.txt")));
        assertThrows(java.nio.file.FileAlreadyExistsException.class,()->AgentProjectFolders.apply(root,new AgentProjectFolders.Request("create","folder",null),p,()->true));
        assertThrows(WorkspaceApiException.class,()->AgentProjectFolders.apply(root,new AgentProjectFolders.Request("create","other",null),p,()->false));assertFalse(Files.exists(root.resolve("other")));
    }
    @Test void rejectsTraversalAdministrationAndSymlinkEscape()throws Exception{
        Path root=Files.createDirectory(temporary.resolve("project")),outside=Files.createDirectory(temporary.resolve("outside"));Files.writeString(outside.resolve("sentinel"),"keep");Files.createSymbolicLink(root.resolve("link"),outside);var p=AgentWorkbenchUi.projectPolicy();
        for(String name:new String[]{"../outside","link/new",".git/new",".hg/new",".svn/new","README.md"})assertThrows(WorkspaceApiException.class,()->AgentProjectFolders.apply(root,new AgentProjectFolders.Request("create",name,null),p,()->true));
        assertEquals("keep",Files.readString(outside.resolve("sentinel")));assertFalse(Files.exists(outside.resolve("new")));
    }
    @Test void rejectsUnknownOperationsAndExtraArguments(){for(String json:new String[]{"{}","{\"kind\":\"exec\",\"path\":\"one\"}","{\"kind\":\"delete\",\"path\":\"one\",\"recursive\":true}"})assertThrows(WorkspaceApiException.class,()->AgentProjectFolders.parse(json));}
}
