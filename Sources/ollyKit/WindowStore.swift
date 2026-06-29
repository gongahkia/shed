import CoreGraphics
import Foundation

public typealias DisplayID = CGDirectDisplayID

public struct WindowState: Equatable, Sendable {
    public let id: WindowID
    public let processID: pid_t
    public let bundleID: String?
    public let displayID: DisplayID?
    public let tagMask: UInt64
    public let isFloating: Bool
    public let layoutOrder: Int?
    public let frame: CGRect
    public let title: String?
    public let role: String?
    public let subrole: String?

    public init(
        id: WindowID,
        processID: pid_t,
        bundleID: String? = nil,
        displayID: DisplayID? = nil,
        tagMask: UInt64 = 0,
        isFloating: Bool = false,
        layoutOrder: Int? = nil,
        frame: CGRect,
        title: String? = nil,
        role: String? = nil,
        subrole: String? = nil
    ) {
        self.id = id
        self.processID = processID
        self.bundleID = bundleID
        self.displayID = displayID
        self.tagMask = tagMask
        self.isFloating = isFloating
        self.layoutOrder = layoutOrder
        self.frame = frame
        self.title = title
        self.role = role
        self.subrole = subrole
    }

    public init(
        id: WindowID,
        processID: pid_t,
        bundleID: String? = nil,
        displayID: DisplayID? = nil,
        tagMask: UInt64 = 0,
        isFloating: Bool = false,
        frame: CGRect,
        title: String? = nil,
        role: String? = nil,
        subrole: String? = nil
    ) {
        self.init(
            id: id,
            processID: processID,
            bundleID: bundleID,
            displayID: displayID,
            tagMask: tagMask,
            isFloating: isFloating,
            layoutOrder: nil,
            frame: frame,
            title: title,
            role: role,
            subrole: subrole
        )
    }

    public func withLayoutOrder(_ layoutOrder: Int?) -> WindowState {
        WindowState(
            id: id,
            processID: processID,
            bundleID: bundleID,
            displayID: displayID,
            tagMask: tagMask,
            isFloating: isFloating,
            layoutOrder: layoutOrder,
            frame: frame,
            title: title,
            role: role,
            subrole: subrole
        )
    }

    public func withFloating(_ isFloating: Bool) -> WindowState {
        WindowState(
            id: id,
            processID: processID,
            bundleID: bundleID,
            displayID: displayID,
            tagMask: tagMask,
            isFloating: isFloating,
            layoutOrder: layoutOrder,
            frame: frame,
            title: title,
            role: role,
            subrole: subrole
        )
    }

    public func withDisplayID(_ displayID: DisplayID?) -> WindowState {
        WindowState(
            id: id,
            processID: processID,
            bundleID: bundleID,
            displayID: displayID,
            tagMask: tagMask,
            isFloating: isFloating,
            layoutOrder: layoutOrder,
            frame: frame,
            title: title,
            role: role,
            subrole: subrole
        )
    }
}

public enum WindowStoreDelta: Equatable, Sendable {
    case added(WindowState)
    case updated(previous: WindowState, current: WindowState)
    case removed(WindowState)
}

public actor WindowStore {
    private var windowsByID: [WindowID: WindowState] = [:]
    private var idsByProcessID: [pid_t: Set<WindowID>] = [:]
    private var idsByDisplayID: [DisplayID: Set<WindowID>] = [:]
    private var idsByTagIndex: [UInt8: Set<WindowID>] = [:]
    private var subscribers: [UUID: AsyncStream<WindowStoreDelta>.Continuation] = [:]

    public init() {}

    public var count: Int {
        windowsByID.count
    }

    public func allWindows() -> [WindowState] {
        windowsByID.values.sorted(by: Self.precedes)
    }

    public func state(for id: WindowID) -> WindowState? {
        windowsByID[id]
    }

    public func windows(forProcessID processID: pid_t) -> [WindowState] {
        states(for: idsByProcessID[processID])
    }

    public func windows(onDisplay displayID: DisplayID) -> [WindowState] {
        states(for: idsByDisplayID[displayID])
    }

    public func windows(withTagIndex tagIndex: UInt8) -> [WindowState] {
        states(for: idsByTagIndex[tagIndex])
    }

    public func windows(intersectingTagMask tagMask: UInt64) -> [WindowState] {
        let ids = Self.tagIndices(in: tagMask).reduce(into: Set<WindowID>()) { result, tagIndex in
            result.formUnion(idsByTagIndex[tagIndex] ?? [])
        }
        return states(for: ids)
    }

    @discardableResult
    public func upsert(_ state: WindowState) -> WindowStoreDelta {
        let delta: WindowStoreDelta
        if let previous = windowsByID[state.id] {
            delta = .updated(previous: previous, current: state)
        } else {
            delta = .added(state)
        }

        windowsByID[state.id] = state
        rebuildIndexes()
        publish(delta)
        return delta
    }

    @discardableResult
    public func remove(id: WindowID) -> WindowStoreDelta? {
        guard let removed = windowsByID.removeValue(forKey: id) else {
            return nil
        }

        rebuildIndexes()
        let delta = WindowStoreDelta.removed(removed)
        publish(delta)
        return delta
    }

    public func deltas() -> AsyncStream<WindowStoreDelta> {
        let id = UUID()
        var capturedContinuation: AsyncStream<WindowStoreDelta>.Continuation?
        let stream = AsyncStream<WindowStoreDelta>(bufferingPolicy: .bufferingNewest(256)) { continuation in
            capturedContinuation = continuation
            continuation.onTermination = { [weak self] _ in
                Task {
                    await self?.removeSubscriber(id: id)
                }
            }
        }

        if let capturedContinuation {
            subscribers[id] = capturedContinuation
        }
        return stream
    }

    private func states(for ids: Set<WindowID>?) -> [WindowState] {
        (ids ?? []).compactMap { windowsByID[$0] }.sorted(by: Self.precedes)
    }

    private func publish(_ delta: WindowStoreDelta) {
        for subscriber in subscribers.values {
            subscriber.yield(delta)
        }
    }

    private func removeSubscriber(id: UUID) {
        subscribers[id] = nil
    }

    private func rebuildIndexes() {
        idsByProcessID.removeAll(keepingCapacity: true)
        idsByDisplayID.removeAll(keepingCapacity: true)
        idsByTagIndex.removeAll(keepingCapacity: true)

        for state in windowsByID.values {
            idsByProcessID[state.processID, default: []].insert(state.id)
            if let displayID = state.displayID {
                idsByDisplayID[displayID, default: []].insert(state.id)
            }
            for tagIndex in Self.tagIndices(in: state.tagMask) {
                idsByTagIndex[tagIndex, default: []].insert(state.id)
            }
        }
    }

    private static func tagIndices(in tagMask: UInt64) -> [UInt8] {
        (0..<64).compactMap { index in
            let bit = UInt64(1) << UInt64(index)
            return tagMask & bit == 0 ? nil : UInt8(index)
        }
    }

    private static func precedes(_ lhs: WindowState, _ rhs: WindowState) -> Bool {
        let lhsOrder = lhs.layoutOrder ?? Int(lhs.id)
        let rhsOrder = rhs.layoutOrder ?? Int(rhs.id)
        guard lhsOrder == rhsOrder else {
            return lhsOrder < rhsOrder
        }
        return lhs.id < rhs.id
    }
}
