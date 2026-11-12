package shed;

import com.sun.jna.*;
import java.nio.file.*;
import java.util.concurrent.TimeUnit;

/** Capture only a window owned by this prototype process, even when it is behind another app. */
final class WindowCapture {
    interface CoreGraphics extends Library { Pointer CGWindowListCopyWindowInfo(int option, int relative); }
    interface CoreFoundation extends Library {
        long CFArrayGetCount(Pointer array);
        Pointer CFArrayGetValueAtIndex(Pointer array,long index);
        Pointer CFDictionaryGetValue(Pointer dictionary,Pointer key);
        Pointer CFStringCreateWithCString(Pointer allocator,String value,int encoding);
        byte CFStringGetCString(Pointer string,Pointer buffer,long size,int encoding);
        byte CFNumberGetValue(Pointer number,int type,Pointer value);
        void CFRelease(Pointer value);
    }
    static void capture(String title,int width,int height,Path output) throws Exception {
        if(!System.getProperty("os.name").startsWith("Mac"))throw new IllegalStateException("Window-specific capture implemented for this macOS evaluation host only");
        var cg=Native.load("/System/Library/Frameworks/CoreGraphics.framework/CoreGraphics",CoreGraphics.class);
        var cf=Native.load("/System/Library/Frameworks/CoreFoundation.framework/CoreFoundation",CoreFoundation.class);
        Pointer windows=cg.CGWindowListCopyWindowInfo(0,0);
        int match=0,fallback=0;
        if(windows==null)throw new IllegalStateException("No window metadata available");
        try {
            for(long i=0;i<cf.CFArrayGetCount(windows);i++) {
                Pointer item=cf.CFArrayGetValueAtIndex(windows,i);
                if(number(cf,value(cf,item,"kCGWindowOwnerPID"))!=ProcessHandle.current().pid())continue;
                int id=(int)number(cf,value(cf,item,"kCGWindowNumber"));
                String name=string(cf,value(cf,item,"kCGWindowName"));
                Pointer bounds=value(cf,item,"kCGWindowBounds");
                if(bounds!=null) {
                    double w=real(cf,value(cf,bounds,"Width")),h=real(cf,value(cf,bounds,"Height"));
                    if(Math.abs(w-width)<6 && Math.abs(h-height)<6) {fallback=id;if(title.equals(name))match=id;}
                }
            }
        } finally {cf.CFRelease(windows);}
        if(match==0)match=fallback;
        if(match==0)throw new IllegalStateException("Could not identify prototype-owned window: "+title);
        Process process=new ProcessBuilder("/usr/sbin/screencapture","-x","-o","-l",Integer.toString(match),output.toAbsolutePath().toString()).redirectErrorStream(true).start();
        if(!process.waitFor(15,TimeUnit.SECONDS)){process.destroyForcibly();throw new IllegalStateException("Window capture timeout");}
        String diagnostic=new String(process.getInputStream().readAllBytes());
        if(process.exitValue()!=0 || !Files.isRegularFile(output))throw new IllegalStateException("Window capture unavailable: "+diagnostic);
    }
    private static Pointer value(CoreFoundation cf,Pointer dictionary,String key) {
        Pointer string=cf.CFStringCreateWithCString(null,key,0x08000100);
        try{return cf.CFDictionaryGetValue(dictionary,string);}finally{cf.CFRelease(string);}
    }
    private static long number(CoreFoundation cf,Pointer number){if(number==null)return -1;Memory value=new Memory(8);cf.CFNumberGetValue(number,4,value);return value.getLong(0);}
    private static double real(CoreFoundation cf,Pointer number){if(number==null)return -1;Memory value=new Memory(8);cf.CFNumberGetValue(number,6,value);return value.getDouble(0);}
    private static String string(CoreFoundation cf,Pointer string){if(string==null)return "";Memory value=new Memory(4096);return cf.CFStringGetCString(string,value,4096,0x08000100)!=0?value.getString(0):"";}
}
