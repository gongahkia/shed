import CoreGraphics
import ollyCore
import ollyKit

public enum ManualSplitAxis: String, Codable, Equatable, Sendable {
    case horizontal
    case vertical
}

public enum ManualPreselectDirection: String, Codable, Equatable, Sendable {
    case left
    case right
    case up // swiftlint:disable:this identifier_name
    case down

    public var axis: ManualSplitAxis {
        switch self {
        case .left, .right:
            return .horizontal
        case .up, .down:
            return .vertical
        }
    }

    fileprivate var insertsBeforeTarget: Bool {
        switch self {
        case .left, .up:
            return true
        case .right, .down:
            return false
        }
    }
}

public struct ManualContainerPath: Codable, Equatable, Hashable, Sendable {
    public static let root = ManualContainerPath()

    public let indexes: [Int]

    public init(_ indexes: [Int] = []) {
        precondition(indexes.allSatisfy { $0 >= 0 })
        self.indexes = indexes
    }
}

public enum ManualLayoutTreeError: Error, Equatable, Sendable {
    case duplicateWindow(WindowID)
    case missingContainer(ManualContainerPath)
    case missingWindow(WindowID)
}

public indirect enum ManualContainer: Codable, Equatable, Sendable {
    case window(id: WindowID, preselect: ManualPreselectDirection? = nil)
    case split(
        axis: ManualSplitAxis,
        children: [ManualContainer],
        preselect: ManualPreselectDirection? = nil
    )

    public var preselect: ManualPreselectDirection? {
        switch self {
        case let .window(_, preselect), let .split(_, _, preselect):
            return preselect
        }
    }

    public var windowID: WindowID? {
        guard case let .window(id, _) = self else {
            return nil
        }
        return id
    }

    public var children: [ManualContainer] {
        guard case let .split(_, children, _) = self else {
            return []
        }
        return children
    }

    public var axis: ManualSplitAxis? {
        guard case let .split(axis, _, _) = self else {
            return nil
        }
        return axis
    }

    public func settingPreselect(_ direction: ManualPreselectDirection?) -> ManualContainer {
        switch self {
        case let .window(id, _):
            return .window(id: id, preselect: direction)
        case let .split(axis, children, _):
            return .split(axis: axis, children: children, preselect: direction)
        }
    }

    fileprivate var isWindow: Bool {
        if case .window = self {
            return true
        }
        return false
    }

    fileprivate func path(to windowID: WindowID, prefix: [Int] = []) -> ManualContainerPath? {
        switch self {
        case let .window(id, _):
            return id == windowID ? ManualContainerPath(prefix) : nil
        case let .split(_, children, _):
            for (index, child) in children.enumerated() {
                if let path = child.path(to: windowID, prefix: prefix + [index]) {
                    return path
                }
            }
            return nil
        }
    }

    fileprivate func windowIDs() -> [WindowID] {
        switch self {
        case let .window(id, _):
            return [id]
        case let .split(_, children, _):
            return children.flatMap { $0.windowIDs() }
        }
    }

    fileprivate func settingPreselect(
        _ direction: ManualPreselectDirection?,
        at path: ArraySlice<Int>,
        originalPath: ManualContainerPath
    ) throws -> ManualContainer {
        guard let index = path.first else {
            return settingPreselect(direction)
        }
        guard case let .split(axis, children, preselect) = self,
              children.indices.contains(index) else {
            throw ManualLayoutTreeError.missingContainer(originalPath)
        }

        var updatedChildren = children
        updatedChildren[index] = try children[index].settingPreselect(
            direction,
            at: path.dropFirst(),
            originalPath: originalPath
        )
        return .split(axis: axis, children: updatedChildren, preselect: preselect)
    }

    fileprivate func insertingWindow(
        _ windowID: WindowID,
        at path: ArraySlice<Int>,
        defaultAxis: ManualSplitAxis,
        originalPath: ManualContainerPath
    ) throws -> ManualContainer {
        guard let index = path.first else {
            return insertingWindowAtTarget(windowID, defaultAxis: defaultAxis)
        }
        guard case let .split(axis, children, preselect) = self,
              children.indices.contains(index) else {
            throw ManualLayoutTreeError.missingContainer(originalPath)
        }

        var updatedChildren = children
        if path.count == 1, children[index].preselect == nil, children[index].isWindow {
            updatedChildren.insert(.window(id: windowID), at: index + 1)
        } else {
            updatedChildren[index] = try children[index].insertingWindow(
                windowID,
                at: path.dropFirst(),
                defaultAxis: defaultAxis,
                originalPath: originalPath
            )
        }
        return .split(axis: axis, children: updatedChildren, preselect: preselect)
    }

    fileprivate func pruned(to windowIDs: Set<WindowID>) -> ManualContainer? {
        switch self {
        case let .window(id, _):
            return windowIDs.contains(id) ? self : nil
        case let .split(axis, children, preselect):
            let prunedChildren = children.compactMap { $0.pruned(to: windowIDs) }
            if prunedChildren.isEmpty {
                return nil
            }
            if prunedChildren.count == 1 {
                return prunedChildren[0]
            }
            return .split(axis: axis, children: prunedChildren, preselect: preselect)
        }
    }

    fileprivate func placements(in bounds: CGRect) -> [Placement] {
        switch self {
        case let .window(id, _):
            return [Placement(windowID: id, frame: bounds)]
        case let .split(axis, children, _):
            guard !children.isEmpty else {
                return []
            }
            return children.enumerated().flatMap { index, child in
                child.placements(in: childFrame(for: index, count: children.count, axis: axis, bounds: bounds))
            }
        }
    }

    private func insertingWindowAtTarget(_ windowID: WindowID, defaultAxis: ManualSplitAxis) -> ManualContainer {
        let nextWindow = ManualContainer.window(id: windowID)
        if let preselect {
            let current = settingPreselect(nil)
            let children = preselect.insertsBeforeTarget ? [nextWindow, current] : [current, nextWindow]
            return .split(axis: preselect.axis, children: children)
        }

        switch self {
        case .window:
            return .split(axis: defaultAxis, children: [self, nextWindow])
        case let .split(axis, children, _):
            return .split(axis: axis, children: children + [nextWindow])
        }
    }

    private func childFrame(for index: Int, count: Int, axis: ManualSplitAxis, bounds: CGRect) -> CGRect {
        switch axis {
        case .horizontal:
            let width = bounds.width / CGFloat(count)
            return CGRect(
                x: bounds.minX + CGFloat(index) * width,
                y: bounds.minY,
                width: width,
                height: bounds.height
            )
        case .vertical:
            let height = bounds.height / CGFloat(count)
            return CGRect(
                x: bounds.minX,
                y: bounds.minY + CGFloat(index) * height,
                width: bounds.width,
                height: height
            )
        }
    }
}

public struct ManualLayoutTree: Codable, Equatable, Sendable {
    public let root: ManualContainer?
    public let defaultAxis: ManualSplitAxis

    public init(root: ManualContainer? = nil, defaultAxis: ManualSplitAxis = .horizontal) {
        self.root = root
        self.defaultAxis = defaultAxis
    }

    public func path(to windowID: WindowID) -> ManualContainerPath? {
        root?.path(to: windowID)
    }

    public func preselect(
        _ direction: ManualPreselectDirection?,
        at path: ManualContainerPath
    ) throws -> ManualLayoutTree {
        guard let root else {
            throw ManualLayoutTreeError.missingContainer(path)
        }

        let updatedRoot = try root.settingPreselect(
            direction,
            at: path.indexes[...],
            originalPath: path
        )
        return ManualLayoutTree(root: updatedRoot, defaultAxis: defaultAxis)
    }

    public func preselect(
        _ direction: ManualPreselectDirection?,
        for windowID: WindowID
    ) throws -> ManualLayoutTree {
        guard let path = path(to: windowID) else {
            throw ManualLayoutTreeError.missingWindow(windowID)
        }
        return try preselect(direction, at: path)
    }

    public func placingNextWindow(
        _ windowID: WindowID,
        at path: ManualContainerPath
    ) throws -> ManualLayoutTree {
        guard let root else {
            return ManualLayoutTree(root: .window(id: windowID), defaultAxis: defaultAxis)
        }
        guard !root.windowIDs().contains(windowID) else {
            throw ManualLayoutTreeError.duplicateWindow(windowID)
        }

        let updatedRoot = try root.insertingWindow(
            windowID,
            at: path.indexes[...],
            defaultAxis: defaultAxis,
            originalPath: path
        )
        return ManualLayoutTree(root: updatedRoot, defaultAxis: defaultAxis)
    }

    public func placingNextWindow(
        _ windowID: WindowID,
        after focus: WindowID?
    ) throws -> ManualLayoutTree {
        if let focus {
            guard let path = path(to: focus) else {
                throw ManualLayoutTreeError.missingWindow(focus)
            }
            return try placingNextWindow(windowID, at: path)
        }
        return try placingNextWindow(windowID, at: .root)
    }

    fileprivate func placements(in bounds: CGRect, windowIDs: [WindowID]) -> [Placement] {
        let reconciledTree = reconciled(with: windowIDs)
        let placements = reconciledTree.root?.placements(in: bounds) ?? []
        return placements.enumerated().map { index, placement in
            Placement(
                windowID: placement.windowID,
                frame: placement.frame,
                zOrder: index,
                hidden: placement.hidden
            )
        }
    }

    public func reconciled(with windowIDs: [WindowID]) -> ManualLayoutTree {
        let windowIDSet = Set(windowIDs)
        let prunedRoot = root?.pruned(to: windowIDSet)
        let existingWindowIDs = Set(prunedRoot?.windowIDs() ?? [])
        let missingWindowIDs = windowIDs.filter { !existingWindowIDs.contains($0) }
        let updatedRoot = ManualLayoutTree.appending(
            missingWindowIDs,
            to: prunedRoot,
            defaultAxis: defaultAxis
        )
        return ManualLayoutTree(root: updatedRoot, defaultAxis: defaultAxis)
    }

    private static func appending(
        _ windowIDs: [WindowID],
        to root: ManualContainer?,
        defaultAxis: ManualSplitAxis
    ) -> ManualContainer? {
        let newWindows = windowIDs.map { ManualContainer.window(id: $0) }
        guard !newWindows.isEmpty else {
            return root
        }
        guard let root else {
            return newWindows.count == 1 ? newWindows[0] : .split(axis: defaultAxis, children: newWindows)
        }

        switch root {
        case .window:
            return .split(axis: defaultAxis, children: [root] + newWindows)
        case let .split(axis, children, preselect):
            return .split(axis: axis, children: children + newWindows, preselect: preselect)
        }
    }
}

public struct ManualLayoutEngine: LayoutEngine {
    public struct Config: Codable, Equatable, Sendable {
        public let tree: ManualLayoutTree

        public init(tree: ManualLayoutTree = ManualLayoutTree()) {
            self.tree = tree
        }
    }

    public static let engineID = LayoutEngineID(rawValue: "manual")

    public let id = ManualLayoutEngine.engineID
    public let displayName = "Manual"
    public let config: Config
    public let capabilities: LayoutEngineCapabilities = [.supportsManualSplits, .supportsResizing]

    public init(config: Config = Config()) {
        self.config = config
    }

    public func arrange(windows: [WindowSnapshot], in bounds: CGRect, focus: WindowID?) -> [Placement] {
        config.tree.placements(in: bounds, windowIDs: windows.map(\.windowID))
    }
}

public struct ManualLayoutEngineFactory: LayoutEngineFactory {
    public let id = ManualLayoutEngine.engineID
    public let displayName = "Manual"

    public init() {}

    public func makeEngine(config: ManualLayoutEngine.Config) throws -> ManualLayoutEngine {
        ManualLayoutEngine(config: config)
    }
}
