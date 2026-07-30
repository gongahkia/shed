# Update checks

Shed starts with update checks disabled. `:update consent` is the only command that enables automatic checks; it displays the network and installer boundary before writing both consent settings. `:update disable` clears both settings and cancels the active check when one is tracked. `:update status` is local-only and reports consent, configuration presence, last trusted metadata, and the latest error or result.

Automatic checks run once on launch only when both `updates.enabled` and `updates.consent.granted` are true. `:update check` uses the same gate. Before a request, Shed requires an HTTPS metadata URL, a Base64 Ed25519 SubjectPublicKeyInfo public key, a supported packaged platform, and an installed `major.minor.patch` version. Misconfiguration sends no request.

The metadata request uses HTTPS, an explicit timeout, no redirects, a 64 KiB body limit, and no telemetry headers. The response must have a `X-Shed-Update-Signature` Base64 Ed25519 signature over the exact UTF-8 metadata body. The body is a canonical `key=value` document with these exact keys:

```text
schema=1
version=2.0.1
release_url=https://releases.example.invalid/Shed-2.0.1
macos_arm64_url=https://releases.example.invalid/Shed-2.0.1-macos-arm64.dmg
macos_arm64_sha256=<64 lowercase hex characters>
windows_x64_url=https://releases.example.invalid/Shed-2.0.1-windows-x64.msi
windows_x64_sha256=<64 lowercase hex characters>
linux_x64_url=https://releases.example.invalid/Shed-2.0.1-linux-x64.deb
linux_x64_sha256=<64 lowercase hex characters>
```

Unknown, duplicate, missing, non-HTTPS, malformed, oversized, unsigned, or signature-invalid metadata is rejected. A failed check keeps the running application and last trusted metadata unchanged. Shed never downloads, invokes an installer, replaces files, or restarts itself. `:update open` is an explicit browser handoff for the verified platform asset and prints its SHA-256; verify the downloaded installer before manual installation. `:update rollback` reports that no updater-managed installation exists because no application files are changed.

## Settings

| Key | Default | Purpose |
| :--- | :--- | :--- |
| `updates.enabled` | `false` | Requested automatic-check enablement; ineffective without consent |
| `updates.consent.granted` | `false` | Persisted consent receipt; clearing it disables checks |
| `updates.metadata.url` | empty | HTTPS endpoint for signed metadata |
| `updates.metadata.public.key` | empty | Base64 Ed25519 SubjectPublicKeyInfo public key |
| `updates.check.timeout.ms` | `5000` | Connection and request timeout; `1000..30000` |

Update configuration is global only: project `.shed.toml` cannot set its endpoint, key, timeout, or consent. The current repository has no published signed metadata endpoint or public signing key, so enabling consent without configuring both values records no request and reports the local error. Public update readiness therefore cannot be verified here.
