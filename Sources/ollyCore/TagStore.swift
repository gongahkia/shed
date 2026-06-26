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

public actor TagStore {
    private let defaultActiveTags: TagSet
    private let maxHistoryCount: Int
    private var statesByDisplayID: [DisplayID: DisplayTagState] = [:]

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
        var state = stateRef(for: displayID)
        state.activeTags = tags
        record(tags, in: &state)
        statesByDisplayID[displayID] = state
        return state
    }

    @discardableResult
    public func bindEngine(_ engineID: LayoutEngineID, to tag: Tag, on displayID: DisplayID) -> DisplayTagState {
        var state = stateRef(for: displayID)
        state.tagToEngine[tag] = engineID
        statesByDisplayID[displayID] = state
        return state
    }

    @discardableResult
    public func unbindEngine(for tag: Tag, on displayID: DisplayID) -> DisplayTagState {
        var state = stateRef(for: displayID)
        state.tagToEngine[tag] = nil
        statesByDisplayID[displayID] = state
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
        statesByDisplayID[displayID] = nil
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
}
