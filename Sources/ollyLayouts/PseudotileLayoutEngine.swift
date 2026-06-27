import CoreGraphics
import ollyCore
import ollyKit

/// Modifier engine inspired by Hyprland's `pseudo` window rule:
/// https://wiki.hypr.land/Configuring/Window-Rules/
public struct PseudotileLayoutEngine<Base: LayoutEngine>: LayoutEngine {
    public struct Config: Codable, Equatable, Sendable {
        public let preferredSizesByWindowID: [WindowID: CGSize]

        public init(preferredSizesByWindowID: [WindowID: CGSize] = [:]) {
            self.preferredSizesByWindowID = preferredSizesByWindowID
        }
    }

    public let base: Base
    public let config: Config
    public let capabilities: LayoutEngineCapabilities

    public var id: LayoutEngineID {
        LayoutEngineID(rawValue: "pseudotile.\(base.id.rawValue)")
    }

    public var displayName: String {
        "Pseudotile(\(base.displayName))"
    }

    public init(base: Base, config: Config = Config()) {
        self.base = base
        self.config = config
        self.capabilities = base.capabilities
    }

    public func arrange(windows: [WindowSnapshot], in bounds: CGRect, focus: WindowID?) -> [Placement] {
        let windowsByID = Dictionary(uniqueKeysWithValues: windows.map { ($0.windowID, $0) })
        return base.arrange(windows: windows, in: bounds, focus: focus).map { placement in
            guard !placement.hidden, let window = windowsByID[placement.windowID] else {
                return placement
            }
            return pseudotiledPlacement(placement, preferredSize: preferredSize(for: window))
        }
    }

    private func preferredSize(for window: WindowSnapshot) -> CGSize {
        config.preferredSizesByWindowID[window.windowID] ?? window.frame.size
    }

    private func pseudotiledPlacement(_ placement: Placement, preferredSize: CGSize) -> Placement {
        guard preferredSize.width > 0, preferredSize.height > 0 else {
            return placement
        }

        let slot = placement.frame
        let size = CGSize(
            width: min(preferredSize.width, slot.width),
            height: min(preferredSize.height, slot.height)
        )
        let origin = CGPoint(
            x: slot.midX - size.width / 2,
            y: slot.midY - size.height / 2
        )
        return Placement(
            windowID: placement.windowID,
            frame: CGRect(origin: origin, size: size),
            zOrder: placement.zOrder,
            hidden: placement.hidden
        )
    }
}

public struct PseudotileLayoutEngineFactory<Base: LayoutEngine>: LayoutEngineFactory {
    public let base: Base

    public var id: LayoutEngineID {
        LayoutEngineID(rawValue: "pseudotile.\(base.id.rawValue)")
    }

    public var displayName: String {
        "Pseudotile(\(base.displayName))"
    }

    public init(base: Base) {
        self.base = base
    }

    public func makeEngine(config: PseudotileLayoutEngine<Base>.Config) throws -> PseudotileLayoutEngine<Base> {
        PseudotileLayoutEngine(base: base, config: config)
    }
}
