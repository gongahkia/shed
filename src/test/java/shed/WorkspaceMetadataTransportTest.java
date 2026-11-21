package shed;

import static org.junit.jupiter.api.Assertions.*;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** real A1 loopback/authentication/framing, no desktop or shell. */
class WorkspaceMetadataTransportTest {
    @TempDir Path temporary;
    static WorkspaceBridgeServer server(EditorMetadataTest.Fixture f, Path directory) throws Exception {
        return WorkspaceBridgeServer.metadata(f.application,f.metadata,directory,List.of(f.root));
    }
    static Map<String,Object> result(String response) {
        var object=WorkspaceStatusContractTest.object(response);
        assertFalse(object.containsKey("error"),response);return MiniJson.asObject(object.get("result"));
    }

    @Test void mutuallyAuthenticatedObservationUsesExistingTransportAndStrictPrivacySchema() throws Exception {
        try (var f=new EditorMetadataTest.Fixture(temporary);var server=server(f,temporary.resolve("private"));
             var client=WorkspaceBridgeClient.metadata(server.descriptor(),server.credential())) {
            var metadata=result(client.exchange(client.metadataRequest()));
            assertEquals(f.metadata.projectId,metadata.get("projectId"));assertEquals(f.application.windowId,metadata.get("windowId"));
            assertEquals(List.of("status","editor-metadata"),metadata.get("capabilities"));
            assertEquals(List.of("status"),result(client.exchange(WorkspaceStatusContractTest.request("editor/status"))).get("capabilities"));
            assertTrue(Files.size(server.descriptor())<1024);assertFalse(Files.readString(server.descriptor()).contains(f.root.toString()));
            assertFalse(server.statusOnly());assertTrue(server.metadataEnabled());
            EditorMetadataTest.fails("UNAUTHORIZED",()-> {
                try { WorkspaceBridgeClient.status(server.descriptor(),server.credential()).close(); } catch(java.io.IOException e) { throw new AssertionError(e); }
            });
        }
    }

    @Test void unauthenticatedAndStatusOnlyClientsReceiveNoMetadata() throws Exception {
        try (var f=new EditorMetadataTest.Fixture(temporary);var server=server(f,temporary.resolve("private"));var socket=WorkspaceStatusTransportTest.socket(server)) {
            WorkspaceStatusProtocol.write(socket.getOutputStream(),EditorMetadataTest.request(f.params()));
            String denied=WorkspaceStatusProtocol.read(socket.getInputStream());assertEquals("UNAUTHORIZED",WorkspaceStatusContractTest.code(denied));
            assertFalse(denied.contains(f.metadata.projectId));assertFalse(denied.contains("documentId"));assertFalse(denied.contains(f.root.toString()));
        }
        try (var server=WorkspaceBridgeServer.status(new ApplicationStatus(true),temporary.resolve("status"),List.of());
             var client=WorkspaceBridgeClient.status(server.descriptor(),server.credential())) {
            assertEquals("PERMISSION_DENIED",WorkspaceStatusContractTest.code(client.exchange(EditorMetadataTest.request(Map.of("schemaVersion",1,"projectId","project-00000000-0000-0000-0000-000000000000")))));
            EditorMetadataTest.fails("UNAUTHORIZED",()->{try { WorkspaceBridgeClient.metadata(server.descriptor(),server.credential()).close(); } catch(java.io.IOException e) { throw new AssertionError(e); }});
        }
    }

    @Test void wrongKeyWrongInstanceAndTamperedProjectGrantAreRejected() throws Exception {
        try (var f=new EditorMetadataTest.Fixture(temporary);var server=server(f,temporary.resolve("private"))) {
            byte[] original=Files.readAllBytes(server.descriptor());
            try {
                for(String field:List.of("windowId","instanceId","projectId")) {
                    var descriptor=WorkspaceStatusContractTest.object(new String(original,StandardCharsets.UTF_8));
                    descriptor.put(field,field.equals("projectId")?"project-00000000-0000-0000-0000-000000000000":"00000000-0000-0000-0000-000000000000");
                    Files.writeString(server.descriptor(),MiniJson.stringify(descriptor));
                    EditorMetadataTest.fails("UNAUTHORIZED",()->{try { WorkspaceBridgeClient.metadata(server.descriptor(),server.credential()).close(); } catch(java.io.IOException e) { throw new AssertionError(e); }});
                }
            } finally {Files.write(server.descriptor(),original);}
            byte[] key=Files.readAllBytes(server.credential());
            try {
                var credential=WorkspaceStatusContractTest.object(new String(key,StandardCharsets.UTF_8));credential.put("key","01".repeat(32));
                Files.writeString(server.credential(),MiniJson.stringify(credential));
                EditorMetadataTest.fails("UNAUTHORIZED",()->{try { WorkspaceBridgeClient.metadata(server.descriptor(),server.credential()).close(); } catch(java.io.IOException e) { throw new AssertionError(e); }});
            } finally {Files.write(server.credential(),key);}
        }
    }

    @Test void expiryRevocationAndTimedCleanupPreserveA1Lifetime() throws Exception {
        try (var f=new EditorMetadataTest.Fixture(temporary)) {
            var clock=new WorkspaceStatusTransportTest.MutableClock();
            try (var server=WorkspaceBridgeServer.metadata(f.application,f.metadata,temporary.resolve("clock"),List.of(f.root),Duration.ofMinutes(1),clock);
                 var client=WorkspaceBridgeClient.metadata(server.descriptor(),server.credential())) {
                result(client.exchange(client.metadataRequest()));clock.now.addAndGet(60_001);
                assertEquals("SESSION_EXPIRED",WorkspaceStatusContractTest.code(client.exchange(client.metadataRequest())));
            }
        }
        Path parent=Files.createDirectory(temporary.resolve("timer-fixture"));
        try(var f=new EditorMetadataTest.Fixture(parent)) {
            var server=WorkspaceBridgeServer.metadata(f.application,f.metadata,temporary.resolve("timer"),List.of(f.root),Duration.ofMillis(100),Clock.systemUTC());
            try {WorkspaceStatusTransportTest.await(()->!server.running());WorkspaceStatusTransportTest.await(()->!Files.exists(server.credential()));}
            finally {server.close();}
            EditorMetadataTest.fails("AUTHORIZATION_REVOKED",f::observe);
        }
    }

    @Test void revocationDuringPendingObservationDisclosesNoSnapshot() throws Exception {
        try(var f=new EditorMetadataTest.Fixture(temporary);var server=server(f,temporary.resolve("private"));
            var client=WorkspaceBridgeClient.metadata(server.descriptor(),server.credential());var workers=java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
            CountDownLatch occupied=new CountDownLatch(1),release=new CountDownLatch(1);
            javax.swing.SwingUtilities.invokeLater(()->{occupied.countDown();try {release.await(2,TimeUnit.SECONDS);}catch(InterruptedException e){Thread.currentThread().interrupt();}});
            assertTrue(occupied.await(1,TimeUnit.SECONDS));
            try {
                var pending=workers.submit(()->client.exchange(client.metadataRequest()));Thread.sleep(30);server.revokeObservation();release.countDown();
                String response=pending.get(2,TimeUnit.SECONDS);assertEquals("AUTHORIZATION_REVOKED",WorkspaceStatusContractTest.code(response));assertFalse(response.contains("documentId"));
            }finally {release.countDown();}
        }
    }

    @Test void expirationDuringCaptureIsRecheckedBeforePublishingMetadata() throws Exception {
        try(var f=new EditorMetadataTest.Fixture(temporary)) {
            var clock=new WorkspaceStatusTransportTest.MutableClock();var captures=new java.util.concurrent.atomic.AtomicInteger();
            var metadata=new EditorMetadata(f.application,new EditorMetadata.Approval(f.root,f.roots.generation()),()->{
                var snapshot=f.capture();if(captures.incrementAndGet()==1)clock.now.addAndGet(60_001);return snapshot;
            });
            try(var server=WorkspaceBridgeServer.metadata(f.application,metadata,temporary.resolve("private"),List.of(f.root),Duration.ofMinutes(1),clock);
                var client=WorkspaceBridgeClient.metadata(server.descriptor(),server.credential())) {
                String response=client.exchange(client.metadataRequest());assertEquals("SESSION_EXPIRED",WorkspaceStatusContractTest.code(response));
                assertFalse(response.contains("documentId"));
            }
        }
    }

    @Test void repeatedMalformedUnsupportedAndOversizedRequestsStayBounded() throws Exception {
        try(var f=new EditorMetadataTest.Fixture(temporary);var server=server(f,temporary.resolve("private"));
            var client=WorkspaceBridgeClient.metadata(server.descriptor(),server.credential())) {
            assertEquals("UNSUPPORTED_PROTOCOL",WorkspaceStatusContractTest.code(client.exchange(EditorMetadataTest.request(Map.of("schemaVersion",9,"projectId",f.metadata.projectId)))));
            assertEquals("INVALID_REQUEST",WorkspaceStatusContractTest.code(client.exchange(EditorMetadataTest.request(Map.of("schemaVersion",1,"projectId",f.metadata.projectId,"capabilities",List.of("write"))))));
            assertEquals("UNSUPPORTED",WorkspaceStatusContractTest.code(client.exchange(WorkspaceStatusContractTest.request("documents/read"))));
            EditorMetadataTest.fails("LIMIT_EXCEEDED",()->{try {client.exchange("x".repeat(2049));}catch(java.io.IOException e){throw new AssertionError(e);}});
            for(int i=3;i<WorkspaceStatusProtocol.MAX_QUERIES;i++)assertTrue(client.exchange(client.metadataRequest()).getBytes(StandardCharsets.UTF_8).length<=WorkspaceStatusProtocol.PAYLOAD_BYTES);
            EditorMetadataTest.fails("SESSION_INVALID",()->{try {client.exchange(client.metadataRequest());}catch(java.io.IOException e){throw new AssertionError(e);}});
        }
    }

    @Test void metadataClientRejectsExtraFieldsBadCoordinatesAndWrongIdentities() throws Exception {
        try(var f=new EditorMetadataTest.Fixture(temporary)) {
            var descriptor=new WorkspaceStatusAttachment.Descriptor(f.application.instanceId,f.application.windowId,"00000000-0000-0000-0000-000000000001",12345,
                System.currentTimeMillis()+10000,"editor-metadata",f.metadata.projectId);
            try(var session=new WorkspaceStatusProtocol.Session(descriptor,new byte[32],"00".repeat(32),"01".repeat(32),"test")) {
                var good=WorkspaceStatusContractTest.object(MiniJson.stringify(f.observe()));assertTrue(session.acceptsMetadata(good));
                var bad=new java.util.LinkedHashMap<>(good);bad.put("text","secret");assertFalse(session.acceptsMetadata(bad));
                bad=new java.util.LinkedHashMap<>(good);bad.put("caretOffset",-1L);assertFalse(session.acceptsMetadata(bad));
                bad=new java.util.LinkedHashMap<>(good);bad.put("selectionEnd",((Long)good.get("documentLength"))+1);assertFalse(session.acceptsMetadata(bad));
                bad=new java.util.LinkedHashMap<>(good);bad.put("windowId","00000000-0000-0000-0000-000000000000");assertFalse(session.acceptsMetadata(bad));
            }
        }
    }

    @Test void oversizedResponseFromEscapedRequestIdGetsABoundedAuthenticatedError() throws Exception {
        try(var f=new EditorMetadataTest.Fixture(temporary);var server=server(f,temporary.resolve("private"));
            var client=WorkspaceBridgeClient.metadata(server.descriptor(),server.credential())) {
            String request=WorkspaceRpcTest.request("\0".repeat(256),"editor/metadata",f.params());
            assertTrue(request.getBytes(StandardCharsets.UTF_8).length<=WorkspaceStatusProtocol.PAYLOAD_BYTES);
            String response=client.exchange(request);assertEquals("LIMIT_EXCEEDED",WorkspaceStatusContractTest.code(response));
            assertTrue(response.getBytes(StandardCharsets.UTF_8).length<=WorkspaceStatusProtocol.PAYLOAD_BYTES);
            assertFalse(response.contains("documentId"));result(client.exchange(client.metadataRequest()));
        }
    }

    @Test void separateHeadlessCliObservesProductionBridgeAndLeavesExternalSentinelUnchanged() throws Exception {
        Path sentinel=Files.writeString(temporary.resolve("external-sentinel"),"synthetic configuration\n");
        byte[] bytes=Files.readAllBytes(sentinel);var stamp=Files.getLastModifiedTime(sentinel);
        try(var f=new EditorMetadataTest.Fixture(temporary);var server=server(f,temporary.resolve("private"))) {
            Process child=WorkspaceCliTestProcess.start(WorkspaceCliProcessGuardTest.builder("--workspace-api","--metadata","--connect",server.descriptor().toString(),"--credential",server.credential().toString()));
            try {
                child.getOutputStream().close();assertTrue(child.waitFor(10,TimeUnit.SECONDS));assertEquals(0,child.exitValue());
                String output=new String(child.getInputStream().readNBytes(4097),StandardCharsets.UTF_8);assertTrue(output.length()<2048);
                assertEquals(f.metadata.projectId,result(output).get("projectId"));assertFalse(output.contains("private-name"));assertFalse(output.contains(f.root.toString()));
                assertEquals(0,child.getErrorStream().readNBytes(4097).length);
                System.out.println("A21_EXTERNAL_CLIENT "+MiniJson.stringify(Map.of("exit",0,"bytes",output.length(),"server","production bridge and component panes; no desktop")));
            } finally {WorkspaceCliTestProcess.stop(child);}
            WorkspaceStatusTransportTest.await(()->server.connectionCount()==0);
        }
        assertArrayEquals(bytes,Files.readAllBytes(sentinel));assertEquals(stamp,Files.getLastModifiedTime(sentinel));
    }

    @Test void metadataCliRejectsStatusCredentialsAndIncompatibleFlags() throws Exception {
        try(var server=WorkspaceBridgeServer.status(new ApplicationStatus(true),temporary.resolve("status"),List.of())) {
            var output=new ByteArrayOutputStream();var error=new ByteArrayOutputStream();
            int exit=WorkspaceCli.run(new String[]{"--workspace-api","--metadata","--connect",server.descriptor().toString(),"--credential",server.credential().toString()},
                java.io.InputStream.nullInputStream(),output,error);
            assertEquals(77,exit);assertEquals("UNAUTHORIZED",WorkspaceStatusContractTest.code(output.toString(StandardCharsets.UTF_8)));assertEquals(0,error.size());
            for(String flag:List.of("--status","--stdio","--root","--request")) {
                var args=new java.util.ArrayList<>(List.of("--workspace-api","--metadata","--connect",server.descriptor().toString(),"--credential",server.credential().toString(),flag));
                if(flag.equals("--root")||flag.equals("--request"))args.add("private");output.reset();error.reset();
                assertEquals(64,WorkspaceCli.run(args.toArray(String[]::new),java.io.InputStream.nullInputStream(),output,error));assertEquals(0,error.size());
            }
        }
    }
}
