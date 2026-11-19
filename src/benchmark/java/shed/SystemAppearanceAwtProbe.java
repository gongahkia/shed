package shed;

import com.sun.jna.*;
import java.awt.*;
import java.beans.*;
import java.io.*;
import java.lang.management.ManagementFactory;
import java.util.*;
import java.util.List;
import java.util.concurrent.*;
import javax.swing.*;

/** Read-only native inspection and public Java listener control; never loaded by production. */
public final class SystemAppearanceAwtProbe {
    private interface MainQueue extends Library {
        void dispatch_async_f(Pointer queue, Pointer context, Work work);
    }
    private interface Work extends Callback { void invoke(Pointer context); }
    private static JFrame frame;
    private static final Toolkit toolkit = Toolkit.getDefaultToolkit();
    private static final List<String> properties = new ArrayList<>();
    private static final List<Work> retainedDiagnostics = new ArrayList<>();
    private static final PropertyChangeListener listener = event -> emit("java_property", Map.of(
        "name", event.getPropertyName(), "value", String.valueOf(event.getNewValue())));
    private static int registrations;

    private static synchronized void emit(String event, Map<String, ?> extra) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("event", event); row.put("epoch_ms", System.currentTimeMillis()); row.put("edt", SwingUtilities.isEventDispatchThread());
        row.putAll(extra); System.out.println(MiniJson.stringify(row)); System.out.flush();
    }

    private static Pointer message(Function send, NativeLibrary objc, Pointer receiver, String selector) {
        return send.invokePointer(new Object[]{receiver, objc.getFunction("sel_registerName").invokePointer(new Object[]{selector})});
    }
    private static String string(Function send, NativeLibrary objc, Pointer object) {
        if (object == null) return "nil";
        Pointer utf8 = message(send, objc, object, "UTF8String");
        return utf8 == null ? "nil" : utf8.getString(0, "UTF-8");
    }

    private static Map<String, Object> nativeAppearance() throws Exception {
        NativeLibrary objc = NativeLibrary.getInstance("/usr/lib/libobjc.A.dylib");
        Function send = objc.getFunction("objc_msgSend");
        NativeLibrary system = NativeLibrary.getInstance("System");
        var result = new CompletableFuture<Map<String, Object>>();
        Work work = ignored -> {
            try {
                Pointer cls = objc.getFunction("objc_getClass").invokePointer(new Object[]{"NSApplication"});
                Pointer app = message(send, objc, cls, "sharedApplication");
                Pointer effective = message(send, objc, app, "effectiveAppearance");
                Pointer explicit = message(send, objc, app, "appearance");
                Pointer thread = objc.getFunction("objc_getClass").invokePointer(new Object[]{"NSThread"});
                Function isMain = objc.getFunction("objc_msgSend");
                byte main = (Byte) isMain.invoke(byte.class, new Object[]{thread, objc.getFunction("sel_registerName").invokePointer(new Object[]{"isMainThread"})});
                result.complete(Map.of("effective", string(send, objc, message(send, objc, effective, "name")),
                    "explicit", explicit == null ? "nil" : string(send, objc, message(send, objc, explicit, "name")), "appkit_main_thread", main != 0));
            } catch (Throwable failure) { result.completeExceptionally(failure); }
        };
        // Retain a finite set until process exit; do not free callback memory while a native invocation may still return.
        if (retainedDiagnostics.size() >= 64) throw new IllegalStateException("Diagnostic request bound reached");
        retainedDiagnostics.add(work);
        Native.load("System", MainQueue.class).dispatch_async_f(system.getGlobalVariableAddress("_dispatch_main_q"), null, work);
        return result.get(5, TimeUnit.SECONDS);
    }

    private static void open() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            frame = new JFrame("Shed isolated appearance notification control");
            JTextArea area = new JTextArea("Opaque Swing text\nNative appearance observation only\n");
            area.setFont(new Font("Monospaced", Font.PLAIN, 16));
            frame.add(new JScrollPane(area)); frame.setSize(540, 240); frame.setLocationByPlatform(true);
            frame.setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);
            Object published = toolkit.getDesktopProperty("awt.desktopPropertyNames");
            if (published instanceof String[] names) properties.addAll(Arrays.asList(names));
            properties.add("apple.awt.application.appearance");
            properties.add("awt.font.desktophints");
            for (String name : new LinkedHashSet<>(properties)) toolkit.addPropertyChangeListener(name, listener);
            UIManager.addPropertyChangeListener(listener); frame.addPropertyChangeListener(listener);
            registrations++; frame.setVisible(true);
        });
    }
    private static void close() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            for (String name : new LinkedHashSet<>(properties)) toolkit.removePropertyChangeListener(name, listener);
            properties.clear(); UIManager.removePropertyChangeListener(listener);
            if (frame != null) { frame.removePropertyChangeListener(listener); frame.dispose(); frame = null; }
            emit("disposed", Map.of("registrations", registrations));
        });
    }
    private static void status(String request) throws Exception {
        long began = System.nanoTime();
        Map<String, Object> values = new LinkedHashMap<>(nativeAppearance());
        values.put("native_read_ms", (System.nanoTime() - began) / 1e6);
        values.put("request", request); values.put("system_property", System.getProperty("apple.awt.application.appearance", "unset"));
        values.put("desktop_property", String.valueOf(toolkit.getDesktopProperty("apple.awt.application.appearance")));
        values.put("registrations", registrations);
        var heap = ManagementFactory.getMemoryMXBean().getHeapMemoryUsage(); values.put("used_mib", heap.getUsed() / 1048576d); values.put("committed_mib", heap.getCommitted() / 1048576d);
        emit("status", values);
    }
    public static void main(String[] args) throws Exception {
        try {
            open(); status("initial"); emit("ready", Map.of());
            try (var input = new BufferedReader(new InputStreamReader(System.in))) {
                for (String command; (command = input.readLine()) != null;) {
                    if (command.equals("exit")) break;
                    if (command.equals("reopen")) { close(); open(); }
                    else if (command.equals("property-system")) System.setProperty("apple.awt.application.appearance", "system");
                    status(command);
                }
            }
            close(); emit("exit", Map.of()); System.exit(0);
        } catch (Throwable failure) { failure.printStackTrace(); close(); System.exit(2); }
    }
}
