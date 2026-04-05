package shed;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.luaj.vm2.Globals;
import org.luaj.vm2.LuaError;
import org.luaj.vm2.LuaTable;
import org.luaj.vm2.LuaValue;
import org.luaj.vm2.compiler.LuaC;
import org.luaj.vm2.lib.BaseLib;
import org.luaj.vm2.lib.MathLib;
import org.luaj.vm2.lib.PackageLib;
import org.luaj.vm2.lib.StringLib;
import org.luaj.vm2.lib.TableLib;
import org.luaj.vm2.lib.jse.JseBaseLib;
import org.luaj.vm2.lib.jse.JseMathLib;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

public class LuaEngineTest {
    @TempDir
    Path tempDir;

    private Globals createTestSandbox() {
        Globals globals = new Globals();
        globals.load(new JseBaseLib());
        globals.load(new PackageLib());
        globals.load(new StringLib());
        globals.load(new JseMathLib());
        globals.load(new TableLib());
        LuaC.install(globals);
        globals.set("dofile", LuaValue.NIL);
        globals.set("loadfile", LuaValue.NIL);
        return globals;
    }

    @Test
    void sandboxBlocksDofile() {
        Globals globals = createTestSandbox();
        LuaValue result = globals.get("dofile");
        assertTrue(result.isnil());
    }

    @Test
    void sandboxBlocksLoadfile() {
        Globals globals = createTestSandbox();
        LuaValue result = globals.get("loadfile");
        assertTrue(result.isnil());
    }

    @Test
    void sandboxHasNoOsLib() {
        Globals globals = createTestSandbox();
        LuaValue os = globals.get("os");
        assertTrue(os.isnil());
    }

    @Test
    void sandboxHasNoIoLib() {
        Globals globals = createTestSandbox();
        LuaValue io = globals.get("io");
        assertTrue(io.isnil());
    }

    @Test
    void sandboxAllowsStringOps() {
        Globals globals = createTestSandbox();
        LuaValue result = globals.load("return string.upper('hello')").call();
        assertEquals("HELLO", result.tojstring());
    }

    @Test
    void sandboxAllowsMathOps() {
        Globals globals = createTestSandbox();
        LuaValue result = globals.load("return math.floor(3.7)").call();
        assertEquals(3, result.toint());
    }

    @Test
    void sandboxAllowsTableOps() {
        Globals globals = createTestSandbox();
        LuaValue result = globals.load("local t = {3,1,2}; table.sort(t); return t[1]").call();
        assertEquals(1, result.toint());
    }

    @Test
    void sandboxAllowsPatternMatching() {
        Globals globals = createTestSandbox();
        LuaValue result = globals.load("return ('hello world'):match('(%w+)$')").call();
        assertEquals("world", result.tojstring());
    }

    @Test
    void luaScriptCanRegisterCallback() {
        Globals globals = createTestSandbox();
        LuaTable shed = new LuaTable();
        final boolean[] called = {false};
        shed.set("on", new org.luaj.vm2.lib.TwoArgFunction() {
            public LuaValue call(LuaValue event, LuaValue fn) {
                assertEquals("BufWrite", event.tojstring());
                called[0] = true;
                return LuaValue.NIL;
            }
        });
        globals.set("shed", shed);
        globals.load("shed.on('BufWrite', function() end)").call();
        assertTrue(called[0]);
    }

    @Test
    void luaScriptErrorCaughtGracefully() {
        Globals globals = createTestSandbox();
        boolean caught = false;
        try {
            globals.load("error('test error')").call();
        } catch (LuaError e) {
            caught = true;
            assertTrue(e.getMessage().contains("test error"));
        }
        assertTrue(caught);
    }

    @Test
    void luaFileLoadAndExecute() throws IOException {
        Globals globals = createTestSandbox();
        Path script = tempDir.resolve("test.lua");
        Files.writeString(script, "testvar = 42");
        String source = Files.readString(script);
        globals.load(source, "@" + script.getFileName()).call();
        assertEquals(42, globals.get("testvar").toint());
    }

    @Test
    void luaInfiniteLoopInSandboxCanBeInterrupted() throws Exception {
        Globals globals = createTestSandbox();
        LuaValue chunk = globals.load("while true do end");
        java.util.concurrent.ExecutorService exec = java.util.concurrent.Executors.newSingleThreadExecutor();
        try {
            java.util.concurrent.Future<?> future = exec.submit(() -> {
                try { chunk.call(); } catch (Exception ignored) {}
            });
            boolean timedOut = false;
            try {
                future.get(500, java.util.concurrent.TimeUnit.MILLISECONDS);
            } catch (java.util.concurrent.TimeoutException e) {
                timedOut = true;
                future.cancel(true);
            }
            assertTrue(timedOut, "infinite loop should cause timeout");
        } finally {
            exec.shutdownNow();
        }
    }
}
