package shed;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Builds immutable source links for the Git hosts with stable public URL shapes. */
final class GitPermalink {
    private GitPermalink() {
    }

    static String create(String remote, String revision, String relativePath, int line) {
        Repository repository = parseRemote(remote);
        if (repository == null) throw new IllegalArgumentException("origin is not a supported GitHub, GitLab, or Bitbucket remote");
        if (revision == null || !revision.matches("[0-9a-fA-F]{7,64}")) throw new IllegalArgumentException("HEAD revision is unavailable");
        if (relativePath == null || relativePath.isBlank() || relativePath.startsWith("/") || relativePath.contains("\\")) {
            throw new IllegalArgumentException("current file path is invalid");
        }
        List<String> path = new ArrayList<>();
        for (String part : relativePath.split("/", -1)) {
            if (part.isBlank() || ".".equals(part) || "..".equals(part)) throw new IllegalArgumentException("current file path is invalid");
            path.add(encode(part));
        }
        String encodedPath = String.join("/", path);
        int selectedLine = Math.max(1, line);
        return switch (repository.host()) {
            case "github.com" -> "https://" + repository.host() + "/" + repository.path() + "/blob/" + revision + "/" + encodedPath + "#L" + selectedLine;
            case "gitlab.com" -> "https://" + repository.host() + "/" + repository.path() + "/-/blob/" + revision + "/" + encodedPath + "#L" + selectedLine;
            case "bitbucket.org" -> "https://" + repository.host() + "/" + repository.path() + "/src/" + revision + "/" + encodedPath + "#lines-" + selectedLine;
            default -> throw new IllegalArgumentException("origin is not a supported GitHub, GitLab, or Bitbucket remote");
        };
    }

    private static Repository parseRemote(String remote) {
        String value = remote == null ? "" : remote.strip();
        if (value.isBlank() || value.indexOf('\0') >= 0 || value.indexOf('\n') >= 0 || value.indexOf('\r') >= 0) return null;
        String host = "";
        String path = "";
        java.util.regex.Matcher scp = java.util.regex.Pattern.compile("(?:[A-Za-z0-9._-]+@)?([A-Za-z0-9.-]+):(.+)").matcher(value);
        if (!value.contains("://") && scp.matches()) {
            host = scp.group(1);
            path = scp.group(2);
        } else {
            try {
                java.net.URI uri = java.net.URI.create(value);
                host = uri.getHost() == null ? "" : uri.getHost();
                path = uri.getPath() == null ? "" : uri.getPath();
            } catch (IllegalArgumentException error) {
                return null;
            }
        }
        host = host.toLowerCase(Locale.ROOT);
        path = path.replaceFirst("^/+", "").replaceFirst("/+$", "").replaceFirst("\\.git$", "");
        if (!("github.com".equals(host) || "gitlab.com".equals(host) || "bitbucket.org".equals(host))) return null;
        if (!path.matches("[A-Za-z0-9._~/-]+") || path.isBlank() || path.contains("//") || path.startsWith("/") || path.endsWith("/")) return null;
        return new Repository(host, path);
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private record Repository(String host, String path) { }
}
