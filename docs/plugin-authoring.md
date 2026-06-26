# Layout Plugin Authoring

`ollyLayouts` exposes layout engines as pure Swift values. Engines receive cached `WindowSnapshot` inputs and return deterministic `Placement` outputs. Engines must not call AX, perform I/O, mutate window state, or suspend; `EngineHost` owns diffing and window moves.

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
