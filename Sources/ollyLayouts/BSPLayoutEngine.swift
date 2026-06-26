import CoreGraphics
import ollyCore
import ollyKit

public struct BSPLayoutEngine: LayoutEngine {
    public struct Config: Codable, Equatable, Sendable {
        public let tree: BSPLayoutTree

        public init(tree: BSPLayoutTree = BSPLayoutTree()) {
            self.tree = tree
        }
    }

    public static let engineID = LayoutEngineID(rawValue: "bsp")

    public let id = BSPLayoutEngine.engineID
    public let displayName = "BSP"
    public let config: Config
    public let capabilities: LayoutEngineCapabilities = [.supportsResizing]

    public init(config: Config = Config()) {
        self.config = config
    }

    public func arrange(windows: [WindowSnapshot], in bounds: CGRect, focus: WindowID?) -> [Placement] {
        config.tree.placements(in: bounds, windowIDs: windows.map(\.windowID))
    }
}

public struct BSPLayoutEngineFactory: LayoutEngineFactory {
    public let id = BSPLayoutEngine.engineID
    public let displayName = "BSP"

    public init() {}

    public func makeEngine(config: BSPLayoutEngine.Config) throws -> BSPLayoutEngine {
        BSPLayoutEngine(config: config)
    }
}
