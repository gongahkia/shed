import CoreGraphics
import Foundation
import ollyCore
import ollyKit

public struct RatioTileSizeConstraint: Codable, Equatable, Sendable {
    public let minSize: CGSize
    public let maxSize: CGSize?

    public init(minSize: CGSize = .zero, maxSize: CGSize? = nil) {
        precondition(minSize.width >= 0 && minSize.height >= 0)
        if let maxSize {
            precondition(maxSize.width >= minSize.width && maxSize.height >= minSize.height)
        }
        self.minSize = minSize
        self.maxSize = maxSize
    }
}

/// Shelf-based 2D bin-packing layout that honors configured AX min/max size constraints.
public struct RatioTileLayoutEngine: LayoutEngine {
    public struct Config: Codable, Equatable, Sendable {
        public let constraintsByWindowID: [WindowID: RatioTileSizeConstraint]

        public init(constraintsByWindowID: [WindowID: RatioTileSizeConstraint] = [:]) {
            self.constraintsByWindowID = constraintsByWindowID
        }
    }

    public static let engineID = LayoutEngineID(rawValue: "ratio-tile")

    public let id = RatioTileLayoutEngine.engineID
    public let displayName = "RatioTile"
    public let config: Config
    public let capabilities: LayoutEngineCapabilities = [.supportsResizing]

    public init(config: Config = Config()) {
        self.config = config
    }

    public func arrange(windows: [WindowSnapshot], in bounds: CGRect, focus: WindowID?) -> [Placement] {
        guard !windows.isEmpty else {
            return []
        }

        let items = packingItems(for: windows, in: bounds)
        guard let placements = pack(items, in: bounds) else {
            return GridLayoutEngine().arrange(windows: windows, in: bounds, focus: focus)
        }
        return placements
    }

    private func packingItems(for windows: [WindowSnapshot], in bounds: CGRect) -> [RatioTilePackingItem] {
        let fallbackSize = squareishFallbackSize(windowCount: windows.count, in: bounds)
        return windows.enumerated().map { index, window in
            let constraint = config.constraintsByWindowID[window.windowID] ?? RatioTileSizeConstraint()
            return RatioTilePackingItem(
                windowID: window.windowID,
                size: constrainedSize(
                    preferredSize: preferredSize(for: window, fallbackSize: fallbackSize),
                    constraint: constraint
                ),
                zOrder: index
            )
        }
    }

    private func squareishFallbackSize(windowCount: Int, in bounds: CGRect) -> CGSize {
        let columns = Int(ceil(sqrt(Double(windowCount))))
        let rows = Int(ceil(Double(windowCount) / Double(columns)))
        return CGSize(width: bounds.width / CGFloat(columns), height: bounds.height / CGFloat(rows))
    }

    private func preferredSize(for window: WindowSnapshot, fallbackSize: CGSize) -> CGSize {
        guard window.frame.width > 0, window.frame.height > 0 else {
            return fallbackSize
        }
        return window.frame.size
    }

    private func constrainedSize(preferredSize: CGSize, constraint: RatioTileSizeConstraint) -> CGSize {
        let maxSize = constraint.maxSize ?? preferredSize
        return CGSize(
            width: min(max(preferredSize.width, constraint.minSize.width), maxSize.width),
            height: min(max(preferredSize.height, constraint.minSize.height), maxSize.height)
        )
    }

    private func pack(_ items: [RatioTilePackingItem], in bounds: CGRect) -> [Placement]? {
        var cursor = bounds.origin
        var rowHeight: CGFloat = 0
        var placements: [Placement] = []
        placements.reserveCapacity(items.count)

        for item in items {
            guard item.size.width <= bounds.width, item.size.height <= bounds.height else {
                return nil
            }
            if cursor.x > bounds.minX, cursor.x + item.size.width > bounds.maxX {
                cursor.x = bounds.minX
                cursor.y += rowHeight
                rowHeight = 0
            }
            guard cursor.y + item.size.height <= bounds.maxY else {
                return nil
            }
            placements.append(Placement(
                windowID: item.windowID,
                frame: CGRect(origin: cursor, size: item.size),
                zOrder: item.zOrder
            ))
            cursor.x += item.size.width
            rowHeight = max(rowHeight, item.size.height)
        }

        return placements
    }
}

public struct RatioTileLayoutEngineFactory: LayoutEngineFactory {
    public let id = RatioTileLayoutEngine.engineID
    public let displayName = "RatioTile"

    public init() {}

    public func makeEngine(config: RatioTileLayoutEngine.Config) throws -> RatioTileLayoutEngine {
        RatioTileLayoutEngine(config: config)
    }
}

private struct RatioTilePackingItem {
    let windowID: WindowID
    let size: CGSize
    let zOrder: Int
}
