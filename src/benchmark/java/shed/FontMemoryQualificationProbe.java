package shed;

import java.awt.*;
import java.awt.image.BufferedImage;
import java.lang.management.ManagementFactory;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;

/** JDK-only attribution control: no Shed window, document, parser or renderer is initialized. */
public final class FontMemoryQualificationProbe {
    private static Path output;
    private static void snapshot(String phase) throws Exception {
        for(int i=0;i<3;i++){System.gc();Thread.sleep(80);}
        var heap=ManagementFactory.getMemoryMXBean().getHeapMemoryUsage();
        System.out.println("MD_META "+MiniJson.stringify(Map.of("operation",phase,"heap_used_mib",heap.getUsed()/1048576d,"heap_committed_mib",heap.getCommitted()/1048576d,
            "loaded_classes",ManagementFactory.getClassLoadingMXBean().getLoadedClassCount())));
        for(var entry:Map.of("ps",java.util.List.of("/bin/ps","-o","rss=,vsz=","-p",Long.toString(ProcessHandle.current().pid())),
                "nmt",java.util.List.of(Path.of(System.getProperty("java.home"),"bin/jcmd").toString(),Long.toString(ProcessHandle.current().pid()),"VM.native_memory","summary","scale=KB"),
                "vmmap",java.util.List.of("/usr/bin/vmmap","-summary",Long.toString(ProcessHandle.current().pid()))).entrySet()) {
            var child=new ProcessBuilder(entry.getValue()).redirectErrorStream(true).redirectOutput(output.resolve(phase+"_"+entry.getKey()+".txt").toFile()).start();
            if(!child.waitFor(8,TimeUnit.SECONDS)){child.destroyForcibly();child.waitFor();}
        }
    }
    private static void draw() {
        var image=new BufferedImage(512,128,BufferedImage.TYPE_INT_ARGB);var graphics=image.createGraphics();
        try{graphics.setFont(new Font(Font.MONOSPACED,Font.PLAIN,16));graphics.drawString("Shed Markdown é 中文 😀",4,32);}finally{graphics.dispose();}
    }
    public static void main(String[] args)throws Exception {
        output=Path.of(args[3]);snapshot("jdk_before_awt_fonts");
        if(args[1].equals("fonts-draw-first")){draw();snapshot("jdk_draw_before_catalog");}
        long start=System.nanoTime();String[] names=GraphicsEnvironment.getLocalGraphicsEnvironment().getAvailableFontFamilyNames();
        System.out.println("MD_SAMPLE "+MiniJson.stringify(Map.of("operation","font_catalog_first_use","state","OK","wall_ms",(System.nanoTime()-start)/1e6,"families",names.length)));
        snapshot("jdk_after_font_catalog");
        if(!args[1].equals("fonts-draw-first")){draw();snapshot("jdk_after_text_raster");}
        System.out.println("MD_DONE");System.out.flush();System.exit(0);
    }
}
