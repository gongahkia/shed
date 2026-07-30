package shed;

import java.net.URI;
import java.net.URISyntaxException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

final class UpdateMetadataVerifier {
    static final int MAX_METADATA_BYTES = 65536;
    private static final Pattern VERSION = Pattern.compile("(?:0|[1-9]\\d{0,8})\\.(?:0|[1-9]\\d{0,8})\\.(?:0|[1-9]\\d{0,8})");
    private static final Pattern SHA256 = Pattern.compile("[0-9a-f]{64}");
    private static final Set<String> REQUIRED_KEYS = Set.of(
        "schema", "version", "release_url",
        "macos_arm64_url", "macos_arm64_sha256",
        "windows_x64_url", "windows_x64_sha256",
        "linux_x64_url", "linux_x64_sha256"
    );

    record Asset(String platform, URI url, String sha256) { }

    record Metadata(String version, URI releaseUrl, Map<String, Asset> assets) {
        Asset asset(String platform) {
            return assets.get(platform);
        }
    }

    private UpdateMetadataVerifier() { }

    static Metadata verify(byte[] payload, String signature, String publicKey) {
        if (payload == null || payload.length == 0 || payload.length > MAX_METADATA_BYTES) {
            throw new IllegalArgumentException("metadata size is invalid");
        }
        verifySignature(payload, signature, publicKey);
        return parsePayload(payload);
    }

    static URI endpoint(String value) {
        return httpsUri(value, "metadata endpoint");
    }

    static void validatePublicKey(String value) {
        publicKey(value);
    }

    static boolean isNewer(String candidate, String installed) {
        int[] candidateParts = version(candidate, "metadata version");
        int[] installedParts = version(installed, "installed version");
        for (int index = 0; index < candidateParts.length; index++) {
            if (candidateParts[index] != installedParts[index]) return candidateParts[index] > installedParts[index];
        }
        return false;
    }

    static String currentPlatform(String osName, String osArchitecture) {
        String name = osName == null ? "" : osName.toLowerCase(Locale.ROOT);
        String architecture = osArchitecture == null ? "" : osArchitecture.toLowerCase(Locale.ROOT);
        if (name.contains("mac") && (architecture.equals("aarch64") || architecture.equals("arm64"))) return "macos-arm64";
        if (name.contains("win") && (architecture.equals("amd64") || architecture.equals("x86_64"))) return "windows-x64";
        if (name.contains("linux") && (architecture.equals("amd64") || architecture.equals("x86_64"))) return "linux-x64";
        return "";
    }

    private static void verifySignature(byte[] payload, String signatureValue, String publicKeyValue) {
        try {
            String signatureText = requiredValue(signatureValue, "metadata signature");
            byte[] signature = Base64.getDecoder().decode(signatureText);
            Signature verifier = Signature.getInstance("Ed25519");
            verifier.initVerify(publicKey(publicKeyValue));
            verifier.update(payload);
            if (!verifier.verify(signature)) throw new IllegalArgumentException("metadata signature verification failed");
        } catch (IllegalArgumentException error) {
            throw error;
        } catch (Exception error) {
            throw new IllegalArgumentException("metadata signature is invalid", error);
        }
    }

    private static PublicKey publicKey(String value) {
        try {
            String keyText = requiredValue(value, "metadata public key");
            byte[] key = Base64.getDecoder().decode(keyText);
            return KeyFactory.getInstance("Ed25519").generatePublic(new X509EncodedKeySpec(key));
        } catch (IllegalArgumentException error) {
            throw error;
        } catch (Exception error) {
            throw new IllegalArgumentException("metadata public key is invalid", error);
        }
    }

    private static Metadata parsePayload(byte[] payload) {
        String text;
        try {
            text = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(payload)).toString();
        } catch (CharacterCodingException error) {
            throw new IllegalArgumentException("metadata is not valid UTF-8", error);
        }
        Map<String, String> values = new LinkedHashMap<>();
        String[] lines = text.split("\\n", -1);
        int limit = lines.length;
        if (limit > 0 && lines[limit - 1].isEmpty()) limit--;
        for (int index = 0; index < limit; index++) {
            String line = lines[index];
            if (line.isEmpty() || line.indexOf('\r') >= 0) throw new IllegalArgumentException("metadata line " + (index + 1) + " is invalid");
            int separator = line.indexOf('=');
            if (separator <= 0 || separator == line.length() - 1) throw new IllegalArgumentException("metadata line " + (index + 1) + " is invalid");
            String key = line.substring(0, separator);
            String value = line.substring(separator + 1);
            if (!key.matches("[a-z0-9_]+") || !value.equals(value.trim()) || values.putIfAbsent(key, value) != null) {
                throw new IllegalArgumentException("metadata line " + (index + 1) + " is invalid");
            }
        }
        if (!values.keySet().equals(REQUIRED_KEYS)) throw new IllegalArgumentException("metadata keys are invalid");
        if (!"1".equals(values.get("schema"))) throw new IllegalArgumentException("metadata schema is unsupported");
        String version = values.get("version");
        version(version, "metadata version");
        Map<String, Asset> assets = new LinkedHashMap<>();
        addAsset(assets, values, "macos-arm64", "macos_arm64");
        addAsset(assets, values, "windows-x64", "windows_x64");
        addAsset(assets, values, "linux-x64", "linux_x64");
        return new Metadata(version, httpsUri(values.get("release_url"), "release URL"), Map.copyOf(assets));
    }

    private static void addAsset(Map<String, Asset> assets, Map<String, String> values, String platform, String key) {
        String checksum = values.get(key + "_sha256");
        if (!SHA256.matcher(checksum).matches()) throw new IllegalArgumentException(platform + " checksum is invalid");
        assets.put(platform, new Asset(platform, httpsUri(values.get(key + "_url"), platform + " asset URL"), checksum));
    }

    private static URI httpsUri(String value, String label) {
        try {
            URI uri = new URI(requiredValue(value, label));
            if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null || uri.getHost().isBlank()
                || uri.getUserInfo() != null || uri.getFragment() != null) {
                throw new IllegalArgumentException(label + " must be an HTTPS URI without user info or fragment");
            }
            return uri;
        } catch (URISyntaxException error) {
            throw new IllegalArgumentException(label + " is invalid", error);
        }
    }

    private static String requiredValue(String value, String label) {
        if (value == null || value.isBlank() || !value.equals(value.trim())) throw new IllegalArgumentException(label + " is required");
        return value;
    }

    private static int[] version(String value, String label) {
        if (value == null || !VERSION.matcher(value).matches()) throw new IllegalArgumentException(label + " must use major.minor.patch");
        String[] parts = value.split("\\.");
        return new int[] { Integer.parseInt(parts[0]), Integer.parseInt(parts[1]), Integer.parseInt(parts[2]) };
    }
}
