import CoreGraphics
import QuartzCore
import ollyCore
import ollyKit

public struct NiriScrollLayoutEngine: LayoutEngine {
    public struct Config: Codable, Equatable, Sendable {
        public let strip: NiriScrollStrip
        public let defaultColumnWidth: NiriColumnWidthPreset
        public let animationDuration: TimeInterval

        public init(
            strip: NiriScrollStrip = NiriScrollStrip(),
            defaultColumnWidth: NiriColumnWidthPreset = .half,
            animationDuration: TimeInterval = 0.2
        ) {
            precondition(animationDuration >= 0)
            self.strip = strip
            self.defaultColumnWidth = defaultColumnWidth
            self.animationDuration = animationDuration
        }
    }

    public static let engineID = LayoutEngineID(rawValue: "niri-scroll")

    public let id = NiriScrollLayoutEngine.engineID
    public let displayName = "NiriScroll"
    public let config: Config
    public let capabilities: LayoutEngineCapabilities = [.supportsResizing]

    public init(config: Config = Config()) {
        self.config = config
    }

    public func arrange(windows: [WindowSnapshot], in bounds: CGRect, focus: WindowID?) -> [Placement] {
        config.strip.placements(
            in: bounds,
            windowIDs: windows.map(\.windowID),
            focus: focus,
            defaultColumnWidth: config.defaultColumnWidth
        )
    }

    public func viewportAnimation(from startOffset: CGFloat, to endOffset: CGFloat) -> CABasicAnimation {
        let animation = CABasicAnimation(keyPath: "transform.translation.x")
        animation.fromValue = -startOffset
        animation.toValue = -endOffset
        animation.duration = config.animationDuration
        animation.timingFunction = CAMediaTimingFunction(name: .easeOut)
        return animation
    }
}

public struct NiriScrollLayoutEngineFactory: LayoutEngineFactory {
    public let id = NiriScrollLayoutEngine.engineID
    public let displayName = "NiriScroll"

    public init() {}

    public func makeEngine(config: NiriScrollLayoutEngine.Config) throws -> NiriScrollLayoutEngine {
        NiriScrollLayoutEngine(config: config)
    }
}
