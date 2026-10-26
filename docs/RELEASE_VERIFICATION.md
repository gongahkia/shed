# Release artifact verification

The three packaging jobs create platform installers, each installer checksum, input checksums, and an installer-content validation report. The CI `release-verify` job downloads the three installer artifacts and runs `scripts/verify-release-artifacts.sh` before exposing `shed-verified-release-bundle`, which contains installers, checksums and validation reports.

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

## GitHub Actions delivery

The workflow **Shed CI/CD on Push** in [ci.yml](../.github/workflows/ci.yml) runs on every push, pull request and manual dispatch. It tests/packages Java 21 on Linux, Windows and macOS; runs Swing integration and the model-free ACP completion acceptance fixture under Xvfb/Openbox; then builds the three native installers and verifies the combined bundle. It never downloads a model or requires AI provider credits.

The Actions run exposes a portable JAR, platform installer artifacts, test reports (including failures), desktop captures and the verified release bundle. Test reports/JARs are retained for 14 days and the combined bundle for 30 days. Obsolete runs on the same branch/PR are cancelled. Jobs have timeouts, the repository token is read-only, checkout does not persist credentials, and action versions are pinned to commit SHAs.

This delivers development installers as workflow artifacts. Public release publication, signing and notarization retain the separate policy above. The workflow's first GitHub-hosted execution occurs after these commits are pushed; local YAML validation cannot establish that all hosted OS/package jobs pass.
