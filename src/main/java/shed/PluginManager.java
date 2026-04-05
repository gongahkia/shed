package shed;

// Plugin Manager Class
// Loads ~/.shed/plugins/*.shed and *.lua files, registers commands, keybindings, event hooks

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

public class PluginManager {
    private final ConfigManager configManager;
    private final Texteditor editor;
    private final List<PluginInfo> plugins;
    private final Map<String, List<String>> eventHooks; // event -> list of commands
    private LuaEngine luaEngine;

    public PluginManager(ConfigManager configManager, Texteditor editor) {
        this.configManager = configManager;
        this.editor = editor;
        this.plugins = new ArrayList<>();
        this.eventHooks = new LinkedHashMap<>();
        this.luaEngine = new LuaEngine(editor);
        loadPlugins();
    }

    public void reload() {
        plugins.clear();
        eventHooks.clear();
        luaEngine.reset();
        loadPlugins();
    }

    private void loadPlugins() {
        File pluginDir = new File(configManager.getPluginsDirectoryPath());
        if (!pluginDir.isDirectory()) {
            return;
        }
        try (Stream<Path> files = Files.list(pluginDir.toPath())) {
            files.filter(p -> {
                     String name = p.toString();
                     return name.endsWith(".shed") || name.endsWith(".lua");
                 })
                 .sorted()
                 .forEach(p -> {
                     if (p.toString().endsWith(".lua")) {
                         luaEngine.loadScript(p.toFile());
                     } else {
                         loadShedPlugin(p.toFile());
                     }
                 });
        } catch (IOException ignored) {
        }
    }

    private void loadShedPlugin(File file) {
        PluginInfo info = new PluginInfo(file.getName());
        try (BufferedReader reader = new BufferedReader(new FileReader(file))) {
            String line;
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (!line.startsWith("# @")) {
                    continue;
                }
                String directive = line.substring(3).trim();
                int sep = directive.indexOf(' ');
                if (sep < 0) {
                    continue;
                }
                String key = directive.substring(0, sep).trim().toLowerCase();
                String value = directive.substring(sep + 1).trim();
                switch (key) {
                    case "name":
                        info.name = value;
                        break;
                    case "description":
                        info.description = value;
                        break;
                    case "command": {
                        int eq = value.indexOf('=');
                        if (eq > 0) {
                            String cmdName = value.substring(0, eq).trim();
                            String cmdShell = value.substring(eq + 1).trim();
                            info.commands.put(cmdName, cmdShell);
                            configManager.set("command.user." + cmdName, cmdShell);
                        }
                        break;
                    }
                    case "bind": {
                        int space = value.indexOf(' ');
                        if (space > 0) {
                            String mode = value.substring(0, space).trim().toLowerCase();
                            String mapping = value.substring(space + 1).trim();
                            int eq = mapping.indexOf('=');
                            if (eq > 0) {
                                String lhs = mapping.substring(0, eq).trim();
                                String rhs = mapping.substring(eq + 1).trim();
                                info.bindings.add(mode + " " + lhs + "=" + rhs);
                                configManager.set("keybind." + mode + "." + lhs, rhs);
                            }
                        }
                        break;
                    }
                    case "event": {
                        int eq = value.indexOf('=');
                        if (eq > 0) {
                            String event = value.substring(0, eq).trim();
                            String cmd = value.substring(eq + 1).trim();
                            if (cmd.startsWith(":")) {
                                cmd = cmd.substring(1);
                            }
                            info.events.put(event, cmd);
                            eventHooks.computeIfAbsent(event, k -> new ArrayList<>()).add(cmd);
                        }
                        break;
                    }
                }
            }
        } catch (IOException ignored) {
        }
        plugins.add(info);
    }

    public void fireEvent(String event) {
        // fire declarative .shed hooks
        List<String> cmds = eventHooks.get(event);
        if (cmds != null) {
            for (String cmd : cmds) {
                editor.executeCommand(cmd);
            }
        }
        // fire Lua callbacks
        luaEngine.fireEvent(event);
    }

    public List<String> getEventCommands(String event) {
        return eventHooks.getOrDefault(event, List.of());
    }

    public List<PluginInfo> getPlugins() {
        return plugins;
    }

    public LuaEngine getLuaEngine() {
        return luaEngine;
    }

    public String getPluginListText() {
        List<LuaEngine.LuaPluginInfo> luaScripts = luaEngine.getLoadedScripts();
        if (plugins.isEmpty() && luaScripts.isEmpty()) {
            return "No plugins loaded.\n\n"
                + "Place .shed or .lua files in ~/.shed/plugins/ to install plugins.\n"
                + "Use :help plugins for format details.\n";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("Loaded Plugins\n");
        sb.append("=".repeat(40)).append("\n\n");
        for (PluginInfo p : plugins) {
            sb.append("  ").append(p.name).append("  (").append(p.file).append(")\n");
            if (!p.description.isEmpty()) {
                sb.append("    ").append(p.description).append("\n");
            }
            if (!p.commands.isEmpty()) {
                sb.append("    commands: ").append(String.join(", ", p.commands.keySet())).append("\n");
            }
            if (!p.events.isEmpty()) {
                sb.append("    events: ");
                p.events.forEach((ev, cmd) -> sb.append(ev).append("->:").append(cmd).append(" "));
                sb.append("\n");
            }
            if (!p.bindings.isEmpty()) {
                sb.append("    bindings: ").append(String.join(", ", p.bindings)).append("\n");
            }
            sb.append("\n");
        }
        for (LuaEngine.LuaPluginInfo lp : luaScripts) {
            sb.append("  ").append(lp.file).append("  [lua]");
            if (!lp.loaded) {
                sb.append("  ERROR: ").append(lp.error);
            }
            sb.append("\n");
        }
        return sb.toString();
    }

    public static String interpolate(String shellCmd, String filePath, int line, int col, String word, String selection) {
        if (shellCmd == null) {
            return shellCmd;
        }
        String result = shellCmd;
        result = result.replace("%file", filePath == null ? "" : filePath);
        result = result.replace("%line", String.valueOf(line));
        result = result.replace("%col", String.valueOf(col));
        result = result.replace("%word", word == null ? "" : word);
        result = result.replace("%selection", selection == null ? "" : selection);
        return result;
    }

    static final class PluginInfo {
        String file;
        String name;
        String description;
        final Map<String, String> commands;
        final Map<String, String> events;
        final List<String> bindings;
        PluginInfo(String file) {
            this.file = file;
            this.name = file.replace(".shed", "");
            this.description = "";
            this.commands = new LinkedHashMap<>();
            this.events = new LinkedHashMap<>();
            this.bindings = new ArrayList<>();
        }
    }
}
