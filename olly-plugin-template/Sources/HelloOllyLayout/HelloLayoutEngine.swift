import CoreGraphics
import ollyCore
import ollyKit
import ollyLayouts

public struct HelloLayoutEngine: LayoutEngine {
    public struct Config: Codable, Equatable, Sendable {
        public var inset: CGFloat

        public init(inset: CGFloat = 24) {
            self.inset = inset
        }
    }

    public static let engineID = LayoutEngineID(rawValue: "hello-layout")

    public let id = Self.engineID
    public let displayName = "Hello Layout"
    public let config: Config

    public init(config: Config = Config()) {
        self.config = config
    }

    public func arrange(windows: [WindowSnapshot], in bounds: CGRect, focus: WindowID?) -> [Placement] {
        guard !windows.isEmpty else {
            return []
        }

        let insetBounds = bounds.insetBy(dx: config.inset, dy: config.inset)
        let step = min(config.inset, 16)
        return windows.enumerated().map { index, window in
            let offset = CGFloat(index) * step
            let frame = insetBounds.offsetBy(dx: offset, dy: offset)
            return Placement(windowID: window.windowID, frame: frame, zOrder: index)
        }
    }
}

public struct HelloLayoutEngineFactory: LayoutEngineFactory {
    public let id = HelloLayoutEngine.engineID
    public let displayName = "Hello Layout"

    public init() {}

    public func makeEngine(config: HelloLayoutEngine.Config) throws -> HelloLayoutEngine {
        HelloLayoutEngine(config: config)
    }
}
