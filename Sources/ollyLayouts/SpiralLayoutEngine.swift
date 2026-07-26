import CoreGraphics
import ollyCore
import ollyKit

/// Recursive layout in the XMonad Spiral and Yabai fibonacci family.
public struct SpiralLayoutEngine: LayoutEngine {
    public struct Config: Codable, Equatable, Sendable {
        public static let goldenRatio: CGFloat = 0.618_033_988_749_894_8

        public let splitRatio: CGFloat

        public init(splitRatio: CGFloat = Self.goldenRatio) {
            precondition(splitRatio > 0 && splitRatio < 1)
            self.splitRatio = splitRatio
        }
    }

    public static let engineID = LayoutEngineID(rawValue: "spiral")

    public let id = SpiralLayoutEngine.engineID
    public let displayName = "Spiral"
    public let config: Config
    public let capabilities: LayoutEngineCapabilities = [.supportsResizing]

    public init(config: Config = Config()) {
        self.config = config
    }

    public func arrange(windows: [WindowSnapshot], in bounds: CGRect, focus: WindowID?) -> [Placement] {
        guard !windows.isEmpty else {
            return []
        }

        var remainder = bounds
        var horizontalFromMin = true
        var verticalFromMin = true
        var placements: [Placement] = []
        placements.reserveCapacity(windows.count)

        for (index, window) in windows.enumerated() {
            let frame: CGRect
            if index == windows.count - 1 {
                frame = remainder
            } else {
                let split = nextSplit(
                    in: remainder,
                    horizontalFromMin: &horizontalFromMin,
                    verticalFromMin: &verticalFromMin
                )
                frame = split.window
                remainder = split.remainder
            }
            placements.append(Placement(windowID: window.windowID, frame: frame, zOrder: index))
        }

        return placements
    }

    private func nextSplit(
        in bounds: CGRect,
        horizontalFromMin: inout Bool,
        verticalFromMin: inout Bool
    ) -> (window: CGRect, remainder: CGRect) {
        if bounds.width >= bounds.height {
            defer { horizontalFromMin.toggle() }
            return horizontalSplit(in: bounds, fromMin: horizontalFromMin)
        }

        defer { verticalFromMin.toggle() }
        return verticalSplit(in: bounds, fromMin: verticalFromMin)
    }

    private func horizontalSplit(in bounds: CGRect, fromMin: Bool) -> (window: CGRect, remainder: CGRect) {
        let width = floor(bounds.width * config.splitRatio)
        let remainderWidth = bounds.width - width
        if fromMin {
            return (
                CGRect(x: bounds.minX, y: bounds.minY, width: width, height: bounds.height),
                CGRect(x: bounds.minX + width, y: bounds.minY, width: remainderWidth, height: bounds.height)
            )
        }

        return (
            CGRect(x: bounds.maxX - width, y: bounds.minY, width: width, height: bounds.height),
            CGRect(x: bounds.minX, y: bounds.minY, width: remainderWidth, height: bounds.height)
        )
    }

    private func verticalSplit(in bounds: CGRect, fromMin: Bool) -> (window: CGRect, remainder: CGRect) {
        let height = floor(bounds.height * config.splitRatio)
        let remainderHeight = bounds.height - height
        if fromMin {
            return (
                CGRect(x: bounds.minX, y: bounds.minY, width: bounds.width, height: height),
                CGRect(x: bounds.minX, y: bounds.minY + height, width: bounds.width, height: remainderHeight)
            )
        }

        return (
            CGRect(x: bounds.minX, y: bounds.maxY - height, width: bounds.width, height: height),
            CGRect(x: bounds.minX, y: bounds.minY, width: bounds.width, height: remainderHeight)
        )
    }
}

public struct SpiralLayoutEngineFactory: LayoutEngineFactory {
    public let id = SpiralLayoutEngine.engineID
    public let displayName = "Spiral"

    public init() {}

    public func makeEngine(config: SpiralLayoutEngine.Config) throws -> SpiralLayoutEngine {
        SpiralLayoutEngine(config: config)
    }
}
