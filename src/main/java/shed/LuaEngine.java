package shed;

// Lua Scripting Engine
// Sandboxed LuaJ environment exposing shed.* API for plugin scripting

import org.luaj.vm2.Globals;
import org.luaj.vm2.LuaError;
import org.luaj.vm2.LuaFunction;
import org.luaj.vm2.LuaTable;
import org.luaj.vm2.LuaValue;
import org.luaj.vm2.Varargs;
import org.luaj.vm2.compiler.LuaC;
import org.luaj.vm2.lib.BaseLib;
import org.luaj.vm2.lib.CoroutineLib;
import org.luaj.vm2.lib.MathLib;
import org.luaj.vm2.lib.OneArgFunction;
import org.luaj.vm2.lib.PackageLib;
import org.luaj.vm2.lib.StringLib;
import org.luaj.vm2.lib.TableLib;
import org.luaj.vm2.lib.TwoArgFunction;
import org.luaj.vm2.lib.VarArgFunction;
import org.luaj.vm2.lib.ZeroArgFunction;
import org.luaj.vm2.lib.jse.JseBaseLib;
import org.luaj.vm2.lib.jse.JseMathLib;
import javax.swing.text.BadLocationException;
import javax.swing.SwingUtilities;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

public class LuaEngine {
    private final Texteditor editor;
    private final List<LuaPluginInfo> loadedScripts;
    private final Map<String, List<LuaFunction>> eventCallbacks;
    private static final long LOAD_TIMEOUT_MS = 5000;
    private static final long EVENT_TIMEOUT_MS = 1000;

    public LuaEngine(Texteditor editor) {
        this.editor = editor;
        this.loadedScripts = new ArrayList<>();
        this.eventCallbacks = new LinkedHashMap<>();
    }

    public void reset() {
        loadedScripts.clear();
        eventCallbacks.clear();
    }

    public void loadScript(File file) {
        loadScript(file, Set.of("command", "keybind", "event", "lua", "shell", "config.write"));
    }

    public void loadScript(File file, Set<String> permissions) {
        LuaPluginInfo info = new LuaPluginInfo(file.getName());
        Set<String> allowed = permissions == null ? new LinkedHashSet<>() : new LinkedHashSet<>(permissions);
        Globals globals = createSandbox();
        LuaTable shed = buildShedApi(allowed);
        globals.set("shed", shed);
        ExecutorService exec = Executors.newSingleThreadExecutor();
        try {
            LuaValue chunk = globals.loadfile(file.getAbsolutePath());
            Future<?> future = exec.submit(() -> chunk.call());
            future.get(LOAD_TIMEOUT_MS, TimeUnit.MILLISECONDS);
            info.loaded = true;
        } catch (TimeoutException e) {
            info.error = "timed out after " + LOAD_TIMEOUT_MS + "ms";
            System.err.println("Lua plugin timeout [" + file.getName() + "]: script took too long to load");
        } catch (java.util.concurrent.ExecutionException e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            info.error = cause.getMessage();
            System.err.println("Lua plugin error [" + file.getName() + "]: " + cause.getMessage());
        } catch (Exception e) {
            info.error = e.getMessage();
            System.err.println("Lua plugin error [" + file.getName() + "]: " + e.getMessage());
        } finally {
            exec.shutdownNow();
        }
        loadedScripts.add(info);
    }

    public void recordSkippedScript(File file, String reason) {
        LuaPluginInfo info = new LuaPluginInfo(file == null ? "(unknown)" : file.getName());
        info.loaded = false;
        info.error = reason == null || reason.isBlank() ? "blocked" : reason;
        loadedScripts.add(info);
    }

    public void fireEvent(String event) {
        List<LuaFunction> callbacks = eventCallbacks.get(event);
        if (callbacks == null) return;
        for (LuaFunction fn : callbacks) {
            ExecutorService exec = Executors.newSingleThreadExecutor();
            try {
                Future<?> future = exec.submit(() -> fn.call(LuaValue.valueOf(event)));
                future.get(EVENT_TIMEOUT_MS, TimeUnit.MILLISECONDS);
            } catch (TimeoutException e) {
                showPluginMessage("Plugin event timeout: " + event);
            } catch (LuaError e) {
                showPluginMessage("Plugin error: " + e.getMessage());
            } catch (Exception e) {
                showPluginMessage("Plugin error: " + e.getMessage());
            } finally {
                exec.shutdownNow();
            }
        }
    }

    public List<LuaPluginInfo> getLoadedScripts() {
        return loadedScripts;
    }

    private Globals createSandbox() {
        Globals globals = new Globals();
        globals.load(new JseBaseLib());
        globals.load(new PackageLib());
        globals.load(new StringLib());
        globals.load(new JseMathLib());
        globals.load(new TableLib());
        globals.load(new CoroutineLib());
        LuaC.install(globals); // bytecode compiler for loading scripts
        // no OsLib, IoLib, or LuajavaLib
        globals.set("dofile", LuaValue.NIL);
        globals.set("loadfile", LuaValue.NIL);
        return globals;
    }

    private LuaTable buildShedApi(Set<String> permissions) {
        LuaTable shed = new LuaTable();
        shed.set("get_line", new GetLine());
        shed.set("set_line", new SetLine());
        shed.set("line_count", new LineCount());
        shed.set("get_text", new GetText());
        shed.set("file_path", new FilePath());
        shed.set("file_name", new FileName());
        shed.set("is_modified", new IsModified());
        shed.set("cursor_line", new CursorLine());
        shed.set("cursor_col", new CursorCol());
        shed.set("command", permissions != null && permissions.contains("command") ? new Command() : new DeniedOneArg("command permission required"));
        shed.set("message", new Message());
        shed.set("shell", permissions != null && permissions.contains("shell") ? new Shell() : new DeniedOneArg("shell permission required"));
        shed.set("config_get", new ConfigGet());
        shed.set("config_set", permissions != null && permissions.contains("config.write") ? new ConfigSet() : new DeniedVarArg("config.write permission required"));
        shed.set("theme", new ThemeGet());
        shed.set("themes", new Themes());
        shed.set("theme_set", permissions != null && permissions.contains("config.write") ? new ThemeSet() : new DeniedVarArg("config.write permission required"));
        shed.set("palette_get", new PaletteGet());
        shed.set("palette_set", permissions != null && permissions.contains("config.write") ? new PaletteSet() : new DeniedVarArg("config.write permission required"));
        shed.set("mode", new Mode());
        shed.set("on", permissions != null && permissions.contains("event") ? new On() : new DeniedTwoArg("event permission required"));
        return shed;
    }

    private <T> T onEdt(Callable<T> callable, T fallback) {
        if (editor == null || callable == null) {
            return fallback;
        }
        if (SwingUtilities.isEventDispatchThread()) {
            try {
                return callable.call();
            } catch (Exception e) {
                return fallback;
            }
        }
        final Object[] value = new Object[] {fallback};
        final Exception[] error = new Exception[] {null};
        try {
            SwingUtilities.invokeAndWait(() -> {
                try {
                    value[0] = callable.call();
                } catch (Exception e) {
                    error[0] = e;
                }
            });
        } catch (Exception e) {
            return fallback;
        }
        if (error[0] != null) {
            return fallback;
        }
        @SuppressWarnings("unchecked")
        T cast = (T) value[0];
        return cast;
    }

    private void showPluginMessage(String message) {
        if (editor == null) {
            return;
        }
        onEdt(() -> {
            editor.showMessage(message);
            return null;
        }, null);
    }

    private static class DeniedOneArg extends OneArgFunction {
        private final String message;
        DeniedOneArg(String message) {
            this.message = message;
        }
        public LuaValue call(LuaValue arg) {
            return LuaValue.valueOf(message);
        }
    }

    private static class DeniedTwoArg extends TwoArgFunction {
        private final String message;
        DeniedTwoArg(String message) {
            this.message = message;
        }
        public LuaValue call(LuaValue arg1, LuaValue arg2) {
            return LuaValue.valueOf(message);
        }
    }

    private static class DeniedVarArg extends VarArgFunction {
        private final String message;
        DeniedVarArg(String message) {
            this.message = message;
        }
        public Varargs invoke(Varargs args) {
            return LuaValue.valueOf(message);
        }
    }

    // -- buffer API --

    private class GetLine extends OneArgFunction {
        public LuaValue call(LuaValue arg) {
            int lineNum = arg.checkint(); // 1-indexed
            return LuaValue.valueOf(onEdt(() -> {
                javax.swing.JTextArea area = editor.getTextArea();
                int lineIdx = lineNum - 1;
                if (lineIdx < 0 || lineIdx >= area.getLineCount()) return "";
                int start = area.getLineStartOffset(lineIdx);
                int end = area.getLineEndOffset(lineIdx);
                String text = area.getText(start, end - start);
                if (text.endsWith("\n")) text = text.substring(0, text.length() - 1);
                return text;
            }, ""));
        }
    }

    private class SetLine extends TwoArgFunction {
        public LuaValue call(LuaValue arg1, LuaValue arg2) {
            int lineNum = arg1.checkint();
            String newText = arg2.checkjstring();
            return LuaValue.valueOf(onEdt(() -> {
                javax.swing.JTextArea area = editor.getTextArea();
                int lineIdx = lineNum - 1;
                if (lineIdx < 0 || lineIdx >= area.getLineCount()) return false;
                int start = area.getLineStartOffset(lineIdx);
                int end = area.getLineEndOffset(lineIdx);
                String existing = area.getText(start, end - start);
                boolean hadNewline = existing.endsWith("\n");
                area.replaceRange(newText + (hadNewline ? "\n" : ""), start, end);
                return true;
            }, false));
        }
    }

    private class LineCount extends ZeroArgFunction {
        public LuaValue call() {
            return LuaValue.valueOf(onEdt(() -> editor.getTextArea().getLineCount(), 0));
        }
    }

    private class GetText extends ZeroArgFunction {
        public LuaValue call() {
            return LuaValue.valueOf(onEdt(() -> editor.getTextArea().getText(), ""));
        }
    }

    private class FilePath extends ZeroArgFunction {
        public LuaValue call() {
            FileBuffer buf = onEdt(editor::getCurrentBuffer, null);
            if (buf == null || !buf.hasFilePath()) return LuaValue.valueOf("");
            return LuaValue.valueOf(buf.getFilePath());
        }
    }

    private class FileName extends ZeroArgFunction {
        public LuaValue call() {
            FileBuffer buf = onEdt(editor::getCurrentBuffer, null);
            if (buf == null) return LuaValue.valueOf("");
            return LuaValue.valueOf(buf.getDisplayName());
        }
    }

    private class IsModified extends ZeroArgFunction {
        public LuaValue call() {
            FileBuffer buf = onEdt(editor::getCurrentBuffer, null);
            if (buf == null) return LuaValue.FALSE;
            return LuaValue.valueOf(buf.isModified());
        }
    }

    // -- cursor API --

    private class CursorLine extends ZeroArgFunction {
        public LuaValue call() {
            return LuaValue.valueOf(onEdt(editor::getCurrentLineNumber, 1));
        }
    }

    private class CursorCol extends ZeroArgFunction {
        public LuaValue call() {
            return LuaValue.valueOf(onEdt(() -> {
                javax.swing.JTextArea area = editor.getTextArea();
                int caret = area.getCaretPosition();
                int line = area.getLineOfOffset(caret);
                return caret - area.getLineStartOffset(line);
            }, 0));
        }
    }

    // -- command API --

    private class Command extends OneArgFunction {
        public LuaValue call(LuaValue arg) {
            String cmd = arg.checkjstring();
            String result = onEdt(() -> editor.executeCommand(cmd), "");
            return LuaValue.valueOf(result == null ? "" : result);
        }
    }

    private class Message extends OneArgFunction {
        public LuaValue call(LuaValue arg) {
            onEdt(() -> {
                editor.showMessage(arg.checkjstring());
                return null;
            }, null);
            return LuaValue.NIL;
        }
    }

    private class Shell extends OneArgFunction {
        public LuaValue call(LuaValue arg) {
            String cmd = arg.checkjstring();
            try {
                if (!editor.getConfigManager().getShellCommandEnabled()) {
                    return LuaValue.valueOf("");
                }
                ProcessBuilder pb = new ProcessBuilder(ShellCommand.forCommand(cmd));
                pb.redirectErrorStream(true);
                FileBuffer buf = onEdt(editor::getCurrentBuffer, null);
                if (buf != null && buf.getFile() != null && buf.getFile().getParentFile() != null) {
                    pb.directory(buf.getFile().getParentFile());
                }
                Process p = pb.start();
                int maxBytes = editor.getConfigManager().getProcessOutputMaxBytes();
                byte[] raw = p.getInputStream().readNBytes(maxBytes);
                int timeout = editor.getConfigManager().getProcessTimeoutMs();
                if (!p.waitFor(timeout, java.util.concurrent.TimeUnit.MILLISECONDS)) {
                    p.destroyForcibly();
                    return LuaValue.valueOf("");
                }
                return LuaValue.valueOf(new String(raw));
            } catch (Exception e) {
                return LuaValue.valueOf("");
            }
        }
    }

    // -- config API --

    private class ConfigGet extends OneArgFunction {
        public LuaValue call(LuaValue arg) {
            String val = editor.getConfigManager().get(arg.checkjstring());
            return val == null ? LuaValue.NIL : LuaValue.valueOf(val);
        }
    }

    private class ConfigSet extends VarArgFunction {
        public Varargs invoke(Varargs args) {
            String key = args.arg(1).checkjstring();
            String value = args.arg(2).checkjstring();
            boolean persist = args.narg() >= 3 && args.arg(3).toboolean();
            String result = onEdt(() -> persist
                ? editor.setConfigOptionPersistent(key, value)
                : editor.setConfigOption(key, value), "");
            return LuaValue.valueOf(result == null ? "" : result);
        }
    }

    // -- theme API --

    private class ThemeGet extends ZeroArgFunction {
        public LuaValue call() {
            return LuaValue.valueOf(onEdt(editor::getCurrentThemeName, ""));
        }
    }

    private class Themes extends ZeroArgFunction {
        public LuaValue call() {
            LuaTable table = new LuaTable();
            List<String> themes = onEdt(editor::getThemeIdsForPlugins, List.of());
            for (int i = 0; i < themes.size(); i++) {
                table.set(i + 1, LuaValue.valueOf(themes.get(i)));
            }
            return table;
        }
    }

    private class ThemeSet extends VarArgFunction {
        public Varargs invoke(Varargs args) {
            String theme = args.arg(1).optjstring("");
            boolean persist = args.narg() >= 2 && args.arg(2).toboolean();
            if (theme == null || theme.isBlank()) {
                return LuaValue.valueOf("Usage: shed.theme_set(name [, persist])");
            }
            String result = onEdt(() -> editor.applyThemeFromPlugin(theme, persist), "");
            return LuaValue.valueOf(result == null ? "" : result);
        }
    }

    private class PaletteGet extends ZeroArgFunction {
        public LuaValue call() {
            LuaTable table = new LuaTable();
            Map<String, String> palette = onEdt(editor::getActiveThemePaletteHex, Map.of());
            for (Map.Entry<String, String> entry : palette.entrySet()) {
                if (entry.getKey() == null || entry.getValue() == null) {
                    continue;
                }
                table.set(entry.getKey(), LuaValue.valueOf(entry.getValue()));
            }
            return table;
        }
    }

    private class PaletteSet extends VarArgFunction {
        public Varargs invoke(Varargs args) {
            LuaValue value = args.arg(1);
            if (!(value instanceof LuaTable)) {
                return LuaValue.valueOf("Usage: shed.palette_set({ key='#RRGGBB', ... } [, persist])");
            }
            boolean persist = args.narg() >= 2 && args.arg(2).toboolean();
            LuaTable table = (LuaTable) value;
            Map<String, String> overrides = new LinkedHashMap<>();
            LuaValue key = LuaValue.NIL;
            while (true) {
                Varargs next = table.next(key);
                key = next.arg1();
                if (key.isnil()) {
                    break;
                }
                LuaValue val = next.arg(2);
                if (!val.isstring()) {
                    continue;
                }
                overrides.put(key.tojstring(), val.tojstring());
            }
            String result = onEdt(() -> editor.applyPaletteOverridesFromPlugin(overrides, persist), "");
            return LuaValue.valueOf(result == null ? "" : result);
        }
    }

    // -- mode API --

    private class Mode extends ZeroArgFunction {
        public LuaValue call() {
            String result = onEdt(editor::getModeName, "normal");
            return LuaValue.valueOf(result == null ? "normal" : result);
        }
    }

    // -- event API --

    private class On extends TwoArgFunction {
        public LuaValue call(LuaValue event, LuaValue fn) {
            String eventName = event.checkjstring();
            LuaFunction callback = fn.checkfunction();
            eventCallbacks.computeIfAbsent(eventName, k -> new ArrayList<>()).add(callback);
            return LuaValue.NIL;
        }
    }

    static final class LuaPluginInfo {
        final String file;
        boolean loaded;
        String error;
        LuaPluginInfo(String file) {
            this.file = file;
            this.loaded = false;
            this.error = null;
        }
    }
}
