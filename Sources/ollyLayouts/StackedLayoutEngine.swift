import CoreGraphics
import ollyCore
import ollyKit

public struct StackedLayoutItem: Codable, Equatable, Sendable {
    public let windowID: WindowID
    public let title: String?
    public let index: Int
    public let isSelected: Bool

    public init(windowID: WindowID, title: String?, index: Int, isSelected: Bool) {
        self.windowID = windowID
        self.title = title
        self.index = index
        self.isSelected = isSelected
    }
}

/// Stacked layout inspired by i3/Sway: one selected window fills a tile beside a full-height title rail.
public struct StackedLayoutEngine: LayoutEngine {
    public struct Config: Codable, Equatable, Sendable {
        public let railWidth: CGFloat
        public let offscreenOrigin: CGPoint

        public init(railWidth: CGFloat = 160, offscreenOrigin: CGPoint = OffscreenParking.defaultFallbackOrigin) {
            precondition(railWidth >= 0 && railWidth.isFinite)
            self.railWidth = railWidth
            self.offscreenOrigin = offscreenOrigin
        }
    }

    public static let engineID = LayoutEngineID(rawValue: "stacked")

    public let id = StackedLayoutEngine.engineID
    public let displayName = "Stacked"
    public let config: Config
    public let capabilities: LayoutEngineCapabilities = [.supportsResizing]

    public init(config: Config = Config()) {
        self.config = config
    }

    public func arrange(windows: [WindowSnapshot], in bounds: CGRect, focus: WindowID?) -> [Placement] {
        guard let selectedID = selectedWindowID(windows: windows, focus: focus) else {
            return []
        }
        let contentFrame = contentFrame(in: bounds)
        return windows.enumerated().map { index, window in
            let isSelected = window.windowID == selectedID
            return Placement(
                windowID: window.windowID,
                frame: isSelected ? contentFrame : hiddenFrame(for: window),
                zOrder: index,
                hidden: !isSelected
            )
        }
    }

    public func items(windows: [WindowSnapshot], focus: WindowID?) -> [StackedLayoutItem] {
        let selectedID = selectedWindowID(windows: windows, focus: focus)
        return windows.enumerated().map { index, window in
            StackedLayoutItem(
                windowID: window.windowID,
                title: window.title,
                index: index,
                isSelected: window.windowID == selectedID
            )
        }
    }

    private func selectedWindowID(windows: [WindowSnapshot], focus: WindowID?) -> WindowID? {
        if let focus, windows.contains(where: { $0.windowID == focus }) {
            return focus
        }
        return windows.first?.windowID
    }

    private func contentFrame(in bounds: CGRect) -> CGRect {
        let railWidth = min(config.railWidth, bounds.width)
        return CGRect(
            x: bounds.minX + railWidth,
            y: bounds.minY,
            width: bounds.width - railWidth,
            height: bounds.height
        )
    }

    private func hiddenFrame(for window: WindowSnapshot) -> CGRect {
        CGRect(origin: config.offscreenOrigin, size: window.frame.size)
    }
}

public struct StackedLayoutEngineFactory: LayoutEngineFactory {
    public let id = StackedLayoutEngine.engineID
    public let displayName = "Stacked"

    public init() {}

    public func makeEngine(config: StackedLayoutEngine.Config) throws -> StackedLayoutEngine {
        StackedLayoutEngine(config: config)
    }
}
