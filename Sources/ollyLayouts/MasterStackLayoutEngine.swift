// swiftlint:disable inclusive_language
import CoreGraphics
import ollyCore
import ollyKit

public struct MasterStackLayoutEngine: LayoutEngine {
    public struct Config: Codable, Equatable, Sendable {
        public let masterRatio: CGFloat
        public let masterCount: Int

        public init(masterRatio: CGFloat = 0.55, masterCount: Int = 1) {
            precondition(masterRatio > 0 && masterRatio < 1)
            precondition(masterCount > 0)
            self.masterRatio = masterRatio
            self.masterCount = masterCount
        }
    }

    public static let engineID = LayoutEngineID(rawValue: "master-stack")

    public let id = MasterStackLayoutEngine.engineID
    public let displayName = "MasterStack"
    public let config: Config
    public let capabilities: LayoutEngineCapabilities = [.supportsResizing]

    public init(config: Config = Config()) {
        self.config = config
    }

    public func arrange(windows: [WindowSnapshot], in bounds: CGRect, focus: WindowID?) -> [Placement] {
        guard !windows.isEmpty else {
            return []
        }

        let masterCount = min(config.masterCount, windows.count)
        let masters = Array(windows.prefix(masterCount))
        let slaves = Array(windows.dropFirst(masterCount))
        let masterFrame = slaves.isEmpty ? bounds : CGRect(
            x: bounds.minX,
            y: bounds.minY,
            width: floor(bounds.width * config.masterRatio),
            height: bounds.height
        )
        let slaveFrame = CGRect(
            x: masterFrame.maxX,
            y: bounds.minY,
            width: bounds.maxX - masterFrame.maxX,
            height: bounds.height
        )

        return placements(for: masters, in: masterFrame, startingZOrder: 0) +
            placements(for: slaves, in: slaveFrame, startingZOrder: masters.count)
    }

    public func swapMasterOrder(windows: [WindowSnapshot], focus: WindowID?) -> [WindowID] {
        var order = windows.map(\.windowID)
        guard !order.isEmpty else {
            return []
        }

        if let focus, let index = order.firstIndex(of: focus) {
            order.remove(at: index)
            order.insert(focus, at: 0)
            return order
        }

        order.append(order.removeFirst())
        return order
    }

    private func placements(
        for windows: [WindowSnapshot],
        in bounds: CGRect,
        startingZOrder: Int
    ) -> [Placement] {
        guard !windows.isEmpty else {
            return []
        }

        let height = bounds.height / CGFloat(windows.count)
        return windows.enumerated().map { index, window in
            Placement(
                windowID: window.windowID,
                frame: CGRect(
                    x: bounds.minX,
                    y: bounds.minY + CGFloat(index) * height,
                    width: bounds.width,
                    height: height
                ),
                zOrder: startingZOrder + index,
                hidden: false
            )
        }
    }
}

public struct MasterStackLayoutEngineFactory: LayoutEngineFactory {
    public let id = MasterStackLayoutEngine.engineID
    public let displayName = "MasterStack"

    public init() {}

    public func makeEngine(config: MasterStackLayoutEngine.Config) throws -> MasterStackLayoutEngine {
        MasterStackLayoutEngine(config: config)
    }
}
// swiftlint:enable inclusive_language
