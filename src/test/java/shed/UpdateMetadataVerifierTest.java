package shed;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.util.Base64;
import org.junit.jupiter.api.Test;

public class UpdateMetadataVerifierTest {
    @Test
    void acceptsSignedCanonicalMetadataAndSelectsPlatformAsset() throws Exception {
        KeyPair keyPair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        byte[] payload = metadata("2.0.1").getBytes(StandardCharsets.UTF_8);
        UpdateMetadataVerifier.Metadata result = UpdateMetadataVerifier.verify(payload, signature(payload, keyPair), publicKey(keyPair));

        assertEquals("2.0.1", result.version());
        assertEquals("https://releases.example.invalid/Shed-2.0.1-windows-x64.msi", result.asset("windows-x64").url().toString());
        assertEquals("windows-x64", UpdateMetadataVerifier.currentPlatform("Windows 11", "amd64"));
        assertEquals("macos-arm64", UpdateMetadataVerifier.currentPlatform("Mac OS X", "aarch64"));
        assertEquals("linux-x64", UpdateMetadataVerifier.currentPlatform("Linux", "x86_64"));
        assertTrue(UpdateMetadataVerifier.isNewer("2.0.1", "2.0.0"));
        assertFalse(UpdateMetadataVerifier.isNewer("2.0.0", "2.0.0"));
    }

    @Test
    void rejectsAlteredPayloadInvalidSignatureAndUnsafeAssetUrl() throws Exception {
        KeyPair keyPair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        byte[] payload = metadata("2.0.1").getBytes(StandardCharsets.UTF_8);
        String signature = signature(payload, keyPair);

        assertThrows(IllegalArgumentException.class, () -> UpdateMetadataVerifier.verify(
            metadata("2.0.2").getBytes(StandardCharsets.UTF_8), signature, publicKey(keyPair)));
        assertThrows(IllegalArgumentException.class, () -> UpdateMetadataVerifier.verify(payload, "bad", publicKey(keyPair)));

        byte[] unsafe = metadata("2.0.1").replace("https://releases.example.invalid/Shed-2.0.1-linux-x64.deb", "http://releases.example.invalid/Shed.deb")
            .getBytes(StandardCharsets.UTF_8);
        assertThrows(IllegalArgumentException.class, () -> UpdateMetadataVerifier.verify(unsafe, signature(unsafe, keyPair), publicKey(keyPair)));
    }

    @Test
    void rejectsMalformedVersionsAndUnrecognizedPlatforms() {
        assertThrows(IllegalArgumentException.class, () -> UpdateMetadataVerifier.isNewer("2.0", "2.0.0"));
        assertEquals("", UpdateMetadataVerifier.currentPlatform("Solaris", "sparc"));
        assertThrows(IllegalArgumentException.class, () -> UpdateMetadataVerifier.endpoint("http://updates.example.invalid/metadata"));
    }

    private static String signature(byte[] payload, KeyPair keyPair) throws Exception {
        Signature signer = Signature.getInstance("Ed25519");
        signer.initSign(keyPair.getPrivate());
        signer.update(payload);
        return Base64.getEncoder().encodeToString(signer.sign());
    }

    private static String publicKey(KeyPair keyPair) {
        return Base64.getEncoder().encodeToString(keyPair.getPublic().getEncoded());
    }

    private static String metadata(String version) {
        String checksum = "a".repeat(64);
        return "schema=1\n"
            + "version=" + version + "\n"
            + "release_url=https://releases.example.invalid/Shed-" + version + "\n"
            + "macos_arm64_url=https://releases.example.invalid/Shed-" + version + "-macos-arm64.dmg\n"
            + "macos_arm64_sha256=" + checksum + "\n"
            + "windows_x64_url=https://releases.example.invalid/Shed-" + version + "-windows-x64.msi\n"
            + "windows_x64_sha256=" + checksum + "\n"
            + "linux_x64_url=https://releases.example.invalid/Shed-" + version + "-linux-x64.deb\n"
            + "linux_x64_sha256=" + checksum + "\n";
    }
}
