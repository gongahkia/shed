import CoreGraphics
import ollyCore
import ollyKit
import ollyLayouts

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
