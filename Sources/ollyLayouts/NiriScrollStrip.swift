import CoreGraphics
import ollyKit

public enum NiriColumnWidthPreset: String, CaseIterable, Codable, Equatable, Sendable {
    case oneThird
    case half
    case twoThirds
    case full

    public var proportion: CGFloat {
        switch self {
        case .oneThird:
            return 1 / 3
        case .half:
            return 0.5
        case .twoThirds:
            return 2 / 3
        case .full:
            return 1
        }
    }

    public func next(reverse: Bool = false) -> NiriColumnWidthPreset {
        let presets = Self.allCases
        guard let index = presets.firstIndex(of: self) else {
            return self
        }

        let offset = reverse ? -1 : 1
        let nextIndex = (index + offset + presets.count) % presets.count
        return presets[nextIndex]
    }

    public func width(in bounds: CGRect) -> CGFloat {
        floor(bounds.width * proportion)
    }
}

public struct NiriColumn: Codable, Equatable, Sendable {
    public let windowIDs: [WindowID]
    public let widthPreset: NiriColumnWidthPreset

    public init(windowIDs: [WindowID], widthPreset: NiriColumnWidthPreset = .half) {
        precondition(!windowIDs.isEmpty)
        precondition(Set(windowIDs).count == windowIDs.count)
        self.windowIDs = windowIDs
        self.widthPreset = widthPreset
    }

    public func withWidthPreset(_ widthPreset: NiriColumnWidthPreset) -> NiriColumn {
        NiriColumn(windowIDs: windowIDs, widthPreset: widthPreset)
    }

    fileprivate func pruned(to liveWindowIDs: Set<WindowID>) -> NiriColumn? {
        let remainingWindowIDs = windowIDs.filter { liveWindowIDs.contains($0) }
        guard !remainingWindowIDs.isEmpty else {
            return nil
        }
        return NiriColumn(windowIDs: remainingWindowIDs, widthPreset: widthPreset)
    }

    fileprivate func appendingWindow(_ windowID: WindowID) -> NiriColumn {
        NiriColumn(windowIDs: windowIDs + [windowID], widthPreset: widthPreset)
    }
}

public enum NiriWindowInsertion: String, Codable, Equatable, Sendable {
    case newColumn
    case stacked
}

public enum NiriScrollStripError: Error, Equatable, Sendable {
    case duplicateWindow(WindowID)
    case missingWindow(WindowID)
    case missingColumn(Int)
}

public struct NiriScrollStrip: Codable, Equatable, Sendable {
    public let columns: [NiriColumn]
    public let viewportOffset: CGFloat

    public init(columns: [NiriColumn] = [], viewportOffset: CGFloat = 0) {
        precondition(viewportOffset >= 0)
        let windowIDs = columns.flatMap(\.windowIDs)
        precondition(Set(windowIDs).count == windowIDs.count)
        self.columns = columns
        self.viewportOffset = viewportOffset
    }

    public func columnIndex(containing windowID: WindowID) -> Int? {
        columns.firstIndex { $0.windowIDs.contains(windowID) }
    }

    public func placingNextWindow(
        _ windowID: WindowID,
        after focus: WindowID?,
        insertion: NiriWindowInsertion = .newColumn,
        defaultColumnWidth: NiriColumnWidthPreset = .half
    ) throws -> NiriScrollStrip {
        guard columnIndex(containing: windowID) == nil else {
            throw NiriScrollStripError.duplicateWindow(windowID)
        }
        guard !columns.isEmpty else {
            return NiriScrollStrip(
                columns: [NiriColumn(windowIDs: [windowID], widthPreset: defaultColumnWidth)],
                viewportOffset: viewportOffset
            )
        }

        let targetIndex: Int
        if let focus {
            guard let index = columnIndex(containing: focus) else {
                throw NiriScrollStripError.missingWindow(focus)
            }
            targetIndex = index
        } else {
            targetIndex = columns.count - 1
        }

        var updatedColumns = columns
        switch insertion {
        case .newColumn:
            let column = NiriColumn(windowIDs: [windowID], widthPreset: defaultColumnWidth)
            updatedColumns.insert(column, at: targetIndex + 1)
        case .stacked:
            updatedColumns[targetIndex] = updatedColumns[targetIndex].appendingWindow(windowID)
        }
        return NiriScrollStrip(columns: updatedColumns, viewportOffset: viewportOffset)
    }

    public func switchingPresetColumnWidth(
        for windowID: WindowID,
        reverse: Bool = false
    ) throws -> NiriScrollStrip {
        guard let index = columnIndex(containing: windowID) else {
            throw NiriScrollStripError.missingWindow(windowID)
        }
        return try settingWidthPreset(columns[index].widthPreset.next(reverse: reverse), at: index)
    }

    public func settingWidthPreset(
        _ widthPreset: NiriColumnWidthPreset,
        at index: Int
    ) throws -> NiriScrollStrip {
        guard columns.indices.contains(index) else {
            throw NiriScrollStripError.missingColumn(index)
        }
        var updatedColumns = columns
        updatedColumns[index] = updatedColumns[index].withWidthPreset(widthPreset)
        return NiriScrollStrip(columns: updatedColumns, viewportOffset: viewportOffset)
    }

    public func followingFocus(_ focus: WindowID?, in bounds: CGRect) -> NiriScrollStrip {
        let offset = viewportOffset(focusing: focus, in: bounds)
        return NiriScrollStrip(columns: columns, viewportOffset: offset)
    }

    func placements(
        in bounds: CGRect,
        windowIDs: [WindowID],
        focus: WindowID?,
        defaultColumnWidth: NiriColumnWidthPreset
    ) -> [Placement] {
        let reconciledStrip = reconciled(with: windowIDs, defaultColumnWidth: defaultColumnWidth)
        let offset = reconciledStrip.viewportOffset(focusing: focus, in: bounds)
        let placements = reconciledStrip.placements(in: bounds, viewportOffset: offset)
        return placements.enumerated().map { index, placement in
            Placement(
                windowID: placement.windowID,
                frame: placement.frame,
                zOrder: index,
                hidden: placement.hidden
            )
        }
    }

    func viewportOffset(focusing focus: WindowID?, in bounds: CGRect) -> CGFloat {
        let ranges = columnRanges(in: bounds)
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

    private func reconciled(
        with windowIDs: [WindowID],
        defaultColumnWidth: NiriColumnWidthPreset
    ) -> NiriScrollStrip {
        let liveWindowIDs = Set(windowIDs)
        var updatedColumns = columns.compactMap { $0.pruned(to: liveWindowIDs) }
        let existingWindowIDs = Set(updatedColumns.flatMap(\.windowIDs))
        let missingWindowIDs = windowIDs.filter { !existingWindowIDs.contains($0) }
        updatedColumns += missingWindowIDs.map {
            NiriColumn(windowIDs: [$0], widthPreset: defaultColumnWidth)
        }
        return NiriScrollStrip(columns: updatedColumns, viewportOffset: viewportOffset)
    }

    private func placements(in bounds: CGRect, viewportOffset: CGFloat) -> [Placement] {
        let ranges = columnRanges(in: bounds)
        return zip(columns, ranges).flatMap { column, range in
            placements(for: column, range: range, bounds: bounds, viewportOffset: viewportOffset)
        }
    }

    private func placements(
        for column: NiriColumn,
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

    private func columnRanges(in bounds: CGRect) -> [(minX: CGFloat, maxX: CGFloat)] {
        var offset: CGFloat = 0
        return columns.map { column in
            let width = column.widthPreset.width(in: bounds)
            defer { offset += width }
            return (minX: offset, maxX: offset + width)
        }
    }

    private func clampedOffset(_ offset: CGFloat, totalWidth: CGFloat, viewportWidth: CGFloat) -> CGFloat {
        min(max(offset, 0), max(0, totalWidth - viewportWidth))
    }
}
