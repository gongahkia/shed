import CoreGraphics
import ollyKit

public enum WindowTagAssignmentError: Error, Equatable, Sendable {
    case windowNotFound(WindowID)
}

public actor WindowTagAssignment {
    private let windowStore: WindowStore

    public init(windowStore: WindowStore) {
        self.windowStore = windowStore
    }

    @discardableResult
    public func assign(window windowID: WindowID, tags: TagSet) async throws -> WindowState {
        let state = try await state(for: windowID)
        let updated = copy(state, tagMask: tags.rawValue)
        await windowStore.upsert(updated)
        return updated
    }

    @discardableResult
    public func toggle(window windowID: WindowID, tag: Tag) async throws -> WindowState {
        let state = try await state(for: windowID)
        let tags = TagSet(rawValue: state.tagMask)
        let updatedTags = tags.contains(tag) ? tags.removing(tag) : tags.inserting(tag)
        let updated = copy(state, tagMask: updatedTags.rawValue)
        await windowStore.upsert(updated)
        return updated
    }

    @discardableResult
    public func move(window windowID: WindowID, toDisplay displayID: DisplayID) async throws -> WindowState {
        let state = try await state(for: windowID)
        let updated = copy(state, displayID: displayID)
        await windowStore.upsert(updated)
        return updated
    }

    private func state(for windowID: WindowID) async throws -> WindowState {
        guard let state = await windowStore.state(for: windowID) else {
            throw WindowTagAssignmentError.windowNotFound(windowID)
        }
        return state
    }

    private func copy(
        _ state: WindowState,
        tagMask: UInt64? = nil,
        displayID: DisplayID? = nil
    ) -> WindowState {
        WindowState(
            id: state.id,
            processID: state.processID,
            bundleID: state.bundleID,
            displayID: displayID ?? state.displayID,
            tagMask: tagMask ?? state.tagMask,
            isFloating: state.isFloating,
            frame: state.frame,
            title: state.title,
            role: state.role,
            subrole: state.subrole
        )
    }
}
