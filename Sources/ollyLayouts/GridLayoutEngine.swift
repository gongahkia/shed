import CoreGraphics
import Foundation
import ollyCore
import ollyKit

public enum GridLayoutPolicy: Codable, Equatable, Sendable {
    case squareish
    case fixedRows(Int)
    case fixedCols(Int)

    var isValid: Bool {
        switch self {
        case .squareish:
            return true
        case let .fixedRows(count), let .fixedCols(count):
            return count > 0
        }
    }
}

/// Deterministic grid layout that auto-packs windows by layout order, then AX window ID.
public struct GridLayoutEngine: LayoutEngine {
    public struct Config: Codable, Equatable, Sendable {
        public let policy: GridLayoutPolicy

        public init(policy: GridLayoutPolicy = .squareish) {
            precondition(policy.isValid)
            self.policy = policy
        }
    }

    public static let engineID = LayoutEngineID(rawValue: "grid")

    public let id = GridLayoutEngine.engineID
    public let displayName = "Grid"
    public let config: Config
    public let capabilities: LayoutEngineCapabilities = [.supportsResizing]

    public init(config: Config = Config()) {
        self.config = config
    }

    public func arrange(windows: [WindowSnapshot], in bounds: CGRect, focus: WindowID?) -> [Placement] {
        guard !windows.isEmpty else {
            return []
        }

        let sortedWindows = windows.sorted(by: WindowSnapshot.precedes)
        let dimensions = dimensions(for: sortedWindows.count)
        return sortedWindows.enumerated().map { index, window in
            Placement(
                windowID: window.windowID,
                frame: frame(
                    at: index,
                    columns: dimensions.columns,
                    rows: dimensions.rows,
                    in: bounds
                ),
                zOrder: index
            )
        }
    }

    private func dimensions(for count: Int) -> (columns: Int, rows: Int) {
        switch config.policy {
        case .squareish:
            let columns = Int(ceil(sqrt(Double(count))))
            return (columns, Int(ceil(Double(count) / Double(columns))))
        case let .fixedRows(rows):
            return (Int(ceil(Double(count) / Double(rows))), rows)
        case let .fixedCols(columns):
            return (columns, Int(ceil(Double(count) / Double(columns))))
        }
    }

    private func frame(at index: Int, columns: Int, rows: Int, in bounds: CGRect) -> CGRect {
        let column = index % columns
        let row = index / columns
        let cellWidth = bounds.width / CGFloat(columns)
        let cellHeight = bounds.height / CGFloat(rows)
        let originX = bounds.minX + CGFloat(column) * cellWidth
        let originY = bounds.minY + CGFloat(row) * cellHeight
        return CGRect(
            x: originX,
            y: originY,
            width: column == columns - 1 ? bounds.maxX - originX : cellWidth,
            height: row == rows - 1 ? bounds.maxY - originY : cellHeight
        )
    }
}

public struct GridLayoutEngineFactory: LayoutEngineFactory {
    public let id = GridLayoutEngine.engineID
    public let displayName = "Grid"

    public init() {}

    public func makeEngine(config: GridLayoutEngine.Config) throws -> GridLayoutEngine {
        GridLayoutEngine(config: config)
    }
}
