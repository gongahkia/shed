import CoreGraphics
import ollyCore
import ollyKit

public struct PaperWMColumn: Codable, Equatable, Sendable {
    public let windowIDs: [WindowID]

    public init(windowIDs: [WindowID]) {
        precondition(!windowIDs.isEmpty)
        precondition(Set(windowIDs).count == windowIDs.count)
        self.windowIDs = windowIDs
    }

    fileprivate func pruned(to liveWindowIDs: Set<WindowID>) -> PaperWMColumn? {
        let remainingWindowIDs = windowIDs.filter { liveWindowIDs.contains($0) }
        guard !remainingWindowIDs.isEmpty else {
            return nil
        }
        return PaperWMColumn(windowIDs: remainingWindowIDs)
    }
}

public struct PaperWMColumnSizing: Codable, Equatable, Sendable {
    public let preferredWidthsByWindowID: [WindowID: CGFloat]
    public let defaultColumnWidthRatio: CGFloat
    public let minColumnWidth: CGFloat
    public let maxColumnWidth: CGFloat?
    public let columnSpacing: CGFloat

    public init(
        preferredWidthsByWindowID: [WindowID: CGFloat] = [:],
        defaultColumnWidthRatio: CGFloat = 0.5,
        minColumnWidth: CGFloat = 160,
        maxColumnWidth: CGFloat? = nil,
        columnSpacing: CGFloat = 0
    ) {
        precondition((0...1).contains(defaultColumnWidthRatio))
        precondition(minColumnWidth >= 0 && minColumnWidth.isFinite)
        if let maxColumnWidth {
            precondition(maxColumnWidth >= minColumnWidth && maxColumnWidth.isFinite)
        }
        precondition(columnSpacing >= 0 && columnSpacing.isFinite)
        self.preferredWidthsByWindowID = preferredWidthsByWindowID
        self.defaultColumnWidthRatio = defaultColumnWidthRatio
        self.minColumnWidth = minColumnWidth
        self.maxColumnWidth = maxColumnWidth
        self.columnSpacing = columnSpacing
    }

    func width(for column: PaperWMColumn, windowsByID: [WindowID: WindowSnapshot], in bounds: CGRect) -> CGFloat {
        let preferred = column.windowIDs.compactMap { windowID in
            preferredWidth(for: windowID, windowsByID: windowsByID)
        }.max() ?? fallbackWidth(in: bounds)
        let upperBound = min(maxColumnWidth ?? bounds.width, bounds.width)
        return floor(min(max(preferred, minColumnWidth), upperBound))
    }

    private func preferredWidth(for windowID: WindowID, windowsByID: [WindowID: WindowSnapshot]) -> CGFloat? {
        if let width = preferredWidthsByWindowID[windowID], width > 0, width.isFinite {
            return width
        }
        guard let snapshot = windowsByID[windowID], snapshot.frame.width > 0, snapshot.frame.width.isFinite else {
            return nil
        }
        return snapshot.frame.width
    }

    private func fallbackWidth(in bounds: CGRect) -> CGFloat {
        floor(max(bounds.width * defaultColumnWidthRatio, minColumnWidth))
    }
}

public struct PaperWMScrollStrip: Codable, Equatable, Sendable {
    public let columns: [PaperWMColumn]
    public let viewportOffset: CGFloat

    public init(columns: [PaperWMColumn] = [], viewportOffset: CGFloat = 0) {
        precondition(viewportOffset >= 0 && viewportOffset.isFinite)
        let windowIDs = columns.flatMap(\.windowIDs)
        precondition(Set(windowIDs).count == windowIDs.count)
        self.columns = columns
        self.viewportOffset = viewportOffset
    }

    public func columnIndex(containing windowID: WindowID) -> Int? {
        columns.firstIndex { $0.windowIDs.contains(windowID) }
    }

    func placements(
        in bounds: CGRect,
        windows: [WindowSnapshot],
        focus: WindowID?,
        sizing: PaperWMColumnSizing
    ) -> [Placement] {
        let reconciledStrip = reconciled(with: windows.map(\.windowID))
        let windowsByID = Dictionary(uniqueKeysWithValues: windows.map { ($0.windowID, $0) })
        let widths = reconciledStrip.columnWidths(windowsByID: windowsByID, in: bounds, sizing: sizing)
        let offset = reconciledStrip.viewportOffset(
            focusing: focus,
            in: bounds,
            widths: widths,
            spacing: sizing.columnSpacing
        )
        return reconciledStrip.placements(
            in: bounds,
            widths: widths,
            viewportOffset: offset,
            spacing: sizing.columnSpacing
        )
    }

    private func reconciled(with windowIDs: [WindowID]) -> PaperWMScrollStrip {
        let liveWindowIDs = Set(windowIDs)
        var updatedColumns = columns.compactMap { $0.pruned(to: liveWindowIDs) }
        let existingWindowIDs = Set(updatedColumns.flatMap(\.windowIDs))
        let missingWindowIDs = windowIDs.filter { !existingWindowIDs.contains($0) }
        updatedColumns += missingWindowIDs.map { PaperWMColumn(windowIDs: [$0]) }
        return PaperWMScrollStrip(columns: updatedColumns, viewportOffset: viewportOffset)
    }

    private func columnWidths(
        windowsByID: [WindowID: WindowSnapshot],
        in bounds: CGRect,
        sizing: PaperWMColumnSizing
    ) -> [CGFloat] {
        columns.map { sizing.width(for: $0, windowsByID: windowsByID, in: bounds) }
    }

    private func viewportOffset(
        focusing focus: WindowID?,
        in bounds: CGRect,
        widths: [CGFloat],
        spacing: CGFloat
    ) -> CGFloat {
        let ranges = columnRanges(widths: widths, spacing: spacing)
        let totalWidth = ranges.last.map { $0.maxX } ?? 0
        var offset = clampedOffset(viewportOffset, totalWidth: totalWidth, viewportWidth: bounds.width)

        guard let focus,
              let columnIndex = columnIndex(containing: focus),
              ranges.indices.contains(columnIndex) else {
            return offset
        }

        let range = ranges[columnIndex]
        if range.minX < offset {
            offset = range.minX
        } else if range.maxX > offset + bounds.width {
            offset = range.maxX - bounds.width
        }
        return clampedOffset(offset, totalWidth: totalWidth, viewportWidth: bounds.width)
    }

    private func placements(
        in bounds: CGRect,
        widths: [CGFloat],
        viewportOffset: CGFloat,
        spacing: CGFloat
    ) -> [Placement] {
        let ranges = columnRanges(widths: widths, spacing: spacing)
        return zip(columns, ranges).flatMap { column, range in
            placements(for: column, range: range, bounds: bounds, viewportOffset: viewportOffset)
        }
        .enumerated()
        .map { index, placement in
            Placement(windowID: placement.windowID, frame: placement.frame, zOrder: index)
        }
    }

    private func placements(
        for column: PaperWMColumn,
        range: (minX: CGFloat, maxX: CGFloat),
        bounds: CGRect,
        viewportOffset: CGFloat
    ) -> [Placement] {
        let height = bounds.height / CGFloat(column.windowIDs.count)
        return column.windowIDs.enumerated().map { row, windowID in
            Placement(
                windowID: windowID,
                frame: CGRect(
                    x: bounds.minX + range.minX - viewportOffset,
                    y: bounds.minY + CGFloat(row) * height,
                    width: range.maxX - range.minX,
                    height: height
                )
            )
        }
    }

    private func columnRanges(widths: [CGFloat], spacing: CGFloat) -> [(minX: CGFloat, maxX: CGFloat)] {
        var offset: CGFloat = 0
        return widths.map { width in
            defer {
                offset += width + spacing
            }
            return (minX: offset, maxX: offset + width)
        }
    }

    private func clampedOffset(_ offset: CGFloat, totalWidth: CGFloat, viewportWidth: CGFloat) -> CGFloat {
        min(max(offset, 0), max(0, totalWidth - viewportWidth))
    }
}

public struct PaperWMScrollLayoutEngine: LayoutEngine {
    public struct Config: Codable, Equatable, Sendable {
        public let strip: PaperWMScrollStrip
        public let sizing: PaperWMColumnSizing

        public init(
            strip: PaperWMScrollStrip = PaperWMScrollStrip(),
            sizing: PaperWMColumnSizing = PaperWMColumnSizing()
        ) {
            self.strip = strip
            self.sizing = sizing
        }
    }

    public static let engineID = LayoutEngineID(rawValue: "paperwm-scroll")

    public let id = PaperWMScrollLayoutEngine.engineID
    public let displayName = "PaperWMScroll"
    public let config: Config
    public let capabilities: LayoutEngineCapabilities = [.supportsResizing]

    public init(config: Config = Config()) {
        self.config = config
    }

    public func arrange(windows: [WindowSnapshot], in bounds: CGRect, focus: WindowID?) -> [Placement] {
        config.strip.placements(in: bounds, windows: windows, focus: focus, sizing: config.sizing)
    }
}

public struct PaperWMScrollLayoutEngineFactory: LayoutEngineFactory {
    public let id = PaperWMScrollLayoutEngine.engineID
    public let displayName = "PaperWMScroll"

    public init() {}

    public func makeEngine(config: PaperWMScrollLayoutEngine.Config) throws -> PaperWMScrollLayoutEngine {
        PaperWMScrollLayoutEngine(config: config)
    }
}
