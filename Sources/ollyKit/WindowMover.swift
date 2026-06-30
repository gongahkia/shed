import ApplicationServices
import CoreGraphics
import Foundation

public struct WindowMoveTarget {
    public let id: WindowID?
    public let axElement: AXUIElement
    public let displayID: DisplayID?

    public init(id: WindowID?, axElement: AXUIElement, displayID: DisplayID? = nil) {
        self.id = id
        self.axElement = axElement
        self.displayID = displayID
    }

    public init(window: WindowRef, displayID: DisplayID? = nil) {
        self.init(id: window.attributes.windowID, axElement: window.axElement, displayID: displayID)
    }

    public func withFallbackDisplayID(_ displayID: DisplayID?) -> WindowMoveTarget {
        WindowMoveTarget(id: id, axElement: axElement, displayID: self.displayID ?? displayID)
    }
}

public actor WindowMover {
    private static let defaultFrameDelayNanoseconds: UInt64 = 16_666_667

    private let client: AXWindowMoveClient
    private let displayMonitor: DisplayMonitor
    private let usesDisplayLink: Bool
    private let frameDelayNanoseconds: UInt64
    private let retryDelayNanoseconds: UInt64
    private let maxRetries: Int
    private let noOpThreshold: CGFloat
    private var displayLinkCoordinator: DisplayLinkFlushCoordinator?
    private var pendingMoves: [DisplayID: [WindowMoveKey: PendingMove]] = [:]
    private var flushTasks: [DisplayID: Task<Void, Never>] = [:]
    private var lastFrames: [WindowMoveKey: CGRect] = [:]
    private var isPaused = false
    private var axErrorHandler: (@Sendable (AXError) -> Void)?

    public init(
        frameDelayNanoseconds: UInt64 = 16_666_667,
        retryDelayNanoseconds: UInt64 = 5_000_000,
        maxRetries: Int = 2,
        noOpThreshold: CGFloat = 1
    ) {
        let displayMonitor = DisplayMonitor()
        self.init(
            client: SystemAXWindowMoveClient(),
            displayMonitor: displayMonitor,
            frameDelayNanoseconds: frameDelayNanoseconds,
            retryDelayNanoseconds: retryDelayNanoseconds,
            maxRetries: maxRetries,
            noOpThreshold: noOpThreshold
        )
    }

    init(
        client: AXWindowMoveClient,
        displayMonitor: DisplayMonitor = DisplayMonitor(),
        frameDelayNanoseconds: UInt64 = 16_666_667,
        retryDelayNanoseconds: UInt64 = 5_000_000,
        maxRetries: Int = 2,
        noOpThreshold: CGFloat = 1
    ) {
        self.client = client
        self.displayMonitor = displayMonitor
        self.frameDelayNanoseconds = frameDelayNanoseconds
        self.retryDelayNanoseconds = retryDelayNanoseconds
        self.maxRetries = max(0, maxRetries)
        self.noOpThreshold = noOpThreshold
        self.usesDisplayLink = frameDelayNanoseconds == Self.defaultFrameDelayNanoseconds
        self.displayLinkCoordinator = nil
    }

    public func setPosition(_ position: CGPoint, for window: WindowRef) {
        setPosition(position, for: WindowMoveTarget(window: window))
    }

    public func setPosition(_ position: CGPoint, for target: WindowMoveTarget) {
        guard !isPaused else {
            return
        }
        let key = WindowMoveKey(target: target)
        var move = pendingMove(for: key) ?? PendingMove(key: key, target: target)
        move.position = position
        let displayID = target.displayID
            ?? displayID(containing: position)
            ?? pendingDisplayID(for: key)
            ?? defaultDisplayID()
        store(move, on: displayID)
        scheduleFlush(for: displayID)
    }

    public func setSize(_ size: CGSize, for window: WindowRef) {
        setSize(size, for: WindowMoveTarget(window: window))
    }

    public func setSize(_ size: CGSize, for target: WindowMoveTarget) {
        guard !isPaused else {
            return
        }
        let key = WindowMoveKey(target: target)
        var move = pendingMove(for: key) ?? PendingMove(key: key, target: target)
        move.size = size
        let displayID = target.displayID ?? pendingDisplayID(for: key) ?? defaultDisplayID()
        store(move, on: displayID)
        scheduleFlush(for: displayID)
    }

    public func flushNow() async {
        flushTasks.values.forEach { $0.cancel() }
        flushTasks.removeAll()
        for displayID in pendingMoves.keys.sorted() {
            await flushPending(displayID: displayID)
        }
    }

    public func flushAndPause() async {
        isPaused = true
        flushTasks.values.forEach { $0.cancel() }
        flushTasks.removeAll()
        let moves = pendingMoves
            .flatMap { $0.value.values }
            .sorted { $0.key.rawValue < $1.key.rawValue }
        pendingMoves.removeAll()
        for move in moves {
            await apply(move)
        }
    }

    public func resume() {
        isPaused = false
    }

    public func setAXErrorHandler(_ handler: (@Sendable (AXError) -> Void)?) {
        axErrorHandler = handler
    }

    public func lastFrame(for target: WindowMoveTarget) -> CGRect? {
        lastFrames[WindowMoveKey(target: target)]
    }

    private func pendingMove(for key: WindowMoveKey) -> PendingMove? {
        for moves in pendingMoves.values {
            if let move = moves[key] {
                return move
            }
        }
        return nil
    }

    private func pendingDisplayID(for key: WindowMoveKey) -> DisplayID? {
        for (displayID, moves) in pendingMoves where moves[key] != nil {
            return displayID
        }
        return nil
    }

    private func store(_ move: PendingMove, on displayID: DisplayID) {
        for existingDisplayID in pendingMoves.keys where existingDisplayID != displayID {
            pendingMoves[existingDisplayID]?.removeValue(forKey: move.key)
            if pendingMoves[existingDisplayID]?.isEmpty == true {
                pendingMoves.removeValue(forKey: existingDisplayID)
            }
        }
        pendingMoves[displayID, default: [:]][move.key] = move
    }

    private func scheduleFlush(for displayID: DisplayID) {
        if usesDisplayLink {
            let displayLinkCoordinator = activeDisplayLinkCoordinator()
            Task { @MainActor in
                displayLinkCoordinator.requestFlush(displayID: displayID)
            }
            return
        }

        guard flushTasks[displayID] == nil else {
            return
        }
        let delay = frameDelayNanoseconds
        flushTasks[displayID] = Task { [weak self] in
            try? await Task.sleep(nanoseconds: delay)
            guard !Task.isCancelled else {
                return
            }
            await self?.flushPending(displayID: displayID)
        }
    }

    private func activeDisplayLinkCoordinator() -> DisplayLinkFlushCoordinator {
        if let displayLinkCoordinator {
            return displayLinkCoordinator
        }
        let displayLinkCoordinator = DisplayLinkFlushCoordinator(mover: self, displayMonitor: displayMonitor)
        self.displayLinkCoordinator = displayLinkCoordinator
        return displayLinkCoordinator
    }

    func flushPending(displayID: DisplayID) async {
        let moves = (pendingMoves.removeValue(forKey: displayID) ?? [:])
            .values
            .sorted { $0.key.rawValue < $1.key.rawValue }
        flushTasks[displayID] = nil

        for move in moves {
            await apply(move)
        }
    }

    private func apply(_ move: PendingMove) async {
        await PerformanceSignpost.interval("ax.write") {
            await applyWrites(move)
        }
    }

    private func applyWrites(_ move: PendingMove) async {
        var frame = client.frame(for: move.target.axElement) ?? lastFrames[move.key]

        if let position = move.position {
            if frame?.origin.isWithin(noOpThreshold, of: position) != true {
                let result = await writeWithRetry {
                    client.setPosition(position, for: move.target.axElement)
                }
                if result == .success {
                    frame = (frame ?? CGRect(origin: position, size: .zero)).withOrigin(position)
                } else {
                    reportAXError(result)
                }
            }
        }

        guard let size = move.size else {
            if let frame {
                lastFrames[move.key] = frame
            }
            return
        }

        guard client.isResizable(move.target.axElement) else {
            if let frame {
                lastFrames[move.key] = frame
            }
            return
        }

        if frame?.size.isWithin(noOpThreshold, of: size) != true {
            let result = await writeWithRetry {
                client.setSize(size, for: move.target.axElement)
            }
            if result == .success {
                frame = (frame ?? CGRect(origin: .zero, size: size)).withSize(size)
            } else {
                reportAXError(result)
            }
        }

        if let frame {
            lastFrames[move.key] = frame
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

    private func reportAXError(_ error: AXError) {
        guard error.isAXPermissionRevocationSignal else {
            return
        }
        axErrorHandler?(error)
    }

    private func displayID(containing point: CGPoint) -> DisplayID? {
        displayMonitor.displays().first { $0.frame.contains(point) }?.id
    }

    private func defaultDisplayID() -> DisplayID {
        let displays = displayMonitor.displays()
        return displays.first { $0.isMain }?.id ?? displays.first?.id ?? CGMainDisplayID()
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
    func frame(for element: AXUIElement) -> CGRect?
    func isResizable(_ element: AXUIElement) -> Bool
    func setPosition(_ position: CGPoint, for element: AXUIElement) -> AXError
    func setSize(_ size: CGSize, for element: AXUIElement) -> AXError
}

private final class SystemAXWindowMoveClient: AXWindowMoveClient {
    private let frameReader = AXFrameReader()

    func frame(for element: AXUIElement) -> CGRect? {
        frameReader.frame(for: element)
    }

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
