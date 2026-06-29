# Layout Plugin Authoring

`ollyLayouts` exposes layout engines as pure Swift values. Engines receive cached `WindowSnapshot` inputs and return deterministic `Placement` outputs. Engines must not call AX, perform I/O, mutate window state, or suspend; `EngineHost` owns diffing and window moves.

## Starter Template

Start from [`olly-plugin-template`](../olly-plugin-template/README.md). It contains a 49-line `HelloLayoutEngine`, a SwiftPM package, and a placement snapshot test you can run with `swift test --package-path olly-plugin-template` from this repo root.

For broader examples, see [`examples/layout-engine-showcase`](../examples/layout-engine-showcase/).

## Loading Model

v0.x plugins are SwiftPM packages consumed by the user's config sidecar. The
sidecar is recompiled with the plugin module on `ConfigLoader.moduleSearchPaths`;
there is no runtime `.dylib` loading in v0.x.

Dynamic layout plugins are deferred until v1.0, after the Swift protocol ABI has
stayed stable for at least two minor releases (`ref:N§4-D4`). The planned package
shape is a signed `.ollyplugin` bundle:

```text
Example.ollyplugin/
|-- manifest.json
|-- macos-arm64/Example.dylib
|-- macos-x86_64/Example.dylib
`-- signature
```

`manifest.json` fields:

- `id`: reverse-DNS engine package ID, e.g. `dev.olly.example.layouts`.
- `engines`: exported layout engine IDs.
- `apiVersion`: layout plugin ABI version.
- `minOllyVersion`: oldest compatible olly release.
- `swiftCompiler`: compiler/build metadata used to produce the binary.
- `signature`: Developer ID or future olly registry signature metadata.

Version negotiation is fail-fast: olly loads only packages whose `apiVersion`
matches a supported ABI and whose `minOllyVersion` is satisfied. Unknown engine
IDs, duplicate IDs, missing signatures, or unsupported ABI versions reject the
package before any engine is instantiated.

## Protocol

```swift
public protocol LayoutEngine {
    associatedtype Config

    var id: LayoutEngineID { get }
    var displayName: String { get }
    var config: Config { get }
    var capabilities: LayoutEngineCapabilities { get }

    func arrange(windows: [WindowSnapshot], in bounds: CGRect, focus: WindowID?) -> [Placement]
}
```

`WindowSnapshot` carries `windowID`, cached `frame`, optional `displayID`, `TagSet`, title, role, and subrole. `Placement` carries `windowID`, target `frame`, `zOrder`, and `hidden`.

## Capabilities

Declare capabilities so UI and keybind layers can avoid offering unsupported actions:

- `supportsManualSplits`
- `supportsResizing`
- `supportsFloatingMix`

Engines that do not override `capabilities` declare no optional capabilities.

## Factories

Factories bridge the DSL to concrete engine instances:

```swift
public protocol LayoutEngineFactory {
    associatedtype Engine: LayoutEngine

    var id: LayoutEngineID { get }
    var displayName: String { get }

    func makeEngine(config: Engine.Config) throws -> Engine
}
```

Register factories with `LayoutEngineRegistry`. The registry rejects duplicate IDs and returns type-erased `AnyLayoutEngine` instances by `(id, config)`.

## 20-Line Example

```swift
import CoreGraphics
import ollyCore
import ollyKit
import ollyLayouts
struct HalfEngine: LayoutEngine {
    struct Config {}
    let id = LayoutEngineID(rawValue: "example.half")
    let displayName = "Half"
    let config = Config()
    let capabilities: LayoutEngineCapabilities = [.supportsResizing]
    func arrange(windows: [WindowSnapshot], in bounds: CGRect, focus: WindowID?) -> [Placement] {
        guard !windows.isEmpty else { return [] }
        let width = bounds.width / CGFloat(windows.count)
        return windows.enumerated().map { index, window in
            let x = bounds.minX + CGFloat(index) * width
            let frame = CGRect(x: x, y: bounds.minY, width: width, height: bounds.height)
            return Placement(windowID: window.windowID, frame: frame, zOrder: index)
        }
    }
}
```

## Determinism Rules

Given identical `windows`, `bounds`, `focus`, and `config`, an engine must return identical placements in identical order. Use the input order unless the engine's documented behavior requires sorting. Keep `zOrder` stable and derive it from the engine's own order.
