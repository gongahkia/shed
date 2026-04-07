package shed;

// Plugin Manager Class
// Loads ~/.shed/plugins/*.shed and *.lua files, registers commands, keybindings, event hooks

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.net.URLConnection;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Stream;

public class PluginManager {
    private final ConfigManager configManager;
    private final Texteditor editor;
    private final List<PluginInfo> plugins;
    private final Map<String, List<String>> eventHooks; // event -> list of commands
    private final Map<String, PackageRecord> packageIndex;
    private final File packageIndexFile;
    private LuaEngine luaEngine;
    private int eventDepth;
    private static final int MAX_EVENT_DEPTH = 3;
    private static final Pattern PACKAGE_NAME_PATTERN = Pattern.compile("[A-Za-z0-9._-]+");

    public PluginManager(ConfigManager configManager, Texteditor editor) {
        this.configManager = configManager;
        this.editor = editor;
        this.plugins = new ArrayList<>();
        this.eventHooks = new LinkedHashMap<>();
        this.packageIndex = new LinkedHashMap<>();
        this.packageIndexFile = new File(configManager.getPluginsDirectoryPath(), ".packages.json");
        this.luaEngine = new LuaEngine(editor);
        loadPackageIndex();
        loadPlugins();
    }

    public void reload() {
        plugins.clear();
        eventHooks.clear();
        luaEngine.reset();
        loadPackageIndex();
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

    public synchronized String getPackageListText() {
        if (packageIndex.isEmpty()) {
            return "No managed plugin packages.\n\n"
                + "Use :plugin install <name> <version> <source> [--checksum=<sha256>] [--pin]\n";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("Plugin Packages\n");
        sb.append("=".repeat(40)).append("\n\n");
        List<String> names = new ArrayList<>(packageIndex.keySet());
        Collections.sort(names);
        for (String key : names) {
            PackageRecord record = packageIndex.get(key);
            if (record == null) {
                continue;
            }
            sb.append(record.name)
                .append(" @ ").append(record.version)
                .append(record.pinned ? "  [pinned]" : "")
                .append("\n");
            sb.append("  file: ").append(record.fileName).append("\n");
            sb.append("  source: ").append(record.source).append("\n");
            if (record.checksum != null && !record.checksum.isBlank()) {
                sb.append("  sha256: ").append(record.checksum).append("\n");
            }
            if (record.expectedChecksum != null && !record.expectedChecksum.isBlank()) {
                sb.append("  expected: ").append(record.expectedChecksum).append("\n");
            }
            sb.append("\n");
        }
        return sb.toString();
    }

    public synchronized String installPackage(String rawArgs) {
        ParsedInstallSpec spec;
        try {
            spec = parseInstallSpec(rawArgs);
        } catch (IOException e) {
            return "Plugin install failed: " + e.getMessage();
        }
        if (spec == null) {
            return "Usage: :plugin install <name> <version> <source> [--checksum=<sha256>] [--pin]";
        }
        try {
            PackageRecord record = installPackageInternal(spec, true);
            reload();
            return "Installed plugin package: " + record.name + "@" + record.version
                + (record.pinned ? " (pinned)" : "")
                + " sha256=" + shortChecksum(record.checksum);
        } catch (IOException e) {
            return "Plugin install failed: " + e.getMessage();
        }
    }

    public synchronized String updatePackage(String rawArgs) {
        String target = rawArgs == null ? "" : rawArgs.trim();
        if (packageIndex.isEmpty()) {
            return "No managed plugin packages";
        }
        List<PackageRecord> candidates = new ArrayList<>();
        if (target.isEmpty()) {
            candidates.addAll(packageIndex.values());
        } else {
            PackageRecord record = packageIndex.get(normalizePackageKey(target));
            if (record == null) {
                return "Managed package not found: " + target;
            }
            candidates.add(record);
        }

        int updated = 0;
        int skipped = 0;
        List<String> failures = new ArrayList<>();
        for (PackageRecord record : candidates) {
            if (record == null) {
                continue;
            }
            if (record.pinned) {
                skipped++;
                continue;
            }
            try {
                ParsedInstallSpec spec = new ParsedInstallSpec(
                    record.name,
                    record.version,
                    record.source,
                    record.expectedChecksum,
                    false
                );
                installPackageInternal(spec, false);
                updated++;
            } catch (IOException e) {
                failures.add(record.name + ": " + e.getMessage());
            }
        }
        try {
            savePackageIndex();
        } catch (IOException e) {
            return "Plugin update failed while saving index: " + e.getMessage();
        }
        reload();
        if (!failures.isEmpty()) {
            return "Plugin update completed with failures (updated " + updated + ", skipped " + skipped + "): "
                + String.join("; ", failures);
        }
        return "Updated " + updated + " package" + (updated == 1 ? "" : "s")
            + (skipped > 0 ? " (" + skipped + " pinned skipped)" : "");
    }

    public synchronized String removePackage(String name) {
        if (name == null || name.isBlank()) {
            return "Usage: :plugin remove <name>";
        }
        String key = normalizePackageKey(name);
        PackageRecord record = packageIndex.remove(key);
        if (record == null) {
            return "Managed package not found: " + name;
        }
        File pluginFile = new File(resolvePluginDirectory(), record.fileName);
        try {
            Files.deleteIfExists(pluginFile.toPath());
            savePackageIndex();
        } catch (IOException e) {
            return "Plugin remove failed: " + e.getMessage();
        }
        reload();
        return "Removed plugin package: " + record.name;
    }

    public synchronized String setPackagePinned(String name, boolean pinned) {
        if (name == null || name.isBlank()) {
            return "Usage: :plugin " + (pinned ? "pin" : "unpin") + " <name>";
        }
        String key = normalizePackageKey(name);
        PackageRecord record = packageIndex.get(key);
        if (record == null) {
            return "Managed package not found: " + name;
        }
        record.pinned = pinned;
        try {
            savePackageIndex();
        } catch (IOException e) {
            return "Plugin " + (pinned ? "pin" : "unpin") + " failed: " + e.getMessage();
        }
        return (pinned ? "Pinned " : "Unpinned ") + record.name + "@" + record.version;
    }

    private PackageRecord installPackageInternal(ParsedInstallSpec spec, boolean saveNow) throws IOException {
        if (spec == null) {
            throw new IOException("install spec required");
        }
        File pluginDir = resolvePluginDirectory();
        if (!pluginDir.exists()) {
            Files.createDirectories(pluginDir.toPath());
        }
        byte[] content = readPluginSource(spec.source);
        String checksum = sha256Hex(content);
        if (spec.expectedChecksum != null && !spec.expectedChecksum.isBlank()) {
            String normalizedExpected = normalizeChecksum(spec.expectedChecksum);
            if (!checksum.equals(normalizedExpected)) {
                throw new IOException("checksum mismatch (expected " + normalizedExpected + ", got " + checksum + ")");
            }
        }
        String extension = pluginExtensionFor(spec.source);
        String fileName = spec.name + extension;
        File target = new File(pluginDir, fileName);
        Files.write(
            target.toPath(),
            content,
            StandardOpenOption.CREATE,
            StandardOpenOption.TRUNCATE_EXISTING,
            StandardOpenOption.WRITE
        );
        PackageRecord record = new PackageRecord(
            spec.name,
            spec.version,
            spec.source,
            fileName,
            checksum,
            normalizeChecksum(spec.expectedChecksum),
            spec.pin,
            System.currentTimeMillis()
        );
        packageIndex.put(normalizePackageKey(record.name), record);
        if (saveNow) {
            savePackageIndex();
        }
        return record;
    }

    private ParsedInstallSpec parseInstallSpec(String rawArgs) throws IOException {
        List<String> tokens = splitArgs(rawArgs);
        List<String> positional = new ArrayList<>();
        String checksum = null;
        boolean pin = false;
        for (String token : tokens) {
            if (token == null || token.isBlank()) {
                continue;
            }
            if (token.startsWith("--checksum=")) {
                checksum = token.substring("--checksum=".length()).trim();
                continue;
            }
            if ("--pin".equals(token)) {
                pin = true;
                continue;
            }
            positional.add(token);
        }
        if (positional.size() < 3) {
            return null;
        }
        String name = sanitizePackageName(positional.get(0));
        String version = positional.get(1).trim();
        String source = positional.get(2).trim();
        if (name == null) {
            throw new IOException("invalid package name: " + positional.get(0));
        }
        if (version.isEmpty()) {
            throw new IOException("version is required");
        }
        if (source.isEmpty()) {
            throw new IOException("source is required");
        }
        return new ParsedInstallSpec(name, version, source, checksum, pin);
    }

    private File resolvePluginDirectory() {
        return new File(configManager.getPluginsDirectoryPath());
    }

    private String sanitizePackageName(String raw) {
        if (raw == null) {
            return null;
        }
        String trimmed = raw.trim();
        if (!PACKAGE_NAME_PATTERN.matcher(trimmed).matches()) {
            return null;
        }
        return trimmed;
    }

    private String normalizePackageKey(String name) {
        return name == null ? "" : name.trim().toLowerCase(Locale.ROOT);
    }

    private String pluginExtensionFor(String source) {
        String lower = source == null ? "" : source.toLowerCase(Locale.ROOT);
        int query = lower.indexOf('?');
        if (query >= 0) {
            lower = lower.substring(0, query);
        }
        return lower.endsWith(".lua") ? ".lua" : ".shed";
    }

    private byte[] readPluginSource(String source) throws IOException {
        if (source == null || source.isBlank()) {
            throw new IOException("source is required");
        }
        if (source.startsWith("http://") || source.startsWith("https://")) {
            URLConnection connection = new URL(source).openConnection();
            connection.setConnectTimeout(5000);
            connection.setReadTimeout(15000);
            try (InputStream input = connection.getInputStream()) {
                return readBytesCapped(input, 2 * 1024 * 1024);
            }
        }
        File file = new File(source);
        if (!file.isFile()) {
            throw new IOException("source file not found: " + source);
        }
        long size = Files.size(file.toPath());
        if (size > 2 * 1024 * 1024) {
            throw new IOException("source file too large (>2MB)");
        }
        return Files.readAllBytes(file.toPath());
    }

    private byte[] readBytesCapped(InputStream input, int limitBytes) throws IOException {
        int limit = Math.max(1024, limitBytes);
        byte[] chunk = new byte[8192];
        int total = 0;
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        while (true) {
            int read = input.read(chunk);
            if (read < 0) {
                break;
            }
            if (total + read > limit) {
                throw new IOException("remote source too large (limit " + limit + " bytes)");
            }
            out.write(chunk, 0, read);
            total += read;
        }
        return out.toByteArray();
    }

    private String sha256Hex(byte[] content) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(content == null ? new byte[0] : content);
            StringBuilder sb = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16));
                sb.append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IOException("SHA-256 unavailable", e);
        }
    }

    private String normalizeChecksum(String checksum) {
        if (checksum == null) {
            return null;
        }
        String normalized = checksum.trim().toLowerCase(Locale.ROOT);
        return normalized.isEmpty() ? null : normalized;
    }

    private String shortChecksum(String checksum) {
        if (checksum == null || checksum.isBlank()) {
            return "n/a";
        }
        return checksum.length() <= 12 ? checksum : checksum.substring(0, 12);
    }

    private List<String> splitArgs(String raw) {
        List<String> tokens = new ArrayList<>();
        if (raw == null || raw.isBlank()) {
            return tokens;
        }
        StringBuilder current = new StringBuilder();
        boolean escaped = false;
        char quote = '\0';
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (escaped) {
                current.append(c);
                escaped = false;
                continue;
            }
            if (c == '\\') {
                escaped = true;
                continue;
            }
            if (quote != '\0') {
                if (c == quote) {
                    quote = '\0';
                } else {
                    current.append(c);
                }
                continue;
            }
            if (c == '\'' || c == '"') {
                quote = c;
                continue;
            }
            if (Character.isWhitespace(c)) {
                if (!current.isEmpty()) {
                    tokens.add(current.toString());
                    current.setLength(0);
                }
                continue;
            }
            current.append(c);
        }
        if (escaped) {
            current.append('\\');
        }
        if (!current.isEmpty()) {
            tokens.add(current.toString());
        }
        return tokens;
    }

    private void loadPackageIndex() {
        packageIndex.clear();
        if (!packageIndexFile.isFile()) {
            return;
        }
        try {
            String raw = Files.readString(packageIndexFile.toPath(), StandardCharsets.UTF_8);
            Object parsed = MiniJson.parse(raw);
            Map<String, Object> root = MiniJson.asObject(parsed);
            if (root == null) {
                return;
            }
            List<Object> packages = MiniJson.asArray(root.get("packages"));
            if (packages == null) {
                return;
            }
            for (Object item : packages) {
                Map<String, Object> obj = MiniJson.asObject(item);
                if (obj == null) {
                    continue;
                }
                PackageRecord record = PackageRecord.fromJson(obj);
                if (record == null || record.name == null || record.name.isBlank()) {
                    continue;
                }
                packageIndex.put(normalizePackageKey(record.name), record);
            }
        } catch (Exception ignored) {
        }
    }

    private void savePackageIndex() throws IOException {
        File pluginDir = resolvePluginDirectory();
        if (!pluginDir.exists()) {
            Files.createDirectories(pluginDir.toPath());
        }
        List<String> names = new ArrayList<>(packageIndex.keySet());
        Collections.sort(names);
        List<Object> records = new ArrayList<>();
        for (String name : names) {
            PackageRecord record = packageIndex.get(name);
            if (record != null) {
                records.add(record.toJson());
            }
        }
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("version", 1);
        root.put("packages", records);
        String encoded = MiniJson.stringify(root);
        Files.writeString(
            packageIndexFile.toPath(),
            encoded,
            StandardCharsets.UTF_8,
            StandardOpenOption.CREATE,
            StandardOpenOption.TRUNCATE_EXISTING,
            StandardOpenOption.WRITE
        );
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

    private static final class ParsedInstallSpec {
        final String name;
        final String version;
        final String source;
        final String expectedChecksum;
        final boolean pin;

        ParsedInstallSpec(String name, String version, String source, String expectedChecksum, boolean pin) {
            this.name = name;
            this.version = version;
            this.source = source;
            this.expectedChecksum = expectedChecksum;
            this.pin = pin;
        }
    }

    private static final class PackageRecord {
        String name;
        String version;
        String source;
        String fileName;
        String checksum;
        String expectedChecksum;
        boolean pinned;
        long installedAtEpochMs;

        PackageRecord(
            String name,
            String version,
            String source,
            String fileName,
            String checksum,
            String expectedChecksum,
            boolean pinned,
            long installedAtEpochMs
        ) {
            this.name = name;
            this.version = version;
            this.source = source;
            this.fileName = fileName;
            this.checksum = checksum;
            this.expectedChecksum = expectedChecksum;
            this.pinned = pinned;
            this.installedAtEpochMs = installedAtEpochMs;
        }

        Map<String, Object> toJson() {
            Map<String, Object> json = new LinkedHashMap<>();
            json.put("name", name);
            json.put("version", version);
            json.put("source", source);
            json.put("file", fileName);
            json.put("checksum", checksum);
            if (expectedChecksum != null && !expectedChecksum.isBlank()) {
                json.put("expectedChecksum", expectedChecksum);
            }
            json.put("pinned", pinned);
            json.put("installedAt", installedAtEpochMs);
            return json;
        }

        static PackageRecord fromJson(Map<String, Object> json) {
            if (json == null) {
                return null;
            }
            String name = MiniJson.asString(json.get("name"));
            String version = MiniJson.asString(json.get("version"));
            String source = MiniJson.asString(json.get("source"));
            String fileName = MiniJson.asString(json.get("file"));
            String checksum = MiniJson.asString(json.get("checksum"));
            String expectedChecksum = MiniJson.asString(json.get("expectedChecksum"));
            Object pinnedRaw = json.get("pinned");
            boolean pinned = pinnedRaw instanceof Boolean && (Boolean) pinnedRaw;
            Object installedRaw = json.get("installedAt");
            long installedAt = installedRaw instanceof Number ? ((Number) installedRaw).longValue() : 0L;
            return new PackageRecord(name, version, source, fileName, checksum, expectedChecksum, pinned, installedAt);
        }
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
