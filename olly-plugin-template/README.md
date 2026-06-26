# olly plugin template

Minimal SwiftPM package for a custom olly layout engine.

## Workflow

1. Edit `Sources/HelloOllyLayout/HelloLayoutEngine.swift`.
2. Run `swift test --package-path olly-plugin-template` from the olly repo root.
3. Replace `.package(path: "..")` in `Package.swift` with your olly checkout path or future olly package URL.
4. Build your config sidecar with the template module path included in `ConfigLoader.moduleSearchPaths`.

The template engine is pure: it reads `WindowSnapshot` input and returns `Placement` output only.
