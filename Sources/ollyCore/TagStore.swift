import Foundation
import ollyKit

public struct LayoutEngineID: Codable, ExpressibleByStringLiteral, Hashable, RawRepresentable, Sendable {
    public let rawValue: String

    public init(rawValue: String) {
        precondition(!rawValue.isEmpty)
        self.rawValue = rawValue
    }

    public init(stringLiteral value: String) {
        self.init(rawValue: value)
    }
}

public struct DisplayTagState: Equatable, Sendable {
    public let displayID: DisplayID
    public var activeTags: TagSet
    public var tagToEngine: [Tag: LayoutEngineID]
    public var mruHistory: [TagSet]

    public init(
        displayID: DisplayID,
        activeTags: TagSet = [],
        tagToEngine: [Tag: LayoutEngineID] = [:],
        mruHistory: [TagSet] = []
    ) {
        self.displayID = displayID
        self.activeTags = activeTags
        self.tagToEngine = tagToEngine
        self.mruHistory = mruHistory
    }
}

public enum TagStoreDelta: Equatable, Sendable {
    case updated(previous: DisplayTagState, current: DisplayTagState)
    case removed(DisplayTagState)

    public var displayID: DisplayID {
        switch self {
        case let .updated(_, current):
            return current.displayID
        case let .removed(state):
            return state.displayID
        }
    }
}

public actor TagStore {
    private let defaultActiveTags: TagSet
    private let maxHistoryCount: Int
    private var statesByDisplayID: [DisplayID: DisplayTagState] = [:]
    private var subscribers: [UUID: AsyncStream<TagStoreDelta>.Continuation] = [:]

    public init(defaultActiveTags: TagSet = [], maxHistoryCount: Int = 16) {
        self.defaultActiveTags = defaultActiveTags
        self.maxHistoryCount = max(1, maxHistoryCount)
    }

    public func allStates() -> [DisplayTagState] {
        statesByDisplayID.values.sorted { $0.displayID < $1.displayID }
    }

    public func state(for displayID: DisplayID) -> DisplayTagState {
        stateRef(for: displayID)
    }

    @discardableResult
    public func setActiveTags(_ tags: TagSet, on displayID: DisplayID) -> DisplayTagState {
        let previous = stateRef(for: displayID)
        var state = previous
        state.activeTags = tags
        record(tags, in: &state)
        statesByDisplayID[displayID] = state
        publishUpdate(previous: previous, current: state)
        return state
    }

    @discardableResult
    public func bindEngine(_ engineID: LayoutEngineID, to tag: Tag, on displayID: DisplayID) -> DisplayTagState {
        let previous = stateRef(for: displayID)
        var state = previous
        state.tagToEngine[tag] = engineID
        statesByDisplayID[displayID] = state
        publishUpdate(previous: previous, current: state)
        return state
    }

    @discardableResult
    public func unbindEngine(for tag: Tag, on displayID: DisplayID) -> DisplayTagState {
        let previous = stateRef(for: displayID)
        var state = previous
        state.tagToEngine[tag] = nil
        statesByDisplayID[displayID] = state
        publishUpdate(previous: previous, current: state)
        return state
    }

    public func engine(for tag: Tag, on displayID: DisplayID) -> LayoutEngineID? {
        stateRef(for: displayID).tagToEngine[tag]
    }

    public func activeTags(on displayID: DisplayID) -> TagSet {
        stateRef(for: displayID).activeTags
    }

    public func mruHistory(on displayID: DisplayID) -> [TagSet] {
        stateRef(for: displayID).mruHistory
    }

    public func removeDisplay(_ displayID: DisplayID) {
        guard let state = statesByDisplayID.removeValue(forKey: displayID) else {
            return
        }
        publish(.removed(state))
    }

    public func deltas() -> AsyncStream<TagStoreDelta> {
        let id = UUID()
        var capturedContinuation: AsyncStream<TagStoreDelta>.Continuation?
        let stream = AsyncStream<TagStoreDelta>(bufferingPolicy: .bufferingNewest(256)) { continuation in
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

    private func stateRef(for displayID: DisplayID) -> DisplayTagState {
        if let state = statesByDisplayID[displayID] {
            return state
        }

        let history = defaultActiveTags.isEmpty ? [] : [defaultActiveTags]
        let state = DisplayTagState(
            displayID: displayID,
            activeTags: defaultActiveTags,
            mruHistory: history
        )
        statesByDisplayID[displayID] = state
        return state
    }

    private func record(_ tags: TagSet, in state: inout DisplayTagState) {
        state.mruHistory.removeAll { $0 == tags }
        if !tags.isEmpty {
            state.mruHistory.insert(tags, at: 0)
        }
        if state.mruHistory.count > maxHistoryCount {
            state.mruHistory.removeLast(state.mruHistory.count - maxHistoryCount)
        }
    }

    private func publishUpdate(previous: DisplayTagState, current: DisplayTagState) {
        guard previous != current else {
            return
        }
        publish(.updated(previous: previous, current: current))
    }

    private func publish(_ delta: TagStoreDelta) {
        for subscriber in subscribers.values {
            subscriber.yield(delta)
        }
    }

    private func removeSubscriber(id: UUID) {
        subscribers[id] = nil
    }
}
