import CoreGraphics
import ollyCore
import ollyKit

public enum FrameSplitAxis: String, Codable, Equatable, Sendable {
    case horizontal
    case vertical
}

public struct FrameLayoutFrame: Codable, Equatable, Sendable {
    public let engineID: LayoutEngineID
    public let windowIDs: [WindowID]

    public init(engineID: LayoutEngineID = FloatingLayoutEngine.engineID, windowIDs: [WindowID] = []) {
        self.engineID = engineID
        self.windowIDs = windowIDs
    }
}

public indirect enum FrameLayoutNode: Codable, Equatable, Sendable {
    case frame(FrameLayoutFrame)
    case split(axis: FrameSplitAxis, ratio: CGFloat = 0.5, first: FrameLayoutNode, second: FrameLayoutNode)
}

public struct FrameLayoutTree: Codable, Equatable, Sendable {
    public let root: FrameLayoutNode

    public init(root: FrameLayoutNode = .frame(FrameLayoutFrame())) {
        self.root = root
    }
}

/// Recursive frame tree inspired by herbstluftwm frames:
/// https://herbstluftwm.org/tutorial.html
public struct FrameLayoutEngine: LayoutEngine {
    public struct Config: Codable, Equatable, Sendable {
        public let tree: FrameLayoutTree

        public init(tree: FrameLayoutTree = FrameLayoutTree()) {
            self.tree = tree
        }
    }

    public static let engineID = LayoutEngineID(rawValue: "frame")

    public let id = FrameLayoutEngine.engineID
    public let displayName = "Frame"
    public let config: Config
    public let capabilities: LayoutEngineCapabilities = [.supportsResizing]
    private let enginesByID: [LayoutEngineID: AnyLayoutEngine]

    public init(config: Config = Config(), engines: [AnyLayoutEngine] = Self.defaultEngines()) {
        self.config = config
        self.enginesByID = Dictionary(uniqueKeysWithValues: engines.map { ($0.id, $0) })
    }

    public func arrange(windows: [WindowSnapshot], in bounds: CGRect, focus: WindowID?) -> [Placement] {
        var placements: [Placement] = []
        appendPlacements(
            from: config.tree.root,
            windows: windows,
            bounds: bounds,
            focus: focus,
            into: &placements
        )
        return placements.enumerated().map { index, placement in
            Placement(
                windowID: placement.windowID,
                frame: placement.frame,
                zOrder: index,
                hidden: placement.hidden
            )
        }
    }

    public static func defaultEngines() -> [AnyLayoutEngine] {
        [
            AnyLayoutEngine(FloatingLayoutEngine()),
            AnyLayoutEngine(MasterStackLayoutEngine()),
            AnyLayoutEngine(ManualLayoutEngine()),
            AnyLayoutEngine(BSPLayoutEngine()),
            AnyLayoutEngine(NiriScrollLayoutEngine()),
            AnyLayoutEngine(MonocleLayoutEngine()),
            AnyLayoutEngine(SpiralLayoutEngine()),
            AnyLayoutEngine(GridLayoutEngine()),
            AnyLayoutEngine(ThreeColLayoutEngine()),
            AnyLayoutEngine(AccordionLayoutEngine()),
            AnyLayoutEngine(VerticalTileLayoutEngine()),
            AnyLayoutEngine(RatioTileLayoutEngine())
        ]
    }

    private func appendPlacements(
        from node: FrameLayoutNode,
        windows: [WindowSnapshot],
        bounds: CGRect,
        focus: WindowID?,
        into placements: inout [Placement]
    ) {
        switch node {
        case let .frame(frame):
            let engine = enginesByID[frame.engineID] ?? AnyLayoutEngine(FloatingLayoutEngine())
            placements.append(contentsOf: engine.arrange(
                windows: windowsForFrame(frame, from: windows),
                in: bounds,
                focus: focus
            ))
        case let .split(axis, ratio, first, second):
            let frames = childFrames(axis: axis, ratio: ratio, bounds: bounds)
            appendPlacements(from: first, windows: windows, bounds: frames.first, focus: focus, into: &placements)
            appendPlacements(from: second, windows: windows, bounds: frames.second, focus: focus, into: &placements)
        }
    }

    private func windowsForFrame(_ frame: FrameLayoutFrame, from windows: [WindowSnapshot]) -> [WindowSnapshot] {
        guard !frame.windowIDs.isEmpty else {
            return windows
        }
        let ids = Set(frame.windowIDs)
        return windows.filter { ids.contains($0.windowID) }
    }

    private func childFrames(
        axis: FrameSplitAxis,
        ratio: CGFloat,
        bounds: CGRect
    ) -> (first: CGRect, second: CGRect) {
        switch axis {
        case .horizontal:
            let width = floor(bounds.width * ratio)
            return (
                CGRect(x: bounds.minX, y: bounds.minY, width: width, height: bounds.height),
                CGRect(x: bounds.minX + width, y: bounds.minY, width: bounds.width - width, height: bounds.height)
            )
        case .vertical:
            let height = floor(bounds.height * ratio)
            return (
                CGRect(x: bounds.minX, y: bounds.minY, width: bounds.width, height: height),
                CGRect(x: bounds.minX, y: bounds.minY + height, width: bounds.width, height: bounds.height - height)
            )
        }
    }
}

public struct FrameLayoutEngineFactory: LayoutEngineFactory {
    public let id = FrameLayoutEngine.engineID
    public let displayName = "Frame"

    public init() {}

    public func makeEngine(config: FrameLayoutEngine.Config) throws -> FrameLayoutEngine {
        FrameLayoutEngine(config: config)
    }
}
