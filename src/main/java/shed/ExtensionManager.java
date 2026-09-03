package shed;

import shed.api.CustomEditorContribution;
import shed.api.DebugAdapterContribution;
import shed.api.ExtensionCommand;
import shed.api.ExtensionContext;
import shed.api.LanguageContribution;
import shed.api.RemoteWorkspaceProvider;
import shed.api.ScmContribution;
import shed.api.ShedExtension;
import shed.api.TerminalProfile;
import shed.api.TestContribution;
import shed.api.ToolViewContribution;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.net.URLConnection;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.jar.JarFile;
import org.tomlj.Toml;
import org.tomlj.TomlParseError;
import org.tomlj.TomlParseResult;

/**
 * Explicitly-installed Java extension host. A JAR runs with the user's JVM
 * permissions, so a receipt and SHA-256 verification are required before it
 * is loaded; unlike Lua plugins, this is not a sandbox.
 */
final class ExtensionManager implements AutoCloseable {
    static final int API_VERSION = 1;
    private static final String DESCRIPTOR_PATH = "META-INF/shed-extension.toml";
    private static final String INDEX_FILE = "extensions-v1.json";
    private static final int MAX_JAR_BYTES = 64 * 1024 * 1024;

    private final ConfigManager config;
    private final ExtensionRegistry registry;
    private final Path extensionDirectory;
    private final Path dataDirectory;
    private final Path indexPath;
    private final Map<String, Receipt> receipts = new LinkedHashMap<>();
    private final Map<String, Loaded> loaded = new LinkedHashMap<>();
    private final Map<String, String> errors = new LinkedHashMap<>();

    ExtensionManager(ConfigManager config, ExtensionRegistry registry) {
        this.config = config;
        this.registry = registry;
        Path shedDirectory = Path.of(config.getShedDirectoryPath());
        this.extensionDirectory = shedDirectory.resolve("extensions");
        this.dataDirectory = shedDirectory.resolve("extension-data");
        this.indexPath = extensionDirectory.resolve(INDEX_FILE);
        loadIndex();
    }

    synchronized void loadInstalled() {
        deactivateAll();
        errors.clear();
        for (Receipt receipt : orderedReceipts()) {
            if (!receipt.enabled()) continue;
            try {
                load(receipt);
            } catch (Exception error) {
                errors.put(receipt.id(), concise(error));
            }
        }
    }

    synchronized String handle(String raw) {
        String value = raw == null ? "" : raw.trim();
        if (value.isEmpty() || "list".equalsIgnoreCase(value) || "status".equalsIgnoreCase(value)) {
            return showStatus();
        }
        List<String> tokens;
        try {
            tokens = ShellCommand.directCommand(value);
        } catch (IllegalArgumentException error) {
            return "Extension command invalid: " + error.getMessage();
        }
        String operation = tokens.getFirst().toLowerCase(Locale.ROOT);
        return switch (operation) {
            case "install" -> install(tokens.subList(1, tokens.size()));
            case "remove", "uninstall" -> remove(tokens.size() == 2 ? tokens.get(1) : "");
            case "enable" -> setEnabled(tokens.size() == 2 ? tokens.get(1) : "", true);
            case "disable" -> setEnabled(tokens.size() == 2 ? tokens.get(1) : "", false);
            case "reload" -> { loadInstalled(); yield errors.isEmpty() ? "Extensions reloaded" : "Extensions reloaded with errors; run :extension status"; }
            case "path" -> "Extension directory: " + extensionDirectory;
            default -> "Usage: :extension [status|install <path-or-https-url> [--checksum=<sha256>]|remove <id>|enable <id>|disable <id>|reload|path]";
        };
    }

    synchronized String executeCommand(String id, String arguments) {
        try {
            String result = registry.executeCommand(id, arguments);
            return result == null ? null : result;
        } catch (Exception error) {
            return "Extension command " + id + " failed: " + concise(error);
        }
    }

    synchronized List<String> commandIds() {
        return registry.commandIds();
    }

    synchronized ExtensionRegistry.Owned<LanguageContribution> languageForExtension(String extension) {
        return registry.languageForExtension(extension);
    }

    synchronized List<ExtensionRegistry.Owned<LanguageContribution>> languages() { return registry.languages(); }
    synchronized List<ExtensionRegistry.Owned<DebugAdapterContribution>> debuggers() { return registry.debuggers(); }
    synchronized List<ExtensionRegistry.Owned<TestContribution>> tests() { return registry.tests(); }
    synchronized List<ExtensionRegistry.Owned<ScmContribution>> scmProviders() { return registry.scmProviders(); }
    synchronized List<ExtensionRegistry.Owned<TerminalProfile>> terminalProfiles() { return registry.terminalProfiles(); }
    synchronized List<ExtensionRegistry.Owned<ToolViewContribution>> toolViews() { return registry.toolViews(); }
    synchronized List<ExtensionRegistry.Owned<CustomEditorContribution>> customEditors() { return registry.customEditors(); }
    synchronized List<ExtensionRegistry.Owned<RemoteWorkspaceProvider>> remoteWorkspaceProviders() { return registry.remoteWorkspaceProviders(); }

    private String install(List<String> args) {
        if (args.isEmpty()) {
            return "Usage: :extension install <path-or-https-url> [--checksum=<sha256>]";
        }
        String source = args.getFirst();
        String expectedChecksum = null;
        for (int index = 1; index < args.size(); index++) {
            String argument = args.get(index);
            if (argument.startsWith("--checksum=")) {
                expectedChecksum = normalizeChecksum(argument.substring("--checksum=".length()));
                if (expectedChecksum == null) return "Extension install failed: --checksum must be a SHA-256 value";
            } else {
                return "Extension install failed: unknown option " + argument;
            }
        }
        try {
            boolean remote = isRemote(source);
            if (remote && expectedChecksum == null) {
                return "Extension install failed: HTTPS sources require --checksum=<sha256>";
            }
            byte[] content = readSource(source);
            String checksum = sha256(content);
            if (expectedChecksum != null && !expectedChecksum.equals(checksum)) {
                return "Extension install failed: checksum mismatch";
            }
            Path staging = Files.createTempFile("shed-extension-", ".jar");
            try {
                Files.write(staging, content);
                Descriptor descriptor = descriptor(staging);
                Files.createDirectories(extensionDirectory);
                Path target = extensionDirectory.resolve(descriptor.id() + "-" + descriptor.version() + ".jar").normalize();
                if (!target.getParent().equals(extensionDirectory)) throw new IOException("invalid extension target");
                AtomicFileWriter.write(target, content);
                Receipt previous = receipts.get(descriptor.id());
                if (previous != null && !previous.jarFile().equals(target.getFileName().toString())) {
                    deactivate(previous.id());
                    Files.deleteIfExists(extensionDirectory.resolve(previous.jarFile()));
                }
                Receipt receipt = new Receipt(descriptor.id(), descriptor.version(), descriptor.mainClass(), target.getFileName().toString(), checksum,
                    source, true, System.currentTimeMillis());
                receipts.put(receipt.id(), receipt);
                saveIndex();
                deactivate(receipt.id());
                load(receipt);
                return "Installed extension " + receipt.id() + "@" + receipt.version() + " sha256=" + checksum.substring(0, 12);
            } finally {
                Files.deleteIfExists(staging);
            }
        } catch (Exception error) {
            return "Extension install failed: " + concise(error);
        }
    }

    private String remove(String id) {
        String normalized = normalizeId(id);
        if (normalized == null) return "Usage: :extension remove <id>";
        Receipt receipt = receipts.remove(normalized);
        if (receipt == null) return "Extension not installed: " + id;
        try {
            deactivate(normalized);
            Files.deleteIfExists(extensionDirectory.resolve(receipt.jarFile()));
            deleteDirectory(dataDirectory.resolve(normalized));
            saveIndex();
            return "Removed extension " + normalized;
        } catch (IOException error) {
            receipts.put(normalized, receipt);
            return "Extension remove failed: " + concise(error);
        }
    }

    private String setEnabled(String id, boolean enabled) {
        String normalized = normalizeId(id);
        if (normalized == null) return "Usage: :extension " + (enabled ? "enable" : "disable") + " <id>";
        Receipt receipt = receipts.get(normalized);
        if (receipt == null) return "Extension not installed: " + id;
        receipts.put(normalized, receipt.withEnabled(enabled));
        try {
            saveIndex();
            if (enabled) load(receipts.get(normalized)); else deactivate(normalized);
            return (enabled ? "Enabled extension " : "Disabled extension ") + normalized;
        } catch (Exception error) {
            return "Extension " + (enabled ? "enable" : "disable") + " failed: " + concise(error);
        }
    }

    private void load(Receipt receipt) throws Exception {
        if (loaded.containsKey(receipt.id())) return;
        Path jar = extensionDirectory.resolve(receipt.jarFile()).normalize();
        if (!jar.getParent().equals(extensionDirectory) || !Files.isRegularFile(jar) || Files.isSymbolicLink(jar)) {
            throw new IOException("installed JAR is unavailable or unsafe");
        }
        if (!receipt.checksum().equals(sha256(Files.readAllBytes(jar)))) {
            throw new IOException("installed JAR checksum changed; reinstall explicitly");
        }
        Descriptor descriptor = descriptor(jar);
        if (!receipt.id().equals(descriptor.id()) || !receipt.version().equals(descriptor.version()) || !receipt.mainClass().equals(descriptor.mainClass())) {
            throw new IOException("installed JAR descriptor does not match its receipt");
        }
        ExtensionClassLoader classLoader = new ExtensionClassLoader(jar.toUri().toURL(), ShedExtension.class.getClassLoader());
        try {
            Class<?> type = Class.forName(descriptor.mainClass(), true, classLoader);
            if (!ShedExtension.class.isAssignableFrom(type)) throw new IOException("main_class does not implement shed.api.ShedExtension");
            ShedExtension extension = (ShedExtension) type.getDeclaredConstructor().newInstance();
            Files.createDirectories(dataDirectory.resolve(receipt.id()));
            extension.activate(new Context(receipt.id(), dataDirectory.resolve(receipt.id())));
            loaded.put(receipt.id(), new Loaded(extension, classLoader));
        } catch (Exception error) {
            registry.removeExtension(receipt.id());
            try { classLoader.close(); } catch (IOException closeError) { error.addSuppressed(closeError); }
            throw error;
        }
    }

    private void deactivateAll() {
        for (String id : List.copyOf(loaded.keySet())) deactivate(id);
    }

    private void deactivate(String id) {
        Loaded value = loaded.remove(id);
        registry.removeExtension(id);
        if (value == null) return;
        try { value.extension().deactivate(); } catch (Exception error) { errors.put(id, "deactivation failed: " + concise(error)); }
        try { value.classLoader().close(); } catch (IOException error) { errors.put(id, "class loader close failed: " + concise(error)); }
    }

    private String showStatus() {
        StringBuilder text = new StringBuilder("Extensions\n\n");
        if (receipts.isEmpty()) {
            text.append("No Java extensions installed.\n\nUse :extension install <path-or-https-url> [--checksum=<sha256>].\n");
        } else {
            for (Receipt receipt : orderedReceipts()) {
                text.append(receipt.id()).append(" @ ").append(receipt.version());
                text.append(receipt.enabled() ? "  enabled" : "  disabled");
                text.append(loaded.containsKey(receipt.id()) ? "  loaded" : "  not loaded").append('\n');
                text.append("  source: ").append(receipt.source()).append('\n');
                text.append("  sha256: ").append(receipt.checksum()).append('\n');
                String error = errors.get(receipt.id());
                if (error != null) text.append("  error: ").append(error).append('\n');
            }
        }
        appendContributions(text, "Commands", registry.commandIds());
        appendContributions(text, "Languages", registry.languages().stream().map(value -> value.extensionId() + ":" + value.value().id()).toList());
        appendContributions(text, "Debug adapters", registry.debuggers().stream().map(value -> value.extensionId() + ":" + value.value().id()).toList());
        appendContributions(text, "Test providers", registry.tests().stream().map(value -> value.extensionId() + ":" + value.value().id()).toList());
        appendContributions(text, "SCM providers", registry.scmProviders().stream().map(value -> value.extensionId() + ":" + value.value().id()).toList());
        appendContributions(text, "Terminal profiles", registry.terminalProfiles().stream().map(value -> value.extensionId() + ":" + value.value().id()).toList());
        appendContributions(text, "Tool views", registry.toolViews().stream().map(value -> value.extensionId() + ":" + value.value().id()).toList());
        appendContributions(text, "Custom editors", registry.customEditors().stream().map(value -> value.extensionId() + ":" + value.value().id()).toList());
        appendContributions(text, "Remote workspace providers", registry.remoteWorkspaceProviders().stream().map(value -> value.extensionId() + ":" + value.value().id()).toList());
        return text.toString();
    }

    private static void appendContributions(StringBuilder text, String title, List<String> values) {
        if (values.isEmpty()) return;
        text.append('\n').append(title).append(": ").append(String.join(", ", values)).append('\n');
    }

    private Descriptor descriptor(Path jarPath) throws IOException {
        try (JarFile jar = new JarFile(jarPath.toFile())) {
            var entry = jar.getJarEntry(DESCRIPTOR_PATH);
            if (entry == null || entry.isDirectory()) throw new IOException("missing " + DESCRIPTOR_PATH);
            String contents;
            try (InputStream input = jar.getInputStream(entry)) {
                contents = new String(readCapped(input, 64 * 1024), StandardCharsets.UTF_8);
            }
            TomlParseResult parsed = Toml.parse(contents);
            List<TomlParseError> parseErrors = parsed.errors();
            if (!parseErrors.isEmpty()) throw new IOException("extension descriptor is invalid: " + parseErrors.getFirst().getMessage());
            for (String key : parsed.keySet()) {
                if (!"id".equals(key) && !"version".equals(key) && !"api_version".equals(key) && !"main_class".equals(key)) {
                    throw new IOException("extension descriptor contains unsupported key: " + key);
                }
            }
            String id = normalizeId(parsed.getString("id"));
            String version = parsed.getString("version");
            String mainClass = parsed.getString("main_class");
            Long apiVersion = parsed.getLong("api_version");
            if (id == null || version == null || !version.matches("[A-Za-z0-9][A-Za-z0-9._+-]*") || mainClass == null || !mainClass.matches("[A-Za-z_$][A-Za-z0-9_$.]*") || apiVersion == null || apiVersion != API_VERSION) {
                throw new IOException("extension descriptor requires valid id, version, api_version=" + API_VERSION + ", and main_class");
            }
            return new Descriptor(id, version, mainClass);
        }
    }

    private void loadIndex() {
        receipts.clear();
        if (!Files.isRegularFile(indexPath)) return;
        try {
            Map<String, Object> document = MiniJson.asObject(MiniJson.parse(Files.readString(indexPath, StandardCharsets.UTF_8)));
            if (document == null || !Integer.valueOf(1).equals(MiniJson.asInt(document.get("version")))) return;
            List<Object> entries = MiniJson.asArray(document.get("extensions"));
            if (entries == null) return;
            for (Object raw : entries) {
                Map<String, Object> value = MiniJson.asObject(raw);
                Receipt receipt = Receipt.parse(value);
                if (receipt != null && !receipts.containsKey(receipt.id())) receipts.put(receipt.id(), receipt);
            }
        } catch (Exception error) {
            errors.put("[index]", "could not read extension receipt index: " + concise(error));
        }
    }

    private void saveIndex() throws IOException {
        Files.createDirectories(extensionDirectory);
        List<Map<String, Object>> entries = orderedReceipts().stream().map(Receipt::toMap).toList();
        AtomicFileWriter.write(indexPath, MiniJson.stringify(Map.of("version", 1, "extensions", entries)).getBytes(StandardCharsets.UTF_8));
    }

    private List<Receipt> orderedReceipts() {
        return receipts.values().stream().sorted(Comparator.comparing(Receipt::id)).toList();
    }

    private static byte[] readSource(String source) throws IOException {
        if (isRemote(source)) {
            URLConnection raw = URI.create(source).toURL().openConnection();
            if (!(raw instanceof HttpURLConnection connection)) throw new IOException("HTTPS connection unavailable");
            connection.setInstanceFollowRedirects(false);
            connection.setConnectTimeout(5_000);
            connection.setReadTimeout(20_000);
            connection.setRequestProperty("User-Agent", "Shed-extension-installer/1");
            int status = connection.getResponseCode();
            if (status < 200 || status >= 300) throw new IOException("HTTPS source returned " + status);
            try (InputStream input = connection.getInputStream()) { return readCapped(input, MAX_JAR_BYTES); }
        }
        Path path;
        try { path = Path.of(source).toAbsolutePath().normalize(); }
        catch (RuntimeException error) { throw new IOException("extension source path is invalid", error); }
        if (!Files.isRegularFile(path) || Files.isSymbolicLink(path)) throw new IOException("extension source must be a regular non-symbolic JAR");
        try (InputStream input = Files.newInputStream(path)) { return readCapped(input, MAX_JAR_BYTES); }
    }

    private static boolean isRemote(String source) {
        try { return source != null && "https".equalsIgnoreCase(URI.create(source).getScheme()); }
        catch (IllegalArgumentException error) { return false; }
    }

    private static byte[] readCapped(InputStream input, int limit) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int total = 0;
        for (int read; (read = input.read(buffer)) >= 0;) {
            if (total + read > limit) throw new IOException("extension source exceeds " + limit + " bytes");
            output.write(buffer, 0, read);
            total += read;
        }
        return output.toByteArray();
    }

    private static String sha256(byte[] content) throws IOException {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(content);
            StringBuilder value = new StringBuilder(digest.length * 2);
            for (byte part : digest) value.append(String.format(Locale.ROOT, "%02x", part));
            return value.toString();
        } catch (NoSuchAlgorithmException error) {
            throw new IOException("SHA-256 is unavailable", error);
        }
    }

    private static String normalizeChecksum(String value) {
        String normalized = value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
        if (normalized.startsWith("sha256:")) normalized = normalized.substring("sha256:".length());
        return normalized.matches("[0-9a-f]{64}") ? normalized : null;
    }

    private static String normalizeId(String value) {
        String normalized = value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
        return normalized.matches("[A-Za-z0-9][A-Za-z0-9._-]*") ? normalized : null;
    }

    private static String concise(Exception error) {
        String message = error.getMessage();
        return message == null || message.isBlank() ? error.getClass().getSimpleName() : message.replace('\n', ' ').replace('\r', ' ');
    }

    private static void deleteDirectory(Path directory) throws IOException {
        if (!Files.exists(directory)) return;
        try (var paths = Files.walk(directory)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
        }
    }

    @Override
    public synchronized void close() {
        deactivateAll();
    }

    private record Descriptor(String id, String version, String mainClass) { }
    private record Loaded(ShedExtension extension, ExtensionClassLoader classLoader) { }
    private record Receipt(String id, String version, String mainClass, String jarFile, String checksum, String source, boolean enabled, long installedAt) {
        Receipt {
            id = normalizeId(id);
            if (id == null || version == null || !version.matches("[A-Za-z0-9][A-Za-z0-9._+-]*")
                || mainClass == null || !mainClass.matches("[A-Za-z_$][A-Za-z0-9_$.]*")
                || jarFile == null || !jarFile.matches("[A-Za-z0-9][A-Za-z0-9._-]*\\.jar")
                || normalizeChecksum(checksum) == null || source == null) {
                throw new IllegalArgumentException("invalid extension receipt");
            }
            checksum = normalizeChecksum(checksum);
        }

        Receipt withEnabled(boolean value) { return new Receipt(id, version, mainClass, jarFile, checksum, source, value, installedAt); }

        Map<String, Object> toMap() {
            Map<String, Object> value = new LinkedHashMap<>();
            value.put("id", id);
            value.put("version", version);
            value.put("mainClass", mainClass);
            value.put("jarFile", jarFile);
            value.put("checksum", checksum);
            value.put("source", source);
            value.put("enabled", enabled);
            value.put("installedAt", installedAt);
            return value;
        }

        static Receipt parse(Map<String, Object> value) {
            if (value == null) return null;
            try {
                String id = MiniJson.asString(value.get("id"));
                String version = MiniJson.asString(value.get("version"));
                String mainClass = MiniJson.asString(value.get("mainClass"));
                String jarFile = MiniJson.asString(value.get("jarFile"));
                String checksum = MiniJson.asString(value.get("checksum"));
                String source = MiniJson.asString(value.get("source"));
                Object enabled = value.get("enabled");
                Object installedAt = value.get("installedAt");
                return enabled instanceof Boolean bool && installedAt instanceof Number number
                    ? new Receipt(id, version, mainClass, jarFile, checksum, source, bool, number.longValue()) : null;
            } catch (IllegalArgumentException error) {
                return null;
            }
        }
    }

    private final class Context implements ExtensionContext {
        private final String extensionId;
        private final Path storage;

        private Context(String extensionId, Path storage) {
            this.extensionId = extensionId;
            this.storage = storage;
        }

        @Override public String extensionId() { return extensionId; }
        @Override public Path storageDirectory() { return storage; }
        @Override public void registerCommand(String id, ExtensionCommand command) { registry.registerCommand(extensionId, id, command); }
        @Override public void registerLanguage(LanguageContribution contribution) { registry.registerLanguage(extensionId, contribution); }
        @Override public void registerDebugger(DebugAdapterContribution contribution) { registry.registerDebugger(extensionId, contribution); }
        @Override public void registerTestProvider(TestContribution contribution) { registry.registerTest(extensionId, contribution); }
        @Override public void registerScmProvider(ScmContribution contribution) { registry.registerScm(extensionId, contribution); }
        @Override public void registerTerminalProfile(TerminalProfile contribution) { registry.registerTerminalProfile(extensionId, contribution); }
        @Override public void registerToolView(ToolViewContribution contribution) { registry.registerToolView(extensionId, contribution); }
        @Override public void registerCustomEditor(CustomEditorContribution contribution) { registry.registerCustomEditor(extensionId, contribution); }
        @Override public void registerRemoteWorkspaceProvider(RemoteWorkspaceProvider contribution) { registry.registerRemoteWorkspace(extensionId, contribution); }
    }

    private static final class ExtensionClassLoader extends java.net.URLClassLoader {
        private ExtensionClassLoader(URL url, ClassLoader parent) {
            super(new URL[] {url}, parent);
        }
    }
}
