# Linux packaging

`scripts/package-linux.sh` creates an x64 Debian package from a clean Maven build. It requires Linux x64, JDK 21 with `jdeps`, `jlink`, and `jpackage`, plus `dpkg-deb`, `fakeroot`, and `sha256sum`. The script does not contact an update or catalog service.

```console
$ bash scripts/package-linux.sh
```

The output directory is `target/linux-package/dist/`:

- `Shed-<version>-linux-x64.deb` — Debian installer containing the `Shed` app image.
- `Shed-<version>-linux-x64.deb.sha256` — SHA-256 verification input for the installer.
- `Shed-<version>-linux-x64.inputs.sha256` — SHA-256 values for the shaded JAR and generated runtime-module list.
- `Shed-<version>-linux-x64.validation.txt` — fixed-order validation report covering version, x64 target, Java feature, runtime modules, artifact hash, Debian metadata, and extracted installer contents.

The script derives the Java module list with `jdeps`, links it with the active JDK 21 `jlink`, and gives that runtime to `jpackage`. The installed app includes its runtime and does not require a separately installed JRE. Maven fixes archive-entry timestamps through `project.build.outputTimestamp`; `artifact:check-buildplan` verifies the Maven build plan before packaging. The report order is fixed; input hashes and runtime patch version depend on the checked-out source and active JDK. The DEB hash verifies that generated installer and does not claim byte-identical DEBs across different Linux packaging environments.

Verify a published artifact with:

```console
$ cd target/linux-package/dist
$ sha256sum -c Shed-<version>-linux-x64.deb.sha256
```

Install the verified package on a Debian-family system:

```console
$ sudo apt install ./Shed-<version>-linux-x64.deb
```

## Signing

The build creates an unsigned Debian package and reports `signing=not-applicable`; Debian repository or detached-package signing is not configured. It is suitable for local installation and CI artifact validation. A public signed release requires a signing key, protected signing policy, and signed-distribution process; those are not present in this repository, so public signing readiness cannot be verified here.
