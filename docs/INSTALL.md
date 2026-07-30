# Install and troubleshoot Shed

This document covers the artifacts produced by the current release workflow. It does not claim support for unbuilt formats or architectures.

| Target | Artifact | Runtime policy | Verification |
| :--- | :--- | :--- | :--- |
| macOS arm64 | `Shed-<version>-macos-arm64.dmg` | Bundled Java 21 runtime | `shasum -a 256 -c Shed-<version>-macos-arm64.dmg.sha256` |
| Windows x64 | `Shed-<version>-windows-x64.msi` | Bundled Java 21 runtime | `Get-FileHash` against the supplied `.msi.sha256` value |
| Debian-family Linux x64 | `Shed-<version>-linux-x64.deb` | Bundled Java 21 runtime | `sha256sum -c Shed-<version>-linux-x64.deb.sha256` |
| Java archive | `shed-<version>.jar` | Install a Java 21 runtime separately | Inspect the release source and CI artifact provenance |

The installer workflow validates each package's bundled runtime and main JAR before upload. Its current platform boundary is limited to the three installer rows above. RPM, Linux arm64, macOS x64, and Windows arm64 installers are not produced.

## Verify before installation

Get the installer and its checksum file from the same identified release. A checksum detects a changed installer only if its expected value is obtained independently and has not been changed with it. Current development artifacts are not publicly signed; see [release verification](RELEASE_VERIFICATION.md) before treating an artifact as a public release.

```console
$ shasum -a 256 -c Shed-<version>-macos-arm64.dmg.sha256
$ sha256sum -c Shed-<version>-linux-x64.deb.sha256
```

```powershell
PS> $expected = (Get-Content .\Shed-<version>-windows-x64.msi.sha256).Split()[0]
PS> (Get-FileHash -Algorithm SHA256 .\Shed-<version>-windows-x64.msi).Hash.ToLower() -eq $expected
```

The CI aggregate `SHA256SUMS` covers the three native installers in fixed platform order:

```console
$ sha256sum -c SHA256SUMS
```

## macOS arm64

After the checksum passes, open the DMG and copy `Shed.app` to Applications. The DMG includes a Java 21 runtime; do not install a separate JRE for this installer. Current development builds are ad-hoc signed and not notarized, so macOS may show trust warnings. Do not bypass a warning without independently confirming the artifact origin and checksum.

For local packaging, use a macOS arm64 host with JDK 21:

```console
$ bash scripts/package-macos.sh
```

See [macOS packaging](MACOS_PACKAGING.md) for exact local output and public signing/notarization gaps.

## Windows x64

After the checksum comparison returns `True`, run the MSI from Explorer or:

```powershell
PS> Start-Process msiexec.exe -ArgumentList @('/i', '.\Shed-<version>-windows-x64.msi') -Wait
```

The MSI includes Java 21. Current development builds are `NotSigned`; SmartScreen or other Windows trust warnings are expected. Stop and verify provenance rather than treating an unsigned artifact as publisher-authenticated.

For local packaging, use Windows x64 with JDK 21 and the WiX tooling required by `jpackage`:

```powershell
PS> .\scripts\package-windows.ps1
```

See [Windows packaging](WINDOWS_PACKAGING.md) for the exact local outputs and Authenticode boundary.

## Debian-family Linux x64

After the checksum passes, install the DEB with the system package manager:

```console
$ sudo apt install ./Shed-<version>-linux-x64.deb
```

The DEB includes Java 21. It is not an RPM and has no configured Debian repository or detached-package signature. Use the published checksum and approved source for provenance.

For local packaging, use Linux x64 with JDK 21, `dpkg-deb`, and `fakeroot`:

```console
$ bash scripts/package-linux.sh
```

See [Linux packaging](LINUX_PACKAGING.md) for the exact local outputs and signing boundary.

## Java archive and source build

The JAR needs a Java 21 runtime. Build from source with JDK 21:

```console
$ mvn -B -q -DskipTests package
$ java -jar target/shed-2.0.0.jar [file]
```

The native installers are the supported way to avoid a separate runtime. The source build requires the Maven/JDK toolchain and is not a replacement for public package signing.

## Updates, privacy, diagnostics, and recovery

Update metadata checks are disabled by default. `:update status` is local-only; `:update consent` must be confirmed before a configured signed metadata check can run; `:update disable` revokes consent. Shed never downloads, installs, or replaces itself, so a failed check leaves the running application unchanged. See [Update Checks](UPDATES.md) and the [network boundary](NETWORK_PRIVACY.md).

For unexpected application or background-job failures, run `:perf diagnostics` and inspect the local-only `~/.shed/shed-diagnostics.jsonl` file. Configuration parse or validation errors retain a safe/last-known-good state and are shown with `:config status`; correct the file and run `:reload`. See [local diagnostics](DIAGNOSTICS.md) and [configuration](CONFIG.md).

## Troubleshooting

| Symptom | Action |
| :--- | :--- |
| Checksum fails | Stop. Delete the downloaded installer, obtain it again from an approved source, and compare against an independently obtained checksum. |
| macOS or Windows shows an unsigned/untrusted warning | Current development artifacts have no public trust chain. Verify source and checksum; do not treat the warning as resolved by bypassing it. |
| Linux package manager rejects the DEB | Confirm an x64 Debian-family system and run `sudo apt install ./<file>.deb`; RPM distributions are not a produced target. |
| JAR does not start | Confirm `java -version` reports Java 21, then run `java -jar shed-<version>.jar` from a terminal to retain the error output. |
| Local package command reports a missing tool | Use the platform prerequisites stated above; the packager intentionally fails before producing an incomplete installer. |
| `:update check` says no request sent | Consent, HTTPS endpoint, Base64 Ed25519 key, packaged version, and supported platform must all be available; see [Update Checks](UPDATES.md). |
| Application/UI job failed | Run `:perf diagnostics`, preserve the local log, and use the referenced remediation document. |
