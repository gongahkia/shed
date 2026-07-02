import CoreGraphics
import ollyCore
import ollyKit
import ollyLayouts

public struct DwmMonocleLayoutEngine: LayoutEngine {
    public struct Config: Equatable, Sendable {
        public let sortByLayoutOrder: Bool
        public let focusedOnTop: Bool

        public init(sortByLayoutOrder: Bool = true, focusedOnTop: Bool = true) {
            self.sortByLayoutOrder = sortByLayoutOrder
            self.focusedOnTop = focusedOnTop
        }
    }

    public let id = LayoutEngineID(rawValue: "dev.olly.showcase.dwm-monocle")
    public let displayName = "DWM Monocle"
    public let capabilities: LayoutEngineCapabilities = [.supportsResizing]
    public let config: Config

    public init(config: Config = Config()) {
        self.config = config
    }

    public func arrange(windows: [WindowSnapshot], in bounds: CGRect, focus: WindowID?) -> [Placement] {
        let ordered = orderedWindows(windows)
        guard let visibleID = visibleWindowID(windows: ordered, focus: focus) else { return [] }
        return ordered.enumerated().map { index, window in
            Placement(
                windowID: window.windowID,
                frame: bounds,
                zOrder: zOrder(index: index, count: ordered.count, isFocused: window.windowID == visibleID),
                hidden: false
            )
        }
    }

    public func nextFocus(windows: [WindowSnapshot], focus: WindowID?) -> WindowID? {
        cycleFocus(windows: windows, focus: focus, reverse: false)
    }

    public func previousFocus(windows: [WindowSnapshot], focus: WindowID?) -> WindowID? {
        cycleFocus(windows: windows, focus: focus, reverse: true)
    }

    private func orderedWindows(_ windows: [WindowSnapshot]) -> [WindowSnapshot] {
        config.sortByLayoutOrder ? windows.sorted(by: WindowSnapshot.precedes) : windows
    }

    private func visibleWindowID(windows: [WindowSnapshot], focus: WindowID?) -> WindowID? {
        if let focus, windows.contains(where: { $0.windowID == focus }) {
            return focus
        }
        return windows.first?.windowID
    }

    private func cycleFocus(windows: [WindowSnapshot], focus: WindowID?, reverse: Bool) -> WindowID? {
        let ids = orderedWindows(windows).map(\.windowID)
        guard !ids.isEmpty else { return nil }
        let currentIndex = focus.flatMap { ids.firstIndex(of: $0) } ?? (reverse ? 0 : ids.count - 1)
        let offset = reverse ? -1 : 1
        return ids[(currentIndex + offset + ids.count) % ids.count]
    }

    private func zOrder(index: Int, count: Int, isFocused: Bool) -> Int {
        guard config.focusedOnTop else { return index }
        return isFocused ? count - 1 : index
    }
}

public enum SpiralStartDirection: CaseIterable, Equatable, Sendable {
    case right
    case down
    case left
    case up
}

public struct DwindleSpiralLayoutEngine: LayoutEngine {
    public struct Config: Equatable, Sendable {
        public let ratio: CGFloat
        public let startDirection: SpiralStartDirection
        public let clockwise: Bool

        public init(
            ratio: CGFloat = 0.5,
            startDirection: SpiralStartDirection = .right,
            clockwise: Bool = true
        ) {
            self.ratio = max(0.2, min(0.8, ratio))
            self.startDirection = startDirection
            self.clockwise = clockwise
        }
    }

    public let id = LayoutEngineID(rawValue: "dev.olly.showcase.dwindle-spiral")
    public let displayName = "Dwindle Spiral"
    public let capabilities: LayoutEngineCapabilities = [.supportsResizing]
    public let config: Config

    public init(config: Config = Config()) {
        self.config = config
    }

    public func arrange(windows: [WindowSnapshot], in bounds: CGRect, focus: WindowID?) -> [Placement] {
        guard !windows.isEmpty else { return [] }
        var remainder = bounds
        var directionIndex = Self.directionIndex(config.startDirection)
        return windows.enumerated().map { index, window in
            if index == windows.count - 1 {
                return Placement(windowID: window.windowID, frame: remainder, zOrder: index)
            }

            let split = splitFrame(remainder, direction: Self.directions[directionIndex])
            remainder = split.remainder
            directionIndex = nextDirectionIndex(directionIndex)
            return Placement(windowID: window.windowID, frame: split.window, zOrder: index)
        }
    }

    private func splitFrame(_ frame: CGRect, direction: SpiralStartDirection) -> (window: CGRect, remainder: CGRect) {
        switch direction {
        case .right:
            let width = floor(frame.width * config.ratio)
            return (
                CGRect(x: frame.minX, y: frame.minY, width: width, height: frame.height),
                CGRect(x: frame.minX + width, y: frame.minY, width: frame.width - width, height: frame.height)
            )
        case .down:
            let height = floor(frame.height * config.ratio)
            return (
                CGRect(x: frame.minX, y: frame.minY, width: frame.width, height: height),
                CGRect(x: frame.minX, y: frame.minY + height, width: frame.width, height: frame.height - height)
            )
        case .left:
            let width = floor(frame.width * config.ratio)
            return (
                CGRect(x: frame.maxX - width, y: frame.minY, width: width, height: frame.height),
                CGRect(x: frame.minX, y: frame.minY, width: frame.width - width, height: frame.height)
            )
        case .up:
            let height = floor(frame.height * config.ratio)
            return (
                CGRect(x: frame.minX, y: frame.maxY - height, width: frame.width, height: height),
                CGRect(x: frame.minX, y: frame.minY, width: frame.width, height: frame.height - height)
            )
        }
    }

    private func nextDirectionIndex(_ index: Int) -> Int {
        let offset = config.clockwise ? 1 : -1
        return (index + offset + Self.directions.count) % Self.directions.count
    }

    private static let directions: [SpiralStartDirection] = [.right, .down, .left, .up]

    private static func directionIndex(_ direction: SpiralStartDirection) -> Int {
        directions.firstIndex(of: direction) ?? 0
    }
}

public struct FocusBandLayoutEngine: LayoutEngine {
    public struct Config: Equatable, Sendable {
        public let focusRatio: CGFloat

        public init(focusRatio: CGFloat = 0.62) {
            self.focusRatio = max(0.3, min(0.85, focusRatio))
        }
    }

    public let id = LayoutEngineID(rawValue: "dev.olly.showcase.focus-band")
    public let displayName = "Focus Band"
    public let capabilities: LayoutEngineCapabilities = [.supportsResizing]
    public let config: Config

    public init(config: Config = Config()) {
        self.config = config
    }

    public func arrange(windows: [WindowSnapshot], in bounds: CGRect, focus: WindowID?) -> [Placement] {
        guard !windows.isEmpty else { return [] }
        guard let focusIndex = windows.firstIndex(where: { $0.windowID == focus }) else {
            return equalColumns(windows: windows, in: bounds)
        }

        let focusWidth = bounds.width * config.focusRatio
        let sideWidth = max(0, (bounds.width - focusWidth) / 2)
        let focused = windows[focusIndex]
        let before = Array(windows[..<focusIndex])
        let after = Array(windows[(focusIndex + 1)...])
        var placements: [Placement] = []
        placements += verticalRail(
            windows: before,
            frame: CGRect(x: bounds.minX, y: bounds.minY, width: sideWidth, height: bounds.height),
            zStart: 0
        )
        placements.append(
            Placement(
                windowID: focused.windowID,
                frame: CGRect(x: bounds.minX + sideWidth, y: bounds.minY, width: focusWidth, height: bounds.height),
                zOrder: placements.count
            )
        )
        placements += verticalRail(
            windows: after,
            frame: CGRect(x: bounds.maxX - sideWidth, y: bounds.minY, width: sideWidth, height: bounds.height),
            zStart: placements.count
        )
        return placements
    }

    private func equalColumns(windows: [WindowSnapshot], in bounds: CGRect) -> [Placement] {
        let width = bounds.width / CGFloat(windows.count)
        return windows.enumerated().map { index, window in
            Placement(
                windowID: window.windowID,
                frame: CGRect(
                    x: bounds.minX + CGFloat(index) * width,
                    y: bounds.minY,
                    width: width,
                    height: bounds.height
                ),
                zOrder: index
            )
        }
    }

    private func verticalRail(windows: [WindowSnapshot], frame: CGRect, zStart: Int) -> [Placement] {
        guard !windows.isEmpty else { return [] }
        let height = frame.height / CGFloat(windows.count)
        return windows.enumerated().map { index, window in
            Placement(
                windowID: window.windowID,
                frame: CGRect(
                    x: frame.minX,
                    y: frame.minY + CGFloat(index) * height,
                    width: frame.width,
                    height: height
                ),
                zOrder: zStart + index
            )
        }
    }
}

public struct GoldenColumnsLayoutEngine: LayoutEngine {
    public struct Config: Equatable, Sendable {
        public let ratio: CGFloat

        public init(ratio: CGFloat = 0.618) {
            self.ratio = max(0.2, min(0.8, ratio))
        }
    }

    public let id = LayoutEngineID(rawValue: "dev.olly.showcase.golden-columns")
    public let displayName = "Golden Columns"
    public let capabilities: LayoutEngineCapabilities = [.supportsResizing]
    public let config: Config

    public init(config: Config = Config()) {
        self.config = config
    }

    public func arrange(windows: [WindowSnapshot], in bounds: CGRect, focus: WindowID?) -> [Placement] {
        guard !windows.isEmpty else { return [] }
        var remaining = bounds
        return windows.enumerated().map { index, window in
            let isLast = index == windows.count - 1
            let width = isLast ? remaining.width : max(1, remaining.width * config.ratio)
            let frame = CGRect(x: remaining.minX, y: remaining.minY, width: width, height: remaining.height)
            remaining.origin.x += width
            remaining.size.width = max(0, remaining.width - width)
            return Placement(windowID: window.windowID, frame: frame, zOrder: index)
        }
    }
}

public struct PriorityGridLayoutEngine: LayoutEngine {
    public struct Config: Equatable, Sendable {
        public let priorityBundleIDs: Set<String>

        public init(priorityBundleIDs: Set<String> = []) {
            self.priorityBundleIDs = priorityBundleIDs
        }
    }

    public let id = LayoutEngineID(rawValue: "dev.olly.showcase.priority-grid")
    public let displayName = "Priority Grid"
    public let capabilities: LayoutEngineCapabilities = [.supportsResizing]
    public let config: Config

    public init(config: Config = Config()) {
        self.config = config
    }

    public func arrange(windows: [WindowSnapshot], in bounds: CGRect, focus: WindowID?) -> [Placement] {
        let priority = windows.filter { window in
            window.bundleID.map(config.priorityBundleIDs.contains) ?? false
        }
        let regular = windows.filter { window in
            !(window.bundleID.map(config.priorityBundleIDs.contains) ?? false)
        }
        let topHeight = priority.isEmpty ? 0 : bounds.height * 0.4
        let regularFrame = CGRect(
            x: bounds.minX,
            y: bounds.minY + topHeight,
            width: bounds.width,
            height: bounds.height - topHeight
        )
        let priorityFrame = CGRect(x: bounds.minX, y: bounds.minY, width: bounds.width, height: topHeight)
        return equalColumns(windows: priority, in: priorityFrame, zStart: 0) +
            grid(windows: regular, in: regularFrame, zStart: priority.count)
    }

    private func equalColumns(windows: [WindowSnapshot], in bounds: CGRect, zStart: Int) -> [Placement] {
        guard !windows.isEmpty else { return [] }
        let width = bounds.width / CGFloat(windows.count)
        return windows.enumerated().map { index, window in
            Placement(
                windowID: window.windowID,
                frame: CGRect(
                    x: bounds.minX + CGFloat(index) * width,
                    y: bounds.minY,
                    width: width,
                    height: bounds.height
                ),
                zOrder: zStart + index
            )
        }
    }

    private func grid(windows: [WindowSnapshot], in bounds: CGRect, zStart: Int) -> [Placement] {
        guard !windows.isEmpty else { return [] }
        let columns = Int(ceil(sqrt(Double(windows.count))))
        let rows = Int(ceil(Double(windows.count) / Double(columns)))
        let width = bounds.width / CGFloat(columns)
        let height = bounds.height / CGFloat(rows)
        return windows.enumerated().map { index, window in
            let column = index % columns
            let row = index / columns
            return Placement(
                windowID: window.windowID,
                frame: CGRect(
                    x: bounds.minX + CGFloat(column) * width,
                    y: bounds.minY + CGFloat(row) * height,
                    width: width,
                    height: height
                ),
                zOrder: zStart + index
            )
        }
    }
}
