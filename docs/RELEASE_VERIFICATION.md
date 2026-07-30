# Release artifact verification

The three packaging jobs create platform installers, each installer checksum, input checksums, and an installer-content validation report. The CI `release-verify` job downloads the three installers and runs `scripts/verify-release-artifacts.sh` before exposing its aggregate release-verification artifact.

The verifier requires exactly these versioned files:

- `Shed-<version>-macos-arm64.dmg` with `signing=adhoc` and `notarization=not-requested`.
- `Shed-<version>-windows-x64.msi` with `signing=NotSigned` and `notarization=not-applicable`.
- `Shed-<version>-linux-x64.deb` with `signing=not-applicable` and `notarization=not-applicable`.

For every installer, it fails on a missing artifact, checksum, or validation report; an invalid checksum record; an altered installer; unexpected Java/build-plan/content validation; or an unexpected signing-policy state. It then writes `SHA256SUMS` and `RELEASE_VERIFICATION.txt` in fixed platform order. Verify the aggregate file with:

```console
$ sha256sum -c SHA256SUMS
```

Run the verifier against a release-artifact directory with:

```console
$ bash scripts/verify-release-artifacts.sh --artifacts /path/to/artifacts --version <version>
```

## Development signing policy

The current installers are development/CI artifacts, not public trusted releases. macOS has an ad-hoc signature and is not notarized; Windows has no Authenticode signature; Linux has no Debian repository or detached-package signature. The per-installer SHA-256 files detect alteration only when the checksum is obtained independently and remains unchanged; they do not establish publisher identity. Public release signing therefore requires managed signing credentials, protected signing execution, and a separately approved publishing process. Those credentials and process are absent here, so public signing readiness cannot be verified.
