import CoreGraphics
import ollyCore
import ollyKit

public struct FloatingLayoutEngine: LayoutEngine {
    public struct Config: Codable, Equatable, Sendable {
        public init() {}
    }

    public static let engineID = LayoutEngineID(rawValue: "floating")

    public let id = FloatingLayoutEngine.engineID
    public let displayName = "Floating"
    public let config: Config
    public let capabilities: LayoutEngineCapabilities = [.supportsResizing, .supportsFloatingMix]

    public init(config: Config = Config()) {
        self.config = config
    }

    public func arrange(windows: [WindowSnapshot], in bounds: CGRect, focus: WindowID?) -> [Placement] {
        windows.enumerated().map { index, window in
            Placement(windowID: window.windowID, frame: window.frame, zOrder: index, hidden: false)
        }
    }
}

public struct FloatingLayoutEngineFactory: LayoutEngineFactory {
    public let id = FloatingLayoutEngine.engineID
    public let displayName = "Floating"

    public init() {}

    public func makeEngine(config: FloatingLayoutEngine.Config) throws -> FloatingLayoutEngine {
        FloatingLayoutEngine(config: config)
    }
}
