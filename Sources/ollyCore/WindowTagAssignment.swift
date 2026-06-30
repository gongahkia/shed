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
        let updated = state.withTagMask(tags.rawValue)
        await windowStore.upsert(updated)
        return updated
    }

    @discardableResult
    public func toggle(window windowID: WindowID, tag: Tag) async throws -> WindowState {
        let state = try await state(for: windowID)
        let tags = TagSet(rawValue: state.tagMask)
        let updatedTags = tags.contains(tag) ? tags.removing(tag) : tags.inserting(tag)
        let updated = state.withTagMask(updatedTags.rawValue)
        await windowStore.upsert(updated)
        return updated
    }

    @discardableResult
    public func move(window windowID: WindowID, toDisplay displayID: DisplayID) async throws -> WindowState {
        let state = try await state(for: windowID)
        let updated = state.withDisplayID(displayID)
        await windowStore.upsert(updated)
        return updated
    }

    @discardableResult
    public func setSticky(window windowID: WindowID, sticky: Bool) async throws -> WindowState {
        let state = try await state(for: windowID)
        let updated = state.withSticky(sticky)
        await windowStore.upsert(updated)
        return updated
    }

    @discardableResult
    public func setPinned(window windowID: WindowID, pinned: Bool) async throws -> WindowState {
        let state = try await state(for: windowID)
        let updated = state.withPinned(pinned)
        await windowStore.upsert(updated)
        return updated
    }

    @discardableResult
    public func setFloating(window windowID: WindowID, floating: Bool) async throws -> WindowState {
        let state = try await state(for: windowID)
        let updated = state.withFloating(floating)
        await windowStore.upsert(updated)
        return updated
    }

    private func state(for windowID: WindowID) async throws -> WindowState {
        guard let state = await windowStore.state(for: windowID) else {
            throw WindowTagAssignmentError.windowNotFound(windowID)
        }
        return state
    }

}
