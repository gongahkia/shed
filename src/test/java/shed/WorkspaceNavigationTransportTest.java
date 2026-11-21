package shed;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.ArrayList;
import java.util.Map;
import java.util.LinkedHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** authenticated production bridge and fixed external headless Java CLI only. */
class WorkspaceNavigationTransportTest {
    @TempDir Path temporary;
    static WorkspaceBridgeServer server(EditorNavigationTest.Fixture f,Path directory) throws Exception {
        return WorkspaceBridgeServer.metadata(f.editor.application,f.grant,directory,List.of(f.editor.root));
    }
    static Map<String,Object> result(String response) {return WorkspaceMetadataTransportTest.result(response);}
    static String request(String method,Map<String,Object> params){return WorkspaceRpcTest.request("navigate",method,params);}

    @Test void realBridgeAppliesSelectionAndReturnsActualStateThenSupportsA21Observation() throws Exception {
        try(var f=new EditorNavigationTest.Fixture(temporary);var server=server(f,temporary.resolve("private"));
            var client=WorkspaceBridgeClient.navigation(server.descriptor(),server.credential())) {
            assertTrue(server.navigationEnabled());var context=result(client.exchange(client.navigationContextRequest()));
            var selected=result(client.exchange(request("editor/selection/set",EditorNavigationTest.params(context,"editor/selection/set",1,3))));
            assertEquals(1L,selected.get("selectionStart"));assertEquals(3L,selected.get("selectionEnd"));assertEquals(true,selected.get("applied"));
            var metadata=result(client.exchange(client.metadataRequest()));assertFalse(metadata.containsKey("contextRevision"));
            assertEquals(List.of("status","editor-metadata"),metadata.get("capabilities"));assertEquals(3L,metadata.get("caretOffset"));
            assertEquals(context.get("documentRevision"),metadata.get("documentRevision"));
        }
    }

    @Test void statusAndMetadataCredentialsCannotNavigateOrSelfElevate() throws Exception {
        try(var f=new EditorNavigationTest.Fixture(temporary)) {
            var p=EditorNavigationTest.params(EditorNavigationTest.wire(f.context()),"editor/caret/set",1,1);
            try(var server=WorkspaceBridgeServer.status(f.editor.application,temporary.resolve("status"),List.of());var client=WorkspaceBridgeClient.status(server.descriptor(),server.credential())) {
                assertEquals("UNSUPPORTED",WorkspaceStatusContractTest.code(client.exchange(request("editor/caret/set",p))));
                EditorMetadataTest.fails("UNAUTHORIZED",()->{try{WorkspaceBridgeClient.navigation(server.descriptor(),server.credential()).close();}catch(java.io.IOException e){throw new AssertionError(e);}});
            }
            try(var server=WorkspaceBridgeServer.metadata(f.editor.application,f.editor.metadata,temporary.resolve("metadata"),List.of(f.editor.root));
                var client=WorkspaceBridgeClient.metadata(server.descriptor(),server.credential())) {
                assertEquals("UNSUPPORTED",WorkspaceStatusContractTest.code(client.exchange(request("editor/selection/set",p))));
                assertEquals("INVALID_REQUEST",WorkspaceStatusContractTest.code(client.exchange(WorkspaceRpcTest.request("x","editor/metadata",Map.of("schemaVersion",1,"projectId",f.editor.metadata.projectId,"capabilities",List.of("editor-navigation"))))));
            }
            assertEquals(0,EditorMetadataTest.edt(()->f.editor.active.getTextArea().getCaretPosition()));
        }
    }

    @Test void wrongInstanceProjectPaneAndStaleRevisionRejectWithoutPrivateInformation() throws Exception {
        try(var f=new EditorNavigationTest.Fixture(temporary);var server=server(f,temporary.resolve("private"));var client=WorkspaceBridgeClient.navigation(server.descriptor(),server.credential())) {
            var context=result(client.exchange(client.navigationContextRequest()));
            for(String field:List.of("instanceId","windowId","projectId","paneId","documentId","documentRevision","contextRevision")) {
                var p=EditorNavigationTest.params(context,"editor/caret/set",1,1);
                p.put(field,field.endsWith("Revision")?99999L:field.equals("instanceId")||field.equals("windowId")?"00000000-0000-0000-0000-000000000000":"other-00000000-0000-0000-0000-000000000000");
                String response=client.exchange(request("editor/caret/set",p));
                assertTrue(WorkspaceStatusContractTest.object(response).containsKey("error"));assertFalse(response.contains(f.editor.root.toString()));assertFalse(response.contains("private-name"));
            }
            assertEquals(0,EditorMetadataTest.edt(()->f.editor.active.getTextArea().getCaretPosition()));
        }
    }

    @Test void noOpenCommandCanReadOrOpenOutsideMissingDirectoryOrSymlinkTargets() throws Exception {
        try(var f=new EditorNavigationTest.Fixture(temporary);var server=server(f,temporary.resolve("private"));var client=WorkspaceBridgeClient.navigation(server.descriptor(),server.credential())) {
            Path outside=Files.writeString(temporary.resolve("outside"),"synthetic private source");Path link=Files.createSymbolicLink(f.editor.root.resolve("escape"),outside);
            for(String path:List.of("../outside",outside.toString(),link.getFileName().toString(),"missing.java",".","private-name.java")) {
                String response=client.exchange(request("editor/file/open",Map.of("schemaVersion",1,"projectId",f.grant.projectId,"path",path)));
                assertEquals("UNSUPPORTED",WorkspaceStatusContractTest.code(response));assertFalse(response.contains("\"path\""));assertFalse(response.contains(outside.toString()));
            }
            assertEquals(1,f.editor.buffers.size());assertFalse(Files.exists(f.editor.root.resolve("missing.java")));assertEquals("synthetic private source",Files.readString(outside));
        }
    }

    @Test void expiryDuringPreparationAndRevocationAtCommitDoNotApply() throws Exception {
        try(var f=new EditorNavigationTest.Fixture(temporary)) {
            var clock=new WorkspaceStatusTransportTest.MutableClock();
            try(var server=WorkspaceBridgeServer.metadata(f.editor.application,f.grant,temporary.resolve("private"),List.of(f.editor.root),Duration.ofMinutes(1),clock);
                var client=WorkspaceBridgeClient.navigation(server.descriptor(),server.credential())) {
                var context=result(client.exchange(client.navigationContextRequest()));f.beforeApply=()->clock.now.addAndGet(60_001);
                String response=client.exchange(request("editor/caret/set",EditorNavigationTest.params(context,"editor/caret/set",1,1)));
                assertEquals("SESSION_EXPIRED",WorkspaceStatusContractTest.code(response));assertEquals(0,EditorMetadataTest.edt(()->f.editor.active.getTextArea().getCaretPosition()));
            }
        }
        var parent=Files.createDirectory(temporary.resolve("revoked"));
        try(var f=new EditorNavigationTest.Fixture(parent);var server=server(f,parent.resolve("private"));var client=WorkspaceBridgeClient.navigation(server.descriptor(),server.credential())) {
            var c=result(client.exchange(client.navigationContextRequest()));f.beforeApply=()->server.revokeObservation();
            String response=client.exchange(request("editor/caret/set",EditorNavigationTest.params(c,"editor/caret/set",1,1)));
            assertEquals("AUTHORIZATION_REVOKED",WorkspaceStatusContractTest.code(response));assertEquals(0,EditorMetadataTest.edt(()->f.editor.active.getTextArea().getCaretPosition()));
        }
    }

    @Test void humanPaneSwitchDuringPreparationCannotRedirectTheCommand() throws Exception {
        try(var f=new EditorNavigationTest.Fixture(temporary);var server=server(f,temporary.resolve("private"));var client=WorkspaceBridgeClient.navigation(server.descriptor(),server.credential())) {
            var c=result(client.exchange(client.navigationContextRequest()));EditorPane original=f.editor.active;
            f.beforeApply=()->{var other=f.editor.split(original.getBuffer());f.activate(other);};
            String response=client.exchange(request("editor/caret/set",EditorNavigationTest.params(c,"editor/caret/set",1,1)));
            assertEquals("CONTEXT_CHANGED",WorkspaceStatusContractTest.code(response));assertNotSame(original,f.editor.active);
            assertEquals(0,EditorMetadataTest.edt(()->original.getTextArea().getCaretPosition()));assertEquals(0,EditorMetadataTest.edt(()->f.editor.active.getTextArea().getCaretPosition()));
        }
    }

    @Test void oversizedAcknowledgmentIsRejectedBeforeAnyCaretOrContextChange() throws Exception {
        try(var f=new EditorNavigationTest.Fixture(temporary);var server=server(f,temporary.resolve("private"));var client=WorkspaceBridgeClient.navigation(server.descriptor(),server.credential())) {
            var c=result(client.exchange(client.navigationContextRequest()));var p=EditorNavigationTest.params(c,"editor/caret/set",1,1);
            String query=WorkspaceRpcTest.request("\0".repeat(250),"editor/caret/set",p);
            assertTrue(query.getBytes(StandardCharsets.UTF_8).length<=WorkspaceStatusProtocol.PAYLOAD_BYTES);
            String response=client.exchange(query);assertEquals("LIMIT_EXCEEDED",WorkspaceStatusContractTest.code(response));
            var current=result(client.exchange(client.navigationContextRequest()));assertEquals(c.get("contextRevision"),current.get("contextRevision"));assertEquals(0L,current.get("caretOffset"));
        }
    }

    @Test void repeatedInvalidRequestsAndSequentialClientsDoNotAddListeners() throws Exception {
        try(var f=new EditorNavigationTest.Fixture(temporary);var server=server(f,temporary.resolve("private"))) {
            int count=EditorMetadataTest.edt(()->f.editor.active.getTextArea().getCaretListeners().length);
            for(int cycle=0;cycle<6;cycle++) {
                try(var client=WorkspaceBridgeClient.navigation(server.descriptor(),server.credential())) {
                    var c=result(client.exchange(client.navigationContextRequest()));
                    for(int i=0;i<20;i++)assertEquals("INVALID_OFFSET",WorkspaceStatusContractTest.code(client.exchange(request("editor/caret/set",EditorNavigationTest.params(c,"editor/caret/set",9999,9999)))));
                    assertEquals("UNSUPPORTED",WorkspaceStatusContractTest.code(client.exchange(request("commands/execute",Map.of("command","synthetic")))));
                }
                WorkspaceStatusTransportTest.await(()->server.connectionCount()==0);
            }
            assertEquals(count,EditorMetadataTest.edt(()->f.editor.active.getTextArea().getCaretListeners().length));
        }
    }

    @Test void expiredGrantCleansTrackingAndPrivateCredentials() throws Exception {
        try(var f=new EditorNavigationTest.Fixture(temporary)) {
            var server=WorkspaceBridgeServer.metadata(f.editor.application,f.grant,temporary.resolve("private"),List.of(f.editor.root),Duration.ofMillis(100),Clock.systemUTC());
            try {WorkspaceStatusTransportTest.await(()->!server.running());WorkspaceStatusTransportTest.await(()->!Files.exists(server.credential()));}
            finally{server.close();}
            EditorMetadataTest.edt(()->null);assertEquals(0,EditorMetadataTest.edt(()->f.editor.active.getTextArea().getCaretListeners().length));
        }
    }

    @Test void navigationExpiryGateNeverWaitsForCredentialCleanupMonitorOnEdt() throws Exception {
        var directory=WorkspaceStatusAttachment.privateDirectory(temporary.resolve("private"));
        try(var attachment=new WorkspaceStatusAttachment(new ApplicationStatus(true),12345,directory,List.of(),Duration.ofMinutes(1),Clock.systemUTC());
            var workers=java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
            CountDownLatch locked=new CountDownLatch(1),release=new CountDownLatch(1);
            var holding=workers.submit(()->{synchronized(attachment){locked.countDown();assertTrue(release.await(2,TimeUnit.SECONDS));}return true;});
            assertTrue(locked.await(1,TimeUnit.SECONDS));
            var captured=new java.util.concurrent.CompletableFuture<Boolean>();
            javax.swing.SwingUtilities.invokeLater(()->captured.complete(attachment.expired()));
            try{assertFalse(captured.get(200,TimeUnit.MILLISECONDS));}finally{release.countDown();}
            assertTrue(holding.get(1,TimeUnit.SECONDS));
        }
    }

    @Test void separateCliObservesNavigatesAndReobservesProductionBridge() throws Exception {
        try(var f=new EditorNavigationTest.Fixture(temporary);var server=server(f,temporary.resolve("private"))) {
            var context=runCli(server,null);
            var command=request("editor/caret/set",EditorNavigationTest.params(context,"editor/caret/set",1,1));
            var applied=runCli(server,command);assertEquals(1L,applied.get("caretOffset"));assertEquals(true,applied.get("applied"));
            var observed=runCli(server,null);assertEquals(1L,observed.get("caretOffset"));assertEquals(context.get("documentRevision"),observed.get("documentRevision"));
            WorkspaceStatusTransportTest.await(()->server.connectionCount()==0);
        }
    }
    @Test void unauthenticatedOrWrongKeyClientCannotReachNavigation() throws Exception {
        try(var f=new EditorNavigationTest.Fixture(temporary);var server=server(f,temporary.resolve("private"))) {
            var descriptor=WorkspaceStatusAttachment.Descriptor.read(server.descriptor());
            try(var socket=new java.net.Socket(java.net.InetAddress.getLoopbackAddress(),descriptor.port())) {
                socket.setSoTimeout(2000);
                WorkspaceStatusProtocol.write(socket.getOutputStream(),request("editor/caret/set",EditorNavigationTest.params(EditorNavigationTest.wire(f.context()),"editor/caret/set",1,1)));
                String response=WorkspaceStatusProtocol.read(socket.getInputStream());
                assertFalse(response.contains("documentId"));assertFalse(response.contains("private-name"));assertFalse(response.contains(f.editor.root.toString()));
                assertTrue(WorkspaceStatusContractTest.object(response).containsKey("error"));
            }
            byte[] original=Files.readAllBytes(server.credential());
            try {
                var key=WorkspaceStatusContractTest.object(new String(original,StandardCharsets.UTF_8));key.put("key","01".repeat(32));Files.writeString(server.credential(),MiniJson.stringify(key));
                EditorMetadataTest.fails("UNAUTHORIZED",()->{try{WorkspaceBridgeClient.navigation(server.descriptor(),server.credential()).close();}catch(java.io.IOException e){throw new AssertionError(e);}});
            }finally{Files.write(server.credential(),original);}
            assertEquals(0,EditorMetadataTest.edt(()->f.editor.active.getTextArea().getCaretPosition()));
        }
    }

    @Test void navigationSchemasRejectExtraFieldsVersionsNotificationsAndMalformedCoordinates() throws Exception {
        try(var f=new EditorNavigationTest.Fixture(temporary);var server=server(f,temporary.resolve("private"));var client=WorkspaceBridgeClient.navigation(server.descriptor(),server.credential())) {
            var c=result(client.exchange(client.navigationContextRequest()));
            var p=EditorNavigationTest.params(c,"editor/caret/set",1,1);p.put("capabilities",List.of("write"));
            assertEquals("INVALID_REQUEST",WorkspaceStatusContractTest.code(client.exchange(request("editor/caret/set",p))));
            p=EditorNavigationTest.params(c,"editor/caret/set",1,1);p.put("schemaVersion",2);
            assertEquals("UNSUPPORTED_PROTOCOL",WorkspaceStatusContractTest.code(client.exchange(request("editor/caret/set",p))));
            p=EditorNavigationTest.params(c,"editor/caret/set",1,1);p.put("offset",1.5);
            assertEquals("INVALID_OFFSET",WorkspaceStatusContractTest.code(client.exchange(request("editor/caret/set",p))));
            String notification=MiniJson.stringify(Map.of("jsonrpc","2.0","method","editor/caret/set","params",p));
            EditorMetadataTest.fails("INVALID_REQUEST",()->{try{client.exchange(notification);}catch(java.io.IOException e){throw new AssertionError(e);}});
            assertEquals("INVALID_REQUEST",WorkspaceStatusContractTest.code(new WorkspaceRpc(f.editor.application,f.grant).handle(notification)));
            assertEquals(0L,result(client.exchange(client.navigationContextRequest())).get("caretOffset"));
        }
    }

    static Map<String,Object> runCli(WorkspaceBridgeServer server,String request) throws Exception {
        var args=new ArrayList<>(List.of("--workspace-api","--navigate","--connect",server.descriptor().toString(),"--credential",server.credential().toString()));
        if(request!=null){args.add("--request");args.add(request);}
        var process=WorkspaceCliTestProcess.start(WorkspaceCliProcessGuardTest.builder(args.toArray(String[]::new)));
        try {
            process.getOutputStream().close();assertTrue(process.waitFor(10,TimeUnit.SECONDS));assertEquals(0,process.exitValue());
            byte[] bytes=process.getInputStream().readNBytes(4097);assertTrue(bytes.length<=2048);assertEquals(0,process.getErrorStream().readNBytes(4097).length);
            var result=result(new String(bytes,StandardCharsets.UTF_8));
            System.out.println("A22_EXTERNAL_CLIENT "+MiniJson.stringify(Map.of("exit",0,"bytes",bytes.length,"operation",request==null?"context":"caret","host","production bridge/component panes; no JFrame")));return result;
        }finally{WorkspaceCliTestProcess.stop(process);}
    }
}
