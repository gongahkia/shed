# macOS packaging

`scripts/package-macos.sh` creates an arm64 macOS DMG from a clean Maven build. The script requires macOS arm64 and JDK 21 with `jdeps`, `jlink`, and `jpackage`; it does not contact an update or catalog service.

```console
$ bash scripts/package-macos.sh
```

The output directory is `target/macos-package/dist/`:

- `Shed-<version>-macos-arm64.dmg` — mountable installer containing `Shed.app`.
- `Shed-<version>-macos-arm64.dmg.sha256` — SHA-256 verification input for the DMG.
- `Shed-<version>-macos-arm64.inputs.sha256` — SHA-256 values for the shaded JAR and generated runtime-module list.
- `Shed-<version>-macos-arm64.validation.txt` — fixed-order validation report covering bundle ID/version, arm64 target, Java feature, runtime modules, artifact hash, and installer contents.

The script derives the Java module list with `jdeps`, links that exact list with the active JDK 21 `jlink`, and gives the runtime image to `jpackage`. The app therefore carries its runtime and does not require a separately installed JRE. Maven fixes archive-entry timestamps through `project.build.outputTimestamp`; the script also runs `artifact:check-buildplan` and records both results in fixed report order. Input hashes and runtime patch version depend on the checked-out source and active JDK. The DMG hash is a verification value for that generated installer, not a claim of byte-identical DMGs across different macOS packaging environments.

Verify a published artifact with:

```console
$ cd target/macos-package/dist
$ shasum -a 256 -c Shed-<version>-macos-arm64.dmg.sha256
```

## Signing and notarization

`jpackage` creates an ad-hoc app signature without requiring Apple credentials; the report records this as `signing=adhoc` and `notarization=not-requested`. It is suitable for local installation and CI artifact validation. A public Gatekeeper-trusted release still needs a Developer ID Application signature, hardened-runtime/entitlements review, a Developer ID Installer signature where applicable, and Apple notarization. Those credentials and the exported signed artifact are not present in this repository, so notarization readiness cannot be verified here.
