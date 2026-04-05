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
    private int eventDepth;
    private static final int MAX_EVENT_DEPTH = 3;

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
        if (eventDepth >= MAX_EVENT_DEPTH) return; // prevent infinite recursion
        eventDepth++;
        try {
            List<String> cmds = eventHooks.get(event);
            if (cmds != null) {
                for (String cmd : cmds) {
                    editor.executeCommand(cmd);
                }
            }
            luaEngine.fireEvent(event);
        } finally {
            eventDepth--;
        }
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

    public String getPluginsDirectoryPath() {
        return configManager.getPluginsDirectoryPath();
    }

    public String disablePlugin(String name) {
        File pluginDir = new File(configManager.getPluginsDirectoryPath());
        if (findDisabledFile(pluginDir, name) != null) return "Already disabled: " + name;
        File target = findPluginFile(pluginDir, name);
        if (target == null) return "Plugin not found: " + name;
        File disabled = new File(target.getAbsolutePath() + ".disabled");
        if (!target.renameTo(disabled)) return "Failed to disable: " + name;
        reload();
        return "Disabled plugin: " + name;
    }

    public String enablePlugin(String name) {
        File pluginDir = new File(configManager.getPluginsDirectoryPath());
        if (findPluginFile(pluginDir, name) != null) return "Already enabled: " + name;
        File disabled = findDisabledFile(pluginDir, name);
        if (disabled == null) return "No disabled plugin found: " + name;
        String enabledName = disabled.getName().replaceFirst("\\.disabled$", "");
        File enabled = new File(disabled.getParent(), enabledName);
        if (!disabled.renameTo(enabled)) return "Failed to enable: " + name;
        reload();
        return "Enabled plugin: " + name;
    }

    public String getPluginInfoText(String name) {
        for (PluginInfo p : plugins) {
            if (p.name.equalsIgnoreCase(name) || p.file.equalsIgnoreCase(name)
                || p.file.replace(".shed", "").equalsIgnoreCase(name)
                || p.file.replace(".lua", "").equalsIgnoreCase(name)) {
                return formatPluginInfo(p);
            }
        }
        for (LuaEngine.LuaPluginInfo lp : luaEngine.getLoadedScripts()) {
            if (lp.file.equalsIgnoreCase(name) || lp.file.replace(".lua", "").equalsIgnoreCase(name)) {
                return formatLuaPluginInfo(lp);
            }
        }
        return "Plugin not found: " + name;
    }

    public File createPluginFile(String name) throws IOException {
        File pluginDir = new File(configManager.getPluginsDirectoryPath());
        if (!pluginDir.exists()) pluginDir.mkdirs();
        boolean isLua = name.endsWith(".lua");
        String fileName = name.contains(".") ? name : name + ".shed";
        File file = new File(pluginDir, fileName);
        if (file.exists()) return file; // open existing
        if (isLua || fileName.endsWith(".lua")) {
            Files.writeString(file.toPath(),
                "-- " + name.replace(".lua", "") + " plugin\n"
                + "-- shed.* API: get_line, set_line, line_count, get_text,\n"
                + "-- file_path, file_name, is_modified, cursor_line, cursor_col,\n"
                + "-- command, message, shell, config_get, config_set, mode, on\n\n"
                + "shed.on(\"BufOpen\", function()\n"
                + "  -- your code here\n"
                + "end)\n");
        } else {
            Files.writeString(file.toPath(),
                "# @name " + name.replace(".shed", "") + "\n"
                + "# @description\n"
                + "# @command example=!echo hello\n"
                + "# @event BufOpen=:example\n"
                + "# @bind normal gx=:example\n");
        }
        return file;
    }

    public List<String> listDisabledPlugins() {
        List<String> disabled = new ArrayList<>();
        File pluginDir = new File(configManager.getPluginsDirectoryPath());
        if (!pluginDir.isDirectory()) return disabled;
        File[] files = pluginDir.listFiles();
        if (files == null) return disabled;
        for (File f : files) {
            if (f.getName().endsWith(".disabled")) {
                disabled.add(f.getName());
            }
        }
        java.util.Collections.sort(disabled);
        return disabled;
    }

    private File findPluginFile(File dir, String name) {
        if (!dir.isDirectory()) return null;
        File[] files = dir.listFiles();
        if (files == null) return null;
        for (File f : files) {
            String fn = f.getName();
            if (fn.equals(name) || fn.equals(name + ".shed") || fn.equals(name + ".lua")) return f;
            String base = fn.endsWith(".shed") ? fn.replace(".shed", "") : fn.endsWith(".lua") ? fn.replace(".lua", "") : fn;
            if (base.equalsIgnoreCase(name)) return f;
        }
        return null;
    }

    private File findDisabledFile(File dir, String name) {
        if (!dir.isDirectory()) return null;
        File[] files = dir.listFiles();
        if (files == null) return null;
        for (File f : files) {
            String fn = f.getName();
            if (!fn.endsWith(".disabled")) continue;
            if (fn.equals(name + ".disabled") || fn.equals(name + ".shed.disabled")
                || fn.equals(name + ".lua.disabled")) return f;
            String base = fn.replace(".shed.disabled", "").replace(".lua.disabled", "").replace(".disabled", "");
            if (base.equalsIgnoreCase(name)) return f;
        }
        return null;
    }

    private String formatPluginInfo(PluginInfo p) {
        StringBuilder sb = new StringBuilder();
        sb.append("Plugin: ").append(p.name).append("\n");
        sb.append("File: ").append(p.file).append("\n");
        if (!p.description.isEmpty()) sb.append("Description: ").append(p.description).append("\n");
        sb.append("\n");
        if (!p.commands.isEmpty()) {
            sb.append("Commands:\n");
            p.commands.forEach((k, v) -> sb.append("  :").append(k).append(" -> ").append(v).append("\n"));
            sb.append("\n");
        }
        if (!p.events.isEmpty()) {
            sb.append("Events:\n");
            p.events.forEach((ev, cmd) -> sb.append("  ").append(ev).append(" -> :").append(cmd).append("\n"));
            sb.append("\n");
        }
        if (!p.bindings.isEmpty()) {
            sb.append("Bindings:\n");
            p.bindings.forEach(b -> sb.append("  ").append(b).append("\n"));
        }
        return sb.toString();
    }

    private String formatLuaPluginInfo(LuaEngine.LuaPluginInfo lp) {
        StringBuilder sb = new StringBuilder();
        sb.append("Plugin: ").append(lp.file).append("  [lua]\n");
        sb.append("Status: ").append(lp.loaded ? "loaded" : "error").append("\n");
        if (lp.error != null) sb.append("Error: ").append(lp.error).append("\n");
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
