package shed;

import static org.junit.jupiter.api.Assertions.*;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ShedControlCliTest {
    @TempDir Path temporary;
    record Reply(int code,Map<String,Object> body) { }
    private Reply call(EditorControlTest.Fixture fixture,String...arguments){
        var args=new ArrayList<>(List.of("ctl","--connect",fixture.server.descriptor().toString()));args.addAll(List.of(arguments));
        var out=new ByteArrayOutputStream();
        int code=WorkspaceCli.run(args.toArray(String[]::new),InputStream.nullInputStream(),out,OutputStream.nullOutputStream());
        return new Reply(code,MiniJson.asObject(MiniJson.parseStrict(out.toString(java.nio.charset.StandardCharsets.UTF_8))));
    }
    @Test void ordinaryCommandsUseExplicitAttachmentAndCaptureCurrentContext() throws Exception {
        try(var f=new EditorControlTest.Fixture(temporary)){
            var first=call(f,"state");assertEquals(0,first.code(),first.body()::toString);
            var opened=call(f,"open","Two.java");assertEquals(0,opened.code(),opened.body()::toString);
            var read=call(f,"read");assertEquals(0,read.code());var state=MiniJson.asObject(read.body().get("result"));
            assertTrue(((String)state.get("content")).contains("class Two"));
            Path payload=temporary.resolve("chunk.txt");Files.writeString(payload,"class Replacement {}\n");
            var proposal=call(f,"replace","--document",(String)state.get("documentId"),"--revision",state.get("documentRevision").toString(),"--text-file",payload.toString());
            assertEquals(0,proposal.code(),proposal.body()::toString);assertEquals(true,MiniJson.asObject(proposal.body().get("result")).get("reviewRequired"));
            assertEquals(64,call(f,"exec","arbitrary").code());assertEquals(64,call(f,"state","--start","1").code());
        }
    }
    @Test void staleOrMissingIntentAndOversizedPayloadDoNotPropose() throws Exception {
        try(var f=new EditorControlTest.Fixture(temporary)){
            var state=MiniJson.asObject(call(f,"state").body().get("result"));
            Path payload=temporary.resolve("chunk.txt");Files.writeString(payload,"x".repeat(40000));
            assertNotEquals(0,call(f,"replace","--text-file",payload.toString()).code());
            assertNotEquals(0,call(f,"replace","--document",(String)state.get("documentId"),"--revision",state.get("documentRevision").toString(),"--text-file",payload.toString()).code());
            assertNull(f.reviewed);
        }
    }
    @Test void watchingIsVendorNeutralAndAgentNamesAreOnlyLabels() throws Exception {
        try(var f=new EditorControlTest.Fixture(temporary){
            public EditorControl.WatchRoot watchRoot(){return new EditorControl.WatchRoot(roots.active(),roots.generation());}
            public Map<String,Object> watch(String action,String label){return Map.of("kind","watch","agent",label);}
        }){
            var unnamed=call(f,"watch");assertEquals(0,unnamed.code(),unnamed.body()::toString);
            var observation=MiniJson.asObject(MiniJson.asObject(unnamed.body().get("result")).get("observation"));assertEquals("Agent",observation.get("agent"));
            for(String label:List.of("Codex","Claude Code","OpenCode","Local agent","Custom writer")){
                var reply=call(f,"watch","--agent",label);assertEquals(0,reply.code(),reply.body()::toString);
                observation=MiniJson.asObject(MiniJson.asObject(reply.body().get("result")).get("observation"));assertEquals(label,observation.get("agent"));
            }
        }
    }

}
