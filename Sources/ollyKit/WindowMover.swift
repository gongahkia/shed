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
    func frame(for element: AXUIElement) -> CGRect? {
        guard let position = pointAttribute(kAXPositionAttribute, from: element),
              let size = sizeAttribute(kAXSizeAttribute, from: element) else {
            return nil
        }
        return CGRect(origin: position, size: size)
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

    private func pointAttribute(_ attribute: String, from element: AXUIElement) -> CGPoint? {
        guard let value = axValueAttribute(attribute, from: element),
              AXValueGetType(value) == .cgPoint else {
            return nil
        }
        var point = CGPoint.zero
        guard AXValueGetValue(value, .cgPoint, &point) else {
            return nil
        }
        return point
    }

    private func sizeAttribute(_ attribute: String, from element: AXUIElement) -> CGSize? {
        guard let value = axValueAttribute(attribute, from: element),
              AXValueGetType(value) == .cgSize else {
            return nil
        }
        var size = CGSize.zero
        guard AXValueGetValue(value, .cgSize, &size) else {
            return nil
        }
        return size
    }

    private func axValueAttribute(_ attribute: String, from element: AXUIElement) -> AXValue? {
        var value: CFTypeRef?
        let error = AXSignpost.interval("ax.read.attribute") {
            AXUIElementCopyAttributeValue(element, attribute as CFString, &value)
        }
        guard error == .success, let value, CFGetTypeID(value) == AXValueGetTypeID() else {
            return nil
        }
        return unsafeBitCast(value, to: AXValue.self)
    }
}

private extension CGRect {
    func withOrigin(_ origin: CGPoint) -> CGRect {
        CGRect(origin: origin, size: size)
    }

    func withSize(_ size: CGSize) -> CGRect {
        CGRect(origin: origin, size: size)
    }
}

private extension CGPoint {
    func isWithin(_ threshold: CGFloat, of other: CGPoint) -> Bool {
        abs(x - other.x) < threshold && abs(y - other.y) < threshold
    }
}

private extension CGSize {
    func isWithin(_ threshold: CGFloat, of other: CGSize) -> Bool {
        abs(width - other.width) < threshold && abs(height - other.height) < threshold
    }
}
