import CoreGraphics
import ollyCore
import ollyKit

/// Fullscreen layout following dwm's monocle precedent: one focused window fills the tile, siblings park offscreen.
public struct MonocleLayoutEngine: LayoutEngine {
    public struct Config: Codable, Equatable, Sendable {
        public let offscreenOrigin: CGPoint

        public init(offscreenOrigin: CGPoint = OffscreenParking.defaultFallbackOrigin) {
            self.offscreenOrigin = offscreenOrigin
        }
    }

    public static let engineID = LayoutEngineID(rawValue: "monocle")

    public let id = MonocleLayoutEngine.engineID
    public let displayName = "Monocle"
    public let config: Config
    public let capabilities: LayoutEngineCapabilities = [.supportsResizing]

    public init(config: Config = Config()) {
        self.config = config
    }

    public func arrange(windows: [WindowSnapshot], in bounds: CGRect, focus: WindowID?) -> [Placement] {
        guard let visibleID = visibleWindowID(windows: windows, focus: focus) else {
            return []
        }

        return windows.enumerated().map { index, window in
            let isVisible = window.windowID == visibleID
            return Placement(
                windowID: window.windowID,
                frame: isVisible ? bounds : hiddenFrame(for: window),
                zOrder: index,
                hidden: !isVisible
            )
        }
    }

    public func nextFocus(windows: [WindowSnapshot], focus: WindowID?) -> WindowID? {
        cycleFocus(windows: windows, focus: focus, reverse: false)
    }

    public func previousFocus(windows: [WindowSnapshot], focus: WindowID?) -> WindowID? {
        cycleFocus(windows: windows, focus: focus, reverse: true)
    }

    private func visibleWindowID(windows: [WindowSnapshot], focus: WindowID?) -> WindowID? {
        if let focus, windows.contains(where: { $0.windowID == focus }) {
            return focus
        }
        return windows.first?.windowID
    }

    private func cycleFocus(windows: [WindowSnapshot], focus: WindowID?, reverse: Bool) -> WindowID? {
        let ids = windows.map(\.windowID)
        guard !ids.isEmpty else {
            return nil
        }

        let currentIndex = focus.flatMap { ids.firstIndex(of: $0) } ?? (reverse ? 0 : ids.count - 1)
        let offset = reverse ? -1 : 1
        return ids[(currentIndex + offset + ids.count) % ids.count]
    }

    private func hiddenFrame(for window: WindowSnapshot) -> CGRect {
        CGRect(origin: config.offscreenOrigin, size: window.frame.size)
    }
}

public struct MonocleLayoutEngineFactory: LayoutEngineFactory {
    public let id = MonocleLayoutEngine.engineID
    public let displayName = "Monocle"

    public init() {}

    public func makeEngine(config: MonocleLayoutEngine.Config) throws -> MonocleLayoutEngine {
        MonocleLayoutEngine(config: config)
    }
}
