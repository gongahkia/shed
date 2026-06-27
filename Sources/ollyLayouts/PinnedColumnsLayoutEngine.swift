import CoreGraphics
import ollyCore
import ollyKit

public enum PinnedColumnEdge: String, Codable, Equatable, Sendable {
    case leading
    case trailing
}

public struct PinnedColumnsLayoutEngine<Base: LayoutEngine>: LayoutEngine {
    public struct Config: Codable, Equatable, Sendable {
        public let pinnedWindowIDs: [WindowID]
        public let edge: PinnedColumnEdge
        public let spacing: CGFloat

        public init(
            pinnedWindowIDs: [WindowID] = [],
            edge: PinnedColumnEdge = .leading,
            spacing: CGFloat = 0
        ) {
            precondition(spacing >= 0 && spacing.isFinite)
            self.pinnedWindowIDs = pinnedWindowIDs
            self.edge = edge
            self.spacing = spacing
        }
    }

    public let base: Base
    public let config: Config
    public let capabilities: LayoutEngineCapabilities

    public var id: LayoutEngineID {
        LayoutEngineID(rawValue: "pinned-columns.\(base.id.rawValue)")
    }

    public var displayName: String {
        "PinnedColumns(\(base.displayName))"
    }

    public init(base: Base, config: Config = Config()) {
        self.base = base
        self.config = config
        self.capabilities = base.capabilities
    }

    public func arrange(windows: [WindowSnapshot], in bounds: CGRect, focus: WindowID?) -> [Placement] {
        let placements = base.arrange(windows: windows, in: bounds, focus: focus)
        let pinnedWindowIDs = Set(config.pinnedWindowIDs)
        guard !pinnedWindowIDs.isEmpty else {
            return placements
        }

        let pinnedColumns = columns(from: placements).filter { column in
            column.placements.contains { pinnedWindowIDs.contains($0.placement.windowID) }
        }
        guard !pinnedColumns.isEmpty else {
            return placements
        }

        let targetXByColumnID = targetXs(for: pinnedColumns, in: bounds)
        let zOrderByWindowID = zOrders(for: pinnedColumns, after: placements)
        let targetXByWindowID = pinnedColumns.reduce(into: [WindowID: CGFloat]()) { result, column in
            guard let targetX = targetXByColumnID[column.id] else {
                return
            }
            for item in column.placements {
                result[item.placement.windowID] = targetX
            }
        }

        return placements.map { placement in
            guard let targetX = targetXByWindowID[placement.windowID] else {
                return placement
            }
            var frame = placement.frame
            frame.origin.x = targetX
            return Placement(
                windowID: placement.windowID,
                frame: frame,
                zOrder: zOrderByWindowID[placement.windowID] ?? placement.zOrder,
                hidden: placement.hidden
            )
        }
    }

    private func columns(from placements: [Placement]) -> [PinnedColumn] {
        var columns: [PinnedColumn] = []
        for (index, placement) in placements.enumerated() where !placement.hidden {
            let item = PinnedColumnItem(index: index, placement: placement)
            if let columnIndex = columns.firstIndex(where: { $0.matches(placement) }) {
                columns[columnIndex].placements.append(item)
            } else {
                columns.append(
                    PinnedColumn(
                        id: index,
                        minX: placement.frame.minX,
                        width: placement.frame.width,
                        placements: [item]
                    )
                )
            }
        }
        return columns.sorted {
            if !$0.minX.isAlmostEqual(to: $1.minX) {
                return $0.minX < $1.minX
            }
            return $0.id < $1.id
        }
    }

    private func targetXs(for columns: [PinnedColumn], in bounds: CGRect) -> [Int: CGFloat] {
        let totalWidth = columns.reduce(CGFloat(0)) { $0 + $1.width }
            + config.spacing * CGFloat(max(0, columns.count - 1))
        var currentX = config.edge == .leading ? bounds.minX : bounds.maxX - totalWidth
        var targetXByColumnID: [Int: CGFloat] = [:]

        for column in columns {
            targetXByColumnID[column.id] = currentX
            currentX += column.width + config.spacing
        }
        return targetXByColumnID
    }

    private func zOrders(for columns: [PinnedColumn], after placements: [Placement]) -> [WindowID: Int] {
        var zOrder = (placements.map(\.zOrder).max() ?? -1) + 1
        var zOrderByWindowID: [WindowID: Int] = [:]

        for column in columns {
            for item in column.placements.sorted(by: { $0.index < $1.index }) {
                zOrderByWindowID[item.placement.windowID] = zOrder
                zOrder += 1
            }
        }
        return zOrderByWindowID
    }
}

public struct PinnedColumnsLayoutEngineFactory<Base: LayoutEngine>: LayoutEngineFactory {
    public let base: Base

    public var id: LayoutEngineID {
        LayoutEngineID(rawValue: "pinned-columns.\(base.id.rawValue)")
    }

    public var displayName: String {
        "PinnedColumns(\(base.displayName))"
    }

    public init(base: Base) {
        self.base = base
    }

    public func makeEngine(config: PinnedColumnsLayoutEngine<Base>.Config) throws -> PinnedColumnsLayoutEngine<Base> {
        PinnedColumnsLayoutEngine(base: base, config: config)
    }
}

private struct PinnedColumn {
    let id: Int
    let minX: CGFloat
    let width: CGFloat
    var placements: [PinnedColumnItem]

    func matches(_ placement: Placement) -> Bool {
        minX.isAlmostEqual(to: placement.frame.minX) && width.isAlmostEqual(to: placement.frame.width)
    }
}

private struct PinnedColumnItem {
    let index: Int
    let placement: Placement
}

private extension CGFloat {
    func isAlmostEqual(to other: CGFloat) -> Bool {
        abs(self - other) <= 0.5
    }
}
