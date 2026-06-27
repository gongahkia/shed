import CoreGraphics
import ollyCore
import ollyKit

public struct TabbedLayoutTab: Codable, Equatable, Sendable {
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

/// Tabbed layout inspired by i3/Sway: one selected window fills a tile below a top tab strip.
public struct TabbedLayoutEngine: LayoutEngine {
    public struct Config: Codable, Equatable, Sendable {
        public let tabBarHeight: CGFloat
        public let offscreenOrigin: CGPoint

        public init(tabBarHeight: CGFloat = 28, offscreenOrigin: CGPoint = OffscreenParking.defaultFallbackOrigin) {
            precondition(tabBarHeight >= 0 && tabBarHeight.isFinite)
            self.tabBarHeight = tabBarHeight
            self.offscreenOrigin = offscreenOrigin
        }
    }

    public static let engineID = LayoutEngineID(rawValue: "tabbed")

    public let id = TabbedLayoutEngine.engineID
    public let displayName = "Tabbed"
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

    public func tabs(windows: [WindowSnapshot], focus: WindowID?) -> [TabbedLayoutTab] {
        let selectedID = selectedWindowID(windows: windows, focus: focus)
        return windows.enumerated().map { index, window in
            TabbedLayoutTab(
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
        let height = max(0, bounds.height - config.tabBarHeight)
        return CGRect(x: bounds.minX, y: bounds.minY, width: bounds.width, height: height)
    }

    private func hiddenFrame(for window: WindowSnapshot) -> CGRect {
        CGRect(origin: config.offscreenOrigin, size: window.frame.size)
    }
}

public struct TabbedLayoutEngineFactory: LayoutEngineFactory {
    public let id = TabbedLayoutEngine.engineID
    public let displayName = "Tabbed"

    public init() {}

    public func makeEngine(config: TabbedLayoutEngine.Config) throws -> TabbedLayoutEngine {
        TabbedLayoutEngine(config: config)
    }
}
