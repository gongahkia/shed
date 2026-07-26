import CoreGraphics
import ollyCore
import ollyKit

public struct AccordionLayoutEngine: LayoutEngine {
    public struct Config: Codable, Equatable, Sendable {
        public let stripHeight: CGFloat

        public init(stripHeight: CGFloat = 48) {
            precondition(stripHeight > 0)
            self.stripHeight = stripHeight
        }
    }

    public static let engineID = LayoutEngineID(rawValue: "accordion")

    public let id = AccordionLayoutEngine.engineID
    public let displayName = "Accordion"
    public let config: Config
    public let capabilities: LayoutEngineCapabilities = [.supportsResizing]

    public init(config: Config = Config()) {
        self.config = config
    }

    public func arrange(windows: [WindowSnapshot], in bounds: CGRect, focus: WindowID?) -> [Placement] {
        guard !windows.isEmpty else {
            return []
        }
        guard windows.count > 1 else {
            return [Placement(windowID: windows[0].windowID, frame: bounds)]
        }

        let expandedIndex = focus.flatMap { focusedID in
            windows.firstIndex { $0.windowID == focusedID }
        } ?? 0
        let stripHeight = min(config.stripHeight, bounds.height / CGFloat(windows.count))
        let expandedFrame = CGRect(
            x: bounds.minX,
            y: bounds.minY + CGFloat(expandedIndex) * stripHeight,
            width: bounds.width,
            height: bounds.height - CGFloat(windows.count - 1) * stripHeight
        )

        return windows.enumerated().map { index, window in
            Placement(
                windowID: window.windowID,
                frame: frame(
                    at: index,
                    expandedIndex: expandedIndex,
                    stripHeight: stripHeight,
                    expandedFrame: expandedFrame,
                    in: bounds
                ),
                zOrder: index
            )
        }
    }

    private func frame(
        at index: Int,
        expandedIndex: Int,
        stripHeight: CGFloat,
        expandedFrame: CGRect,
        in bounds: CGRect
    ) -> CGRect {
        if index == expandedIndex {
            return expandedFrame
        }

        let stripIndex = index < expandedIndex ? index : index - 1
        let originY = index < expandedIndex
            ? bounds.minY + CGFloat(stripIndex) * stripHeight
            : expandedFrame.maxY + CGFloat(stripIndex - expandedIndex) * stripHeight
        return CGRect(x: bounds.minX, y: originY, width: bounds.width, height: stripHeight)
    }
}

public struct AccordionLayoutEngineFactory: LayoutEngineFactory {
    public let id = AccordionLayoutEngine.engineID
    public let displayName = "Accordion"

    public init() {}

    public func makeEngine(config: AccordionLayoutEngine.Config) throws -> AccordionLayoutEngine {
        AccordionLayoutEngine(config: config)
    }
}
