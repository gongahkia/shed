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
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
        LuaPluginInfo info = new LuaPluginInfo(file.getName());
        Globals globals = createSandbox();
        LuaTable shed = buildShedApi();
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

    public void fireEvent(String event) {
        List<LuaFunction> callbacks = eventCallbacks.get(event);
        if (callbacks == null) return;
        for (LuaFunction fn : callbacks) {
            try {
                fn.call(LuaValue.valueOf(event)); // runs on EDT, no timeout (matches Vim autocmd behavior)
            } catch (LuaError e) {
                editor.showMessage("Plugin error: " + e.getMessage());
            } catch (Exception e) {
                editor.showMessage("Plugin error: " + e.getMessage());
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

    private LuaTable buildShedApi() {
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
        shed.set("command", new Command());
        shed.set("message", new Message());
        shed.set("shell", new Shell());
        shed.set("config_get", new ConfigGet());
        shed.set("config_set", new ConfigSet());
        shed.set("theme", new ThemeGet());
        shed.set("themes", new Themes());
        shed.set("theme_set", new ThemeSet());
        shed.set("palette_get", new PaletteGet());
        shed.set("palette_set", new PaletteSet());
        shed.set("theater", new Theater());
        shed.set("mode", new Mode());
        shed.set("on", new On());
        return shed;
    }

    // -- buffer API --

    private class GetLine extends OneArgFunction {
        public LuaValue call(LuaValue arg) {
            int lineNum = arg.checkint(); // 1-indexed
            try {
                javax.swing.JTextArea area = editor.getTextArea();
                int lineIdx = lineNum - 1;
                if (lineIdx < 0 || lineIdx >= area.getLineCount()) return LuaValue.valueOf("");
                int start = area.getLineStartOffset(lineIdx);
                int end = area.getLineEndOffset(lineIdx);
                String text = area.getText(start, end - start);
                if (text.endsWith("\n")) text = text.substring(0, text.length() - 1);
                return LuaValue.valueOf(text);
            } catch (BadLocationException e) {
                return LuaValue.valueOf("");
            }
        }
    }

    private class SetLine extends TwoArgFunction {
        public LuaValue call(LuaValue arg1, LuaValue arg2) {
            int lineNum = arg1.checkint();
            String newText = arg2.checkjstring();
            try {
                javax.swing.JTextArea area = editor.getTextArea();
                int lineIdx = lineNum - 1;
                if (lineIdx < 0 || lineIdx >= area.getLineCount()) return LuaValue.FALSE;
                int start = area.getLineStartOffset(lineIdx);
                int end = area.getLineEndOffset(lineIdx);
                String existing = area.getText(start, end - start);
                boolean hadNewline = existing.endsWith("\n");
                area.replaceRange(newText + (hadNewline ? "\n" : ""), start, end);
                return LuaValue.TRUE;
            } catch (BadLocationException e) {
                return LuaValue.FALSE;
            }
        }
    }

    private class LineCount extends ZeroArgFunction {
        public LuaValue call() {
            return LuaValue.valueOf(editor.getTextArea().getLineCount());
        }
    }

    private class GetText extends ZeroArgFunction {
        public LuaValue call() {
            return LuaValue.valueOf(editor.getTextArea().getText());
        }
    }

    private class FilePath extends ZeroArgFunction {
        public LuaValue call() {
            FileBuffer buf = editor.getCurrentBuffer();
            if (buf == null || !buf.hasFilePath()) return LuaValue.valueOf("");
            return LuaValue.valueOf(buf.getFilePath());
        }
    }

    private class FileName extends ZeroArgFunction {
        public LuaValue call() {
            FileBuffer buf = editor.getCurrentBuffer();
            if (buf == null) return LuaValue.valueOf("");
            return LuaValue.valueOf(buf.getDisplayName());
        }
    }

    private class IsModified extends ZeroArgFunction {
        public LuaValue call() {
            FileBuffer buf = editor.getCurrentBuffer();
            if (buf == null) return LuaValue.FALSE;
            return LuaValue.valueOf(buf.isModified());
        }
    }

    // -- cursor API --

    private class CursorLine extends ZeroArgFunction {
        public LuaValue call() {
            return LuaValue.valueOf(editor.getCurrentLineNumber());
        }
    }

    private class CursorCol extends ZeroArgFunction {
        public LuaValue call() {
            try {
                javax.swing.JTextArea area = editor.getTextArea();
                int caret = area.getCaretPosition();
                int line = area.getLineOfOffset(caret);
                return LuaValue.valueOf(caret - area.getLineStartOffset(line));
            } catch (BadLocationException e) {
                return LuaValue.valueOf(0);
            }
        }
    }

    // -- command API --

    private class Command extends OneArgFunction {
        public LuaValue call(LuaValue arg) {
            String cmd = arg.checkjstring();
            try {
                String result = editor.executeCommand(cmd);
                return LuaValue.valueOf(result == null ? "" : result);
            } catch (Exception e) {
                return LuaValue.valueOf("Error: " + e.getMessage());
            }
        }
    }

    private class Message extends OneArgFunction {
        public LuaValue call(LuaValue arg) {
            editor.showMessage(arg.checkjstring());
            return LuaValue.NIL;
        }
    }

    private class Shell extends OneArgFunction {
        public LuaValue call(LuaValue arg) {
            String cmd = arg.checkjstring();
            try {
                ProcessBuilder pb = new ProcessBuilder("bash", "-c", cmd);
                pb.redirectErrorStream(true);
                FileBuffer buf = editor.getCurrentBuffer();
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
            String result = persist
                ? editor.setConfigOptionPersistent(key, value)
                : editor.setConfigOption(key, value);
            return LuaValue.valueOf(result == null ? "" : result);
        }
    }

    // -- theme API --

    private class ThemeGet extends ZeroArgFunction {
        public LuaValue call() {
            return LuaValue.valueOf(editor.getCurrentThemeName());
        }
    }

    private class Themes extends ZeroArgFunction {
        public LuaValue call() {
            LuaTable table = new LuaTable();
            List<String> themes = editor.getThemeIdsForPlugins();
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
            String result = editor.applyThemeFromPlugin(theme, persist);
            return LuaValue.valueOf(result == null ? "" : result);
        }
    }

    private class PaletteGet extends ZeroArgFunction {
        public LuaValue call() {
            LuaTable table = new LuaTable();
            Map<String, String> palette = editor.getActiveThemePaletteHex();
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
            String result = editor.applyPaletteOverridesFromPlugin(overrides, persist);
            return LuaValue.valueOf(result == null ? "" : result);
        }
    }

    private class Theater extends OneArgFunction {
        public LuaValue call(LuaValue arg) {
            String preset = arg.optjstring("");
            String result = editor.applyTheaterPreset(preset);
            return LuaValue.valueOf(result == null ? "" : result);
        }
    }

    // -- mode API --

    private class Mode extends ZeroArgFunction {
        public LuaValue call() {
            FileBuffer buf = editor.getCurrentBuffer(); // just to check we have an editor
            // access mode via the command
            String result = editor.getModeName();
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
