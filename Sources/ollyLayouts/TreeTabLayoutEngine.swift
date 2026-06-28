import CoreGraphics
import ollyCore
import ollyKit

public enum TreeTabSide: String, Codable, Equatable, Sendable {
    case left
    case right
}

public struct TreeTabLayoutItem: Codable, Equatable, Sendable {
    public let windowID: WindowID
    public let title: String?
    public let index: Int
    public let depth: Int
    public let isSelected: Bool

    public init(windowID: WindowID, title: String?, index: Int, depth: Int, isSelected: Bool) {
        self.windowID = windowID
        self.title = title
        self.index = index
        self.depth = depth
        self.isSelected = isSelected
    }
}

public indirect enum TreeTabNode: Codable, Equatable, Sendable {
    case window(id: WindowID, children: [TreeTabNode] = [])

    public var windowID: WindowID {
        switch self {
        case let .window(id, _):
            return id
        }
    }

    public var children: [TreeTabNode] {
        switch self {
        case let .window(_, children):
            return children
        }
    }

    func entries(depth: Int) -> [(windowID: WindowID, depth: Int)] {
        switch self {
        case let .window(id, children):
            return [(windowID: id, depth: depth)] + children.flatMap { $0.entries(depth: depth + 1) }
        }
    }
}

public struct TreeTabTree: Codable, Equatable, Sendable {
    public let roots: [TreeTabNode]

    public init(_ roots: [TreeTabNode] = []) {
        self.roots = roots
    }

    func entries(availableWindowIDs: [WindowID]) -> [(windowID: WindowID, depth: Int)] {
        let available = Set(availableWindowIDs)
        var seen = Set<WindowID>()
        var entries: [(windowID: WindowID, depth: Int)] = []
        for entry in roots.flatMap({ $0.entries(depth: 0) }) where available.contains(entry.windowID) {
            if seen.insert(entry.windowID).inserted {
                entries.append(entry)
            }
        }
        for windowID in availableWindowIDs where !seen.contains(windowID) {
            seen.insert(windowID)
            entries.append((windowID: windowID, depth: 0))
        }
        return entries
    }
}

/// TreeTab layout inspired by Qtile: one selected window fills a tile beside a side tree rail.
public struct TreeTabLayoutEngine: LayoutEngine {
    public struct Config: Codable, Equatable, Sendable {
        public let railWidth: CGFloat
        public let side: TreeTabSide
        public let tree: TreeTabTree
        public let offscreenOrigin: CGPoint

        public init(
            railWidth: CGFloat = 150,
            side: TreeTabSide = .left,
            tree: TreeTabTree = TreeTabTree(),
            offscreenOrigin: CGPoint = OffscreenParking.defaultFallbackOrigin
        ) {
            precondition(railWidth >= 0 && railWidth.isFinite)
            self.railWidth = railWidth
            self.side = side
            self.tree = tree
            self.offscreenOrigin = offscreenOrigin
        }
    }

    public static let engineID = LayoutEngineID(rawValue: "tree-tab")

    public let id = TreeTabLayoutEngine.engineID
    public let displayName = "TreeTab"
    public let config: Config
    public let capabilities: LayoutEngineCapabilities = [.supportsResizing]

    public init(config: Config = Config()) {
        self.config = config
    }

    public func arrange(windows: [WindowSnapshot], in bounds: CGRect, focus: WindowID?) -> [Placement] {
        let entries = config.tree.entries(availableWindowIDs: windows.map(\.windowID))
        guard let selectedID = selectedWindowID(entries: entries, focus: focus) else {
            return []
        }
        let contentFrame = contentFrame(in: bounds)
        let windowsByID = Dictionary(uniqueKeysWithValues: windows.map { ($0.windowID, $0) })
        return entries.enumerated().compactMap { index, entry in
            guard let window = windowsByID[entry.windowID] else {
                return nil
            }
            let isSelected = window.windowID == selectedID
            return Placement(
                windowID: window.windowID,
                frame: isSelected ? contentFrame : hiddenFrame(for: window),
                zOrder: index,
                hidden: !isSelected
            )
        }
    }

    public func items(windows: [WindowSnapshot], focus: WindowID?) -> [TreeTabLayoutItem] {
        let entries = config.tree.entries(availableWindowIDs: windows.map(\.windowID))
        let selectedID = selectedWindowID(entries: entries, focus: focus)
        let windowsByID = Dictionary(uniqueKeysWithValues: windows.map { ($0.windowID, $0) })
        return entries.enumerated().compactMap { index, entry in
            guard let window = windowsByID[entry.windowID] else {
                return nil
            }
            return TreeTabLayoutItem(
                windowID: window.windowID,
                title: window.title,
                index: index,
                depth: entry.depth,
                isSelected: window.windowID == selectedID
            )
        }
    }

    private func selectedWindowID(entries: [(windowID: WindowID, depth: Int)], focus: WindowID?) -> WindowID? {
        if let focus, entries.contains(where: { $0.windowID == focus }) {
            return focus
        }
        return entries.first?.windowID
    }

    private func contentFrame(in bounds: CGRect) -> CGRect {
        let railWidth = min(config.railWidth, bounds.width)
        switch config.side {
        case .left:
            return CGRect(
                x: bounds.minX + railWidth,
                y: bounds.minY,
                width: bounds.width - railWidth,
                height: bounds.height
            )
        case .right:
            return CGRect(
                x: bounds.minX,
                y: bounds.minY,
                width: bounds.width - railWidth,
                height: bounds.height
            )
        }
    }

    private func hiddenFrame(for window: WindowSnapshot) -> CGRect {
        CGRect(origin: config.offscreenOrigin, size: window.frame.size)
    }
}

public struct TreeTabLayoutEngineFactory: LayoutEngineFactory {
    public let id = TreeTabLayoutEngine.engineID
    public let displayName = "TreeTab"

    public init() {}

    public func makeEngine(config: TreeTabLayoutEngine.Config) throws -> TreeTabLayoutEngine {
        TreeTabLayoutEngine(config: config)
    }
}
