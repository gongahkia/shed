package shed;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Explicit, bounded registry reads. No extension activation, dependencies or install scripts. */
final class OpenVsxThemeService {
    record Extension(String publisher, String name, String version, String displayName, String description) {
        String id() { return publisher + "." + name; }
        @Override public String toString() { return displayName + " · " + id(); }
    }
    record Download(List<ImportedTheme> themes, String sha256) { }
    interface Fetcher { byte[] get(URI uri, int limit, AsyncJobService.JobToken token) throws IOException; }
    private static final URI API = URI.create("https://open-vsx.org/api/");
    private final Fetcher fetcher;

    OpenVsxThemeService() { this(OpenVsxThemeService::fetch); }
    OpenVsxThemeService(Fetcher fetcher) { this.fetcher = fetcher; }

    List<Extension> search(String query, AsyncJobService.JobToken token) throws IOException {
        if (query.length() > 120) throw new IOException("Search is limited to 120 characters");
        URI uri = URI.create(API + "-/search?category=Themes&size=30&query=" + URLEncoder.encode(query.strip(), StandardCharsets.UTF_8));
        Map<String, Object> root = object(fetcher.get(uri, 1024 * 1024, token));
        List<Object> entries = MiniJson.asArray(root.get("extensions"));
        if (entries == null || entries.size() > 30) throw new IOException("Invalid registry search response");
        List<Extension> result = new ArrayList<>();
        for (Object entry : entries) {
            Map<String, Object> data = MiniJson.asObject(entry);
            if (data == null || Boolean.TRUE.equals(data.get("deprecated"))) continue;
            String publisher = identifier(text(data, "namespace", 128));
            String name = identifier(text(data, "name", 128));
            String version = identifier(text(data, "version", 128));
            result.add(new Extension(publisher, name, version, text(data, "displayName", 180), text(data, "description", 500)));
        }
        return List.copyOf(result);
    }

    Download download(Extension extension, AsyncJobService.JobToken token) throws IOException {
        String prefix = identifier(extension.publisher()) + "/" + identifier(extension.name()) + "/" + identifier(extension.version());
        Map<String, Object> data = object(fetcher.get(API.resolve(prefix), 1024 * 1024, token));
        if (!extension.publisher().equals(data.get("namespace")) || !extension.name().equals(data.get("name"))
            || !extension.version().equals(data.get("version"))) throw new IOException("Registry package identity changed; search again");
        List<Object> categories = MiniJson.asArray(data.get("categories"));
        if (categories == null || !categories.contains("Themes")) throw new IOException("This package is not a theme package");
        Map<String, Object> files = MiniJson.asObject(data.get("files"));
        if (files == null) throw new IOException("Theme package download is unavailable");
        URI download = packageFile(files.get("download"), prefix);
        URI checksum = packageFile(files.get("sha256"), prefix);
        byte[] bytes = fetcher.get(download, 20 * 1024 * 1024, token);
        String actual = sha256(bytes);
        String expected = new String(fetcher.get(checksum, 1024, token), StandardCharsets.US_ASCII).strip().split("\\s+")[0];
        if (!expected.matches("[a-fA-F0-9]{64}") || !actual.equalsIgnoreCase(expected)) throw new IOException("Theme package checksum did not match");
        String license = text(data, "license", 180);
        if (license.isBlank()) throw new IOException("This package does not declare a license; import is unavailable");
        return new Download(VsCodeThemeImporter.translate(bytes, extension, license, text(data, "repository", 500)), actual);
    }

    private static URI packageFile(Object value, String prefix) throws IOException {
        try {
            URI uri = URI.create((String) value);
            validateUri(uri);
            if (!"open-vsx.org".equals(uri.getHost()) || uri.getRawQuery() != null
                || !uri.getRawPath().startsWith("/api/" + prefix + "/file/") || uri.getRawPath().contains("%")
                || !uri.normalize().equals(uri)) throw new IOException("Unexpected registry package location");
            return uri;
        } catch (IllegalArgumentException | NullPointerException | ClassCastException error) {
            throw new IOException("Invalid registry package location", error);
        }
    }

    static String identifier(String value) throws IOException {
        if (value == null || !value.matches("[A-Za-z0-9][A-Za-z0-9_.-]{0,127}")) throw new IOException("Invalid registry package identifier");
        return value;
    }

    static String text(Map<String, Object> data, String key, int maximum) throws IOException {
        Object value = data.get(key);
        if (value == null) return "";
        if (!(value instanceof String text) || text.length() > maximum) throw new IOException("Invalid registry " + key);
        // JLabel/JList may interpret HTML; metadata is always plain text.
        return text.replace('<', '‹').replace('>', '›').replaceAll("[\\p{Cc}&&[^\\n]]", " ").replace('\n', ' ');
    }

    static Map<String, Object> object(byte[] bytes) throws IOException {
        return Jsonc.parseObject(new String(bytes, StandardCharsets.UTF_8));
    }

    static String sha256(byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (NoSuchAlgorithmException error) { throw new IllegalStateException(error); }
    }

    static void validateUri(URI uri) throws IOException {
        if (!"https".equals(uri.getScheme()) || uri.getHost() == null || !Set.of("open-vsx.org", "openvsx.eclipsecontent.org").contains(uri.getHost())
            || uri.getUserInfo() != null || uri.getPort() != -1 || uri.getFragment() != null)
            throw new IOException("Theme downloads require an approved Open VSX HTTPS host");
    }

    private static byte[] fetch(URI initial, int limit, AsyncJobService.JobToken token) throws IOException {
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(25);
        URI uri = initial;
        for (int redirects = 0; redirects < 3; redirects++) {
            validateUri(uri);
            checkCancelled(token);
            HttpURLConnection connection = (HttpURLConnection) uri.toURL().openConnection();
            connection.setInstanceFollowRedirects(false);
            connection.setConnectTimeout(5000);
            connection.setReadTimeout(5000);
            connection.setRequestProperty("User-Agent", "Shed/2 ThemeGallery");
            connection.setRequestProperty("Accept", "application/json, application/octet-stream;q=0.9");
            if (token != null) token.onCancel(connection::disconnect);
            try {
                int status = connection.getResponseCode();
                if (status == 301 || status == 302 || status == 303 || status == 307 || status == 308) {
                    String location = connection.getHeaderField("Location");
                    if (location == null) throw new IOException("Theme download redirect is missing its destination");
                    uri = uri.resolve(location);
                    continue;
                }
                if (status == 429) throw new IOException("Open VSX is busy; wait and search again");
                if (status != 200) throw new IOException("Open VSX request failed (HTTP " + status + ")");
                if (connection.getContentLengthLong() > limit) throw new IOException("Theme download exceeds its size limit");
                try (InputStream input = connection.getInputStream(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
                    byte[] buffer = new byte[8192];
                    int count;
                    while ((count = input.read(buffer)) != -1) {
                        checkCancelled(token);
                        if (System.nanoTime() > deadline) throw new IOException("Theme download timed out");
                        if (count > limit - output.size()) throw new IOException("Theme download exceeds its size limit");
                        output.write(buffer, 0, count);
                    }
                    return output.toByteArray();
                }
            } finally { connection.disconnect(); }
        }
        throw new IOException("Too many theme download redirects");
    }

    static void checkCancelled(AsyncJobService.JobToken token) throws IOException {
        if (Thread.currentThread().isInterrupted() || token != null && token.isCancelled()) throw new IOException("Theme operation cancelled");
    }
}
