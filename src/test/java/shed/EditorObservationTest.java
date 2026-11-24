package shed;
import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.*;
import java.util.*;
import java.awt.image.BufferedImage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
class EditorObservationTest {
    @TempDir Path temporary;
    @Test void authenticatedObservationsAndBoundedCapturePreserveSource() throws Exception {
        try(var f=new EditorControlTest.Fixture(temporary){
            public Map<String,Object> observe(String method){return Map.of("kind",method.equals("editor/diagnostics")?"diagnostics":method.equals("editor/feedback")?"feedback":"preview");}
            public BufferedImage capturePreview(){return new BufferedImage(16,16,BufferedImage.TYPE_INT_RGB);}
        };var c=WorkspaceBridgeClient.control(f.server.descriptor(),f.server.credential())){
            String original=EditorControlTest.edt(()->f.pane.getBuffer().getContent());
            for(String method:List.of("editor/diagnostics","editor/feedback","editor/preview/state","editor/preview/open","editor/preview/capture")){
                var response=EditorControlTest.result(EditorControlTest.exchange(c,method,EditorControlTest.params(EditorControlTest.context(c))));
                assertEquals("observed",response.get("outcome"));
                if(method.endsWith("capture")){
                    var data=MiniJson.asObject(response.get("observation"));byte[] png=Base64.getDecoder().decode((String)data.get("data"));
                    assertEquals(16,javax.imageio.ImageIO.read(new java.io.ByteArrayInputStream(png)).getWidth());
                }
            }
            assertEquals(original,EditorControlTest.edt(()->f.pane.getBuffer().getContent()));assertFalse(EditorControlTest.edt(()->f.pane.getBuffer().isModified()));
            assertEquals("UNSUPPORTED",EditorControlTest.code(EditorControlTest.exchange(c,"desktop/capture",Map.of())));
        }
    }
    @Test void componentCaptureIsEdtOwnedAndRejectsEscapingAndOversizedRegions() throws Exception {
        var area=new javax.swing.JTextArea("hello");area.setSize(200,100);
        assertThrows(IllegalStateException.class,()->SwingComponentCapture.capture(area,0,0,200,100,20000));
        EditorControlTest.edt(()->{
            assertThrows(IllegalArgumentException.class,()->SwingComponentCapture.capture(area,-1,0,100,100,20000));
            assertThrows(IllegalArgumentException.class,()->SwingComponentCapture.capture(area,0,0,201,100,20000));
            assertThrows(IllegalArgumentException.class,()->SwingComponentCapture.capture(area,0,0,200,100,1));
            assertEquals(200,SwingComponentCapture.capture(area,0,0,200,100,20000).getWidth());return null;
        });
    }
}
