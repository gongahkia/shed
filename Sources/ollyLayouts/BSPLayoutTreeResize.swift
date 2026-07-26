import CoreGraphics
import ollyKit

public enum BSPChildSide: String, Codable, Equatable, Sendable {
    case first
    case second
}

public extension BSPLayoutTree {
    func resizingSplit(
        containing windowID: WindowID,
        axis: BSPSplitAxis,
        focusedChild: BSPChildSide,
        ratio: CGFloat
    ) throws -> BSPLayoutTree {
        guard let root else {
            throw BSPLayoutTreeError.missingWindow(windowID)
        }
        let result = root.resizingSplit(BSPResizeRequest(
            windowID: windowID,
            axis: axis,
            focusedChild: focusedChild,
            ratio: ratio
        ))
        guard result.containsWindow else {
            throw BSPLayoutTreeError.missingWindow(windowID)
        }
        guard result.didResize else {
            throw BSPLayoutTreeError.missingSplit(windowID, axis)
        }
        return BSPLayoutTree(root: result.node)
    }
}

private struct BSPResizeRequest {
    let windowID: WindowID
    let axis: BSPSplitAxis
    let focusedChild: BSPChildSide
    let ratio: CGFloat
}

private struct BSPResizeResult {
    let node: BSPNode
    let containsWindow: Bool
    let didResize: Bool
}

private struct BSPResizeContext {
    let axis: BSPSplitAxis
    let ratio: CGFloat
    let first: BSPNode
    let second: BSPNode
    let actualFocusedChild: BSPChildSide
    let childDidResize: Bool
}

private extension BSPNode {
    func resizingSplit(_ request: BSPResizeRequest) -> BSPResizeResult {
        switch self {
        case let .window(id):
            return BSPResizeResult(node: self, containsWindow: id == request.windowID, didResize: false)
        case let .split(axis, ratio, first, second):
            return splitResizeResult(axis: axis, ratio: ratio, first: first, second: second, request: request)
        }
    }

    func splitResizeResult(
        axis: BSPSplitAxis,
        ratio: CGFloat,
        first: BSPNode,
        second: BSPNode,
        request: BSPResizeRequest
    ) -> BSPResizeResult {
        let firstResult = first.resizingSplit(request)
        if firstResult.containsWindow {
            return resizeResult(BSPResizeContext(
                axis: axis,
                ratio: ratio,
                first: firstResult.node,
                second: second,
                actualFocusedChild: .first,
                childDidResize: firstResult.didResize
            ), request: request)
        }

        let secondResult = second.resizingSplit(request)
        if secondResult.containsWindow {
            return resizeResult(BSPResizeContext(
                axis: axis,
                ratio: ratio,
                first: first,
                second: secondResult.node,
                actualFocusedChild: .second,
                childDidResize: secondResult.didResize
            ), request: request)
        }

        return BSPResizeResult(node: self, containsWindow: false, didResize: false)
    }

    func resizeResult(_ context: BSPResizeContext, request: BSPResizeRequest) -> BSPResizeResult {
        if context.childDidResize ||
            context.axis != request.axis ||
            context.actualFocusedChild != request.focusedChild {
            return BSPResizeResult(
                node: .split(axis: context.axis, ratio: context.ratio, first: context.first, second: context.second),
                containsWindow: true,
                didResize: context.childDidResize
            )
        }

        let ratio = context.actualFocusedChild == .first
            ? Self.clampedRatio(request.ratio)
            : 1 - Self.clampedRatio(request.ratio)
        return BSPResizeResult(
            node: .split(axis: context.axis, ratio: ratio, first: context.first, second: context.second),
            containsWindow: true,
            didResize: true
        )
    }

    static func clampedRatio(_ ratio: CGFloat) -> CGFloat {
        min(max(ratio, 0.05), 0.95)
    }
}
