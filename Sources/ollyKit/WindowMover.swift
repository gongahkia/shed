import ApplicationServices
import CoreGraphics
import Foundation

public struct WindowMoveTarget {
    public let id: WindowID?
    public let axElement: AXUIElement

    public init(id: WindowID?, axElement: AXUIElement) {
        self.id = id
        self.axElement = axElement
    }

    public init(window: WindowRef) {
        self.init(id: window.attributes.windowID, axElement: window.axElement)
    }
}

public actor WindowMover {
    private let client: AXWindowMoveClient
    private let frameDelayNanoseconds: UInt64
    private let retryDelayNanoseconds: UInt64
    private let maxRetries: Int
    private var pendingMoves: [WindowMoveKey: PendingMove] = [:]
    private var flushTask: Task<Void, Never>?

    public init(
        frameDelayNanoseconds: UInt64 = 16_666_667,
        retryDelayNanoseconds: UInt64 = 5_000_000,
        maxRetries: Int = 2
    ) {
        self.init(
            client: SystemAXWindowMoveClient(),
            frameDelayNanoseconds: frameDelayNanoseconds,
            retryDelayNanoseconds: retryDelayNanoseconds,
            maxRetries: maxRetries
        )
    }

    init(
        client: AXWindowMoveClient,
        frameDelayNanoseconds: UInt64 = 16_666_667,
        retryDelayNanoseconds: UInt64 = 5_000_000,
        maxRetries: Int = 2
    ) {
        self.client = client
        self.frameDelayNanoseconds = frameDelayNanoseconds
        self.retryDelayNanoseconds = retryDelayNanoseconds
        self.maxRetries = max(0, maxRetries)
    }

    public func setPosition(_ position: CGPoint, for window: WindowRef) {
        setPosition(position, for: WindowMoveTarget(window: window))
    }

    public func setPosition(_ position: CGPoint, for target: WindowMoveTarget) {
        var move = pendingMove(for: target)
        move.position = position
        pendingMoves[move.key] = move
        scheduleFlush()
    }

    public func setSize(_ size: CGSize, for window: WindowRef) {
        setSize(size, for: WindowMoveTarget(window: window))
    }

    public func setSize(_ size: CGSize, for target: WindowMoveTarget) {
        var move = pendingMove(for: target)
        move.size = size
        pendingMoves[move.key] = move
        scheduleFlush()
    }

    public func flushNow() async {
        flushTask?.cancel()
        flushTask = nil
        await flushPending()
    }

    private func pendingMove(for target: WindowMoveTarget) -> PendingMove {
        let key = WindowMoveKey(target: target)
        return pendingMoves[key] ?? PendingMove(key: key, target: target)
    }

    private func scheduleFlush() {
        guard flushTask == nil else {
            return
        }

        let delay = frameDelayNanoseconds
        flushTask = Task { [weak self] in
            try? await Task.sleep(nanoseconds: delay)
            await self?.flushPending()
        }
    }

    private func flushPending() async {
        let moves = pendingMoves.values.sorted { $0.key.rawValue < $1.key.rawValue }
        pendingMoves.removeAll(keepingCapacity: true)
        flushTask = nil

        for move in moves {
            await apply(move)
        }
    }

    private func apply(_ move: PendingMove) async {
        if let position = move.position {
            await writeWithRetry {
                client.setPosition(position, for: move.target.axElement)
            }
        }

        guard let size = move.size, client.isResizable(move.target.axElement) else {
            return
        }

        await writeWithRetry {
            client.setSize(size, for: move.target.axElement)
        }
    }

    @discardableResult
    private func writeWithRetry(_ operation: () -> AXError) async -> AXError {
        var lastError = AXError.success
        for attempt in 0...maxRetries {
            lastError = operation()
            if lastError == .success {
                return .success
            }
            if attempt < maxRetries, retryDelayNanoseconds > 0 {
                try? await Task.sleep(nanoseconds: retryDelayNanoseconds)
            }
        }
        return lastError
    }
}

private struct WindowMoveKey: Hashable {
    let rawValue: Int

    init(target: WindowMoveTarget) {
        if let id = target.id {
            self.rawValue = Int(id)
        } else {
            self.rawValue = Int(CFHash(target.axElement))
        }
    }
}

private struct PendingMove {
    let key: WindowMoveKey
    let target: WindowMoveTarget
    var position: CGPoint?
    var size: CGSize?
}

protocol AXWindowMoveClient: AnyObject {
    func isResizable(_ element: AXUIElement) -> Bool
    func setPosition(_ position: CGPoint, for element: AXUIElement) -> AXError
    func setSize(_ size: CGSize, for element: AXUIElement) -> AXError
}

private final class SystemAXWindowMoveClient: AXWindowMoveClient {
    func isResizable(_ element: AXUIElement) -> Bool {
        var value: CFTypeRef?
        let error = AXSignpost.interval("ax.read.resizable") {
            AXUIElementCopyAttributeValue(element, "AXResizable" as CFString, &value)
        }
        guard error == .success else {
            return true
        }
        return (value as? Bool) ?? true
    }

    func setPosition(_ position: CGPoint, for element: AXUIElement) -> AXError {
        var position = position
        guard let value = AXValueCreate(.cgPoint, &position) else {
            return .failure
        }
        return AXSignpost.interval("ax.write.position") {
            AXUIElementSetAttributeValue(element, kAXPositionAttribute as CFString, value)
        }
    }

    func setSize(_ size: CGSize, for element: AXUIElement) -> AXError {
        var size = size
        guard let value = AXValueCreate(.cgSize, &size) else {
            return .failure
        }
        return AXSignpost.interval("ax.write.size") {
            AXUIElementSetAttributeValue(element, kAXSizeAttribute as CFString, value)
        }
    }
}
