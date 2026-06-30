import CoreGraphics
import ollyKit

public enum BSPSplitAxis: String, Codable, Equatable, Sendable {
    case horizontal
    case vertical

    public static func longest(in bounds: CGRect) -> BSPSplitAxis {
        bounds.width >= bounds.height ? .horizontal : .vertical
    }

    public var flipped: BSPSplitAxis {
        switch self {
        case .horizontal:
            return .vertical
        case .vertical:
            return .horizontal
        }
    }
}

public struct BSPContainerPath: Codable, Equatable, Hashable, Sendable {
    public static let root = BSPContainerPath()

    public let indexes: [Int]

    public init(_ indexes: [Int] = []) {
        precondition(indexes.allSatisfy { $0 == 0 || $0 == 1 })
        self.indexes = indexes
    }
}

public enum BSPLayoutTreeError: Error, Equatable, Sendable {
    case duplicateWindow(WindowID)
    case missingContainer(BSPContainerPath)
    case missingWindow(WindowID)
    case missingSplit(WindowID, BSPSplitAxis)
    case notSplitContainer(BSPContainerPath)
}

public indirect enum BSPNode: Codable, Equatable, Sendable {
    case window(id: WindowID)
    case split(axis: BSPSplitAxis, ratio: CGFloat = 0.5, first: BSPNode, second: BSPNode)

    public var windowID: WindowID? {
        guard case let .window(id) = self else {
            return nil
        }
        return id
    }

    public var axis: BSPSplitAxis? {
        guard case let .split(axis, _, _, _) = self else {
            return nil
        }
        return axis
    }

    public var children: [BSPNode] {
        guard case let .split(_, _, first, second) = self else {
            return []
        }
        return [first, second]
    }

    fileprivate func path(to windowID: WindowID, prefix: [Int] = []) -> BSPContainerPath? {
        switch self {
        case let .window(id):
            return id == windowID ? BSPContainerPath(prefix) : nil
        case let .split(_, _, first, second):
            return first.path(to: windowID, prefix: prefix + [0]) ??
                second.path(to: windowID, prefix: prefix + [1])
        }
    }

    fileprivate func windowIDs() -> [WindowID] {
        switch self {
        case let .window(id):
            return [id]
        case let .split(_, _, first, second):
            return first.windowIDs() + second.windowIDs()
        }
    }

    fileprivate func insertingWindow(
        _ windowID: WindowID,
        at path: ArraySlice<Int>,
        splitAxis: BSPSplitAxis,
        originalPath: BSPContainerPath
    ) throws -> BSPNode {
        guard let index = path.first else {
            return .split(axis: splitAxis, first: self, second: .window(id: windowID))
        }
        guard case let .split(axis, ratio, first, second) = self else {
            throw BSPLayoutTreeError.missingContainer(originalPath)
        }

        switch index {
        case 0:
            let updatedFirst = try first.insertingWindow(
                windowID,
                at: path.dropFirst(),
                splitAxis: splitAxis,
                originalPath: originalPath
            )
            return .split(axis: axis, ratio: ratio, first: updatedFirst, second: second)
        case 1:
            let updatedSecond = try second.insertingWindow(
                windowID,
                at: path.dropFirst(),
                splitAxis: splitAxis,
                originalPath: originalPath
            )
            return .split(axis: axis, ratio: ratio, first: first, second: updatedSecond)
        default:
            throw BSPLayoutTreeError.missingContainer(originalPath)
        }
    }

    fileprivate func rotatingChildren(
        at path: ArraySlice<Int>,
        originalPath: BSPContainerPath
    ) throws -> BSPNode {
        guard let index = path.first else {
            guard case let .split(axis, ratio, first, second) = self else {
                throw BSPLayoutTreeError.notSplitContainer(originalPath)
            }
            return .split(axis: axis, ratio: 1 - ratio, first: second, second: first)
        }
        guard case let .split(axis, ratio, first, second) = self else {
            throw BSPLayoutTreeError.missingContainer(originalPath)
        }

        switch index {
        case 0:
            let updatedFirst = try first.rotatingChildren(
                at: path.dropFirst(),
                originalPath: originalPath
            )
            return .split(axis: axis, ratio: ratio, first: updatedFirst, second: second)
        case 1:
            let updatedSecond = try second.rotatingChildren(
                at: path.dropFirst(),
                originalPath: originalPath
            )
            return .split(axis: axis, ratio: ratio, first: first, second: updatedSecond)
        default:
            throw BSPLayoutTreeError.missingContainer(originalPath)
        }
    }

    fileprivate func flippingAxis(
        at path: ArraySlice<Int>,
        originalPath: BSPContainerPath
    ) throws -> BSPNode {
        guard let index = path.first else {
            guard case let .split(axis, ratio, first, second) = self else {
                throw BSPLayoutTreeError.notSplitContainer(originalPath)
            }
            return .split(axis: axis.flipped, ratio: ratio, first: first, second: second)
        }
        guard case let .split(axis, ratio, first, second) = self else {
            throw BSPLayoutTreeError.missingContainer(originalPath)
        }

        switch index {
        case 0:
            let updatedFirst = try first.flippingAxis(at: path.dropFirst(), originalPath: originalPath)
            return .split(axis: axis, ratio: ratio, first: updatedFirst, second: second)
        case 1:
            let updatedSecond = try second.flippingAxis(at: path.dropFirst(), originalPath: originalPath)
            return .split(axis: axis, ratio: ratio, first: first, second: updatedSecond)
        default:
            throw BSPLayoutTreeError.missingContainer(originalPath)
        }
    }

    fileprivate func frame(
        at path: ArraySlice<Int>,
        in bounds: CGRect,
        originalPath: BSPContainerPath
    ) throws -> CGRect {
        guard let index = path.first else {
            return bounds
        }
        guard case let .split(axis, ratio, first, second) = self else {
            throw BSPLayoutTreeError.missingContainer(originalPath)
        }

        let frames = childFrames(axis: axis, ratio: ratio, bounds: bounds)
        switch index {
        case 0:
            return try first.frame(at: path.dropFirst(), in: frames.first, originalPath: originalPath)
        case 1:
            return try second.frame(at: path.dropFirst(), in: frames.second, originalPath: originalPath)
        default:
            throw BSPLayoutTreeError.missingContainer(originalPath)
        }
    }

    fileprivate func pruned(to windowIDs: Set<WindowID>) -> BSPNode? {
        switch self {
        case let .window(id):
            return windowIDs.contains(id) ? self : nil
        case let .split(axis, ratio, first, second):
            let prunedFirst = first.pruned(to: windowIDs)
            let prunedSecond = second.pruned(to: windowIDs)
            switch (prunedFirst, prunedSecond) {
            case let (first?, second?):
                return .split(axis: axis, ratio: ratio, first: first, second: second)
            case let (first?, nil):
                return first
            case let (nil, second?):
                return second
            case (nil, nil):
                return nil
            }
        }
    }

    fileprivate func placements(in bounds: CGRect) -> [Placement] {
        switch self {
        case let .window(id):
            return [Placement(windowID: id, frame: bounds)]
        case let .split(axis, ratio, first, second):
            let frames = childFrames(axis: axis, ratio: ratio, bounds: bounds)
            return first.placements(in: frames.first) + second.placements(in: frames.second)
        }
    }

    fileprivate static func balanced(windowIDs: [WindowID], in bounds: CGRect) -> BSPNode? {
        guard !windowIDs.isEmpty else {
            return nil
        }
        guard windowIDs.count > 1 else {
            return .window(id: windowIDs[0])
        }

        let splitIndex = windowIDs.count / 2
        let firstIDs = Array(windowIDs[..<splitIndex])
        let secondIDs = Array(windowIDs[splitIndex...])
        let axis = BSPSplitAxis.longest(in: bounds)
        let ratio = CGFloat(firstIDs.count) / CGFloat(windowIDs.count)
        let frames = childFrames(axis: axis, ratio: ratio, bounds: bounds)

        guard let first = balanced(windowIDs: firstIDs, in: frames.first),
              let second = balanced(windowIDs: secondIDs, in: frames.second) else {
            return nil
        }
        return .split(axis: axis, ratio: ratio, first: first, second: second)
    }

    private static func childFrames(
        axis: BSPSplitAxis,
        ratio: CGFloat,
        bounds: CGRect
    ) -> (first: CGRect, second: CGRect) {
        let ratio = clampedRatio(ratio)
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

    private func childFrames(
        axis: BSPSplitAxis,
        ratio: CGFloat,
        bounds: CGRect
    ) -> (first: CGRect, second: CGRect) {
        BSPNode.childFrames(axis: axis, ratio: ratio, bounds: bounds)
    }

    private static func clampedRatio(_ ratio: CGFloat) -> CGFloat {
        min(max(ratio, 0.05), 0.95)
    }
}

public struct BSPLayoutTree: Codable, Equatable, Sendable {
    public let root: BSPNode?

    public init(root: BSPNode? = nil) {
        self.root = root
    }

    public func path(to windowID: WindowID) -> BSPContainerPath? {
        root?.path(to: windowID)
    }

    public func placingNextWindow(
        _ windowID: WindowID,
        after focus: WindowID?,
        in bounds: CGRect
    ) throws -> BSPLayoutTree {
        guard let root else {
            return BSPLayoutTree(root: .window(id: windowID))
        }
        guard !root.windowIDs().contains(windowID) else {
            throw BSPLayoutTreeError.duplicateWindow(windowID)
        }

        let targetPath: BSPContainerPath
        if let focus {
            guard let path = path(to: focus) else {
                throw BSPLayoutTreeError.missingWindow(focus)
            }
            targetPath = path
        } else {
            targetPath = .root
        }

        let targetFrame = try root.frame(
            at: targetPath.indexes[...],
            in: bounds,
            originalPath: targetPath
        )
        let updatedRoot = try root.insertingWindow(
            windowID,
            at: targetPath.indexes[...],
            splitAxis: .longest(in: targetFrame),
            originalPath: targetPath
        )
        return BSPLayoutTree(root: updatedRoot)
    }

    public func rotatingChildren(at path: BSPContainerPath = .root) throws -> BSPLayoutTree {
        guard let root else {
            throw BSPLayoutTreeError.missingContainer(path)
        }
        let updatedRoot = try root.rotatingChildren(at: path.indexes[...], originalPath: path)
        return BSPLayoutTree(root: updatedRoot)
    }

    public func flippingAxis(at path: BSPContainerPath = .root) throws -> BSPLayoutTree {
        guard let root else {
            throw BSPLayoutTreeError.missingContainer(path)
        }
        let updatedRoot = try root.flippingAxis(at: path.indexes[...], originalPath: path)
        return BSPLayoutTree(root: updatedRoot)
    }

    public func balancing(in bounds: CGRect) -> BSPLayoutTree {
        BSPLayoutTree(root: BSPNode.balanced(windowIDs: root?.windowIDs() ?? [], in: bounds))
    }

    func placements(in bounds: CGRect, windowIDs: [WindowID]) -> [Placement] {
        let reconciledTree = reconciled(with: windowIDs, in: bounds)
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

    public func reconciled(with windowIDs: [WindowID], in bounds: CGRect) -> BSPLayoutTree {
        let windowIDSet = Set(windowIDs)
        let prunedRoot = root?.pruned(to: windowIDSet)
        let existingWindowIDs = Set(prunedRoot?.windowIDs() ?? [])
        let missingWindowIDs = windowIDs.filter { !existingWindowIDs.contains($0) }
        let updatedRoot = BSPLayoutTree.appending(missingWindowIDs, to: prunedRoot, in: bounds)
        return BSPLayoutTree(root: updatedRoot)
    }

    private static func appending(_ windowIDs: [WindowID], to root: BSPNode?, in bounds: CGRect) -> BSPNode? {
        guard !windowIDs.isEmpty else {
            return root
        }
        guard var updatedRoot = root else {
            return BSPNode.balanced(windowIDs: windowIDs, in: bounds)
        }

        for windowID in windowIDs {
            let existingCount = updatedRoot.windowIDs().count
            let ratio = CGFloat(existingCount) / CGFloat(existingCount + 1)
            updatedRoot = .split(
                axis: .longest(in: bounds),
                ratio: ratio,
                first: updatedRoot,
                second: .window(id: windowID)
            )
        }
        return updatedRoot
    }
}
