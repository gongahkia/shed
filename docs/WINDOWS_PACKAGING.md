# Windows packaging

`scripts/package-windows.ps1` creates an x64 Windows MSI from a clean Maven build. It requires Windows x64, JDK 21 with `jdeps`, `jlink`, and `jpackage`, plus the WiX tooling required by `jpackage` to produce an MSI. The script does not contact an update or catalog service.

```powershell
PS> .\scripts\package-windows.ps1
```

The output directory is `target/windows-package/dist/`:

- `Shed-<version>-windows-x64.msi` — installer containing the `Shed` app image.
- `Shed-<version>-windows-x64.msi.sha256` — SHA-256 verification input for the MSI.
- `Shed-<version>-windows-x64.inputs.sha256` — SHA-256 values for the shaded JAR and generated runtime-module list.
- `Shed-<version>-windows-x64.validation.txt` — fixed-order validation report covering version, x64 target, Java feature, runtime modules, artifact hash, MSI extraction, and signing state.

The script derives the Java module list with `jdeps`, links it with the active JDK 21 `jlink`, and passes the runtime image to `jpackage`. The installed app includes its runtime and does not require a separately installed JRE. Maven fixes archive-entry timestamps through `project.build.outputTimestamp`; `artifact:check-buildplan` verifies the Maven build plan before packaging. The report order is fixed; input hashes and runtime patch version depend on the checked-out source and active JDK. The MSI hash verifies that generated installer and does not claim byte-identical MSIs across different Windows packaging environments.

Verify a published artifact with:

```powershell
PS> $expected = (Get-Content .\Shed-<version>-windows-x64.msi.sha256).Split()[0]
PS> (Get-FileHash -Algorithm SHA256 .\Shed-<version>-windows-x64.msi).Hash.ToLower() -eq $expected
```

## Signing

The build has no code-signing certificate and reports its Authenticode status, normally `NotSigned`. It is suitable for local installation and CI artifact validation. A public SmartScreen-trusted release requires an organization-appropriate Authenticode certificate and signing policy; no certificate or signed installer is present here, so public signing readiness cannot be verified.
