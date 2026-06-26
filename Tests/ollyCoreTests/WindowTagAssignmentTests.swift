import CoreGraphics
import XCTest
import ollyKit
@testable import ollyCore

final class WindowTagAssignmentTests: XCTestCase {
    func testAssignReplacesWindowTags() async throws {
        let one = try Tag(index: 1)
        let two = try Tag(index: 2)
        let store = WindowStore()
        let assignment = WindowTagAssignment(windowStore: store)
        await store.upsert(window(id: 1, tagMask: TagSet(one).rawValue))

        let updated = try await assignment.assign(window: 1, tags: TagSet(two))
        let storedTagMask = await store.state(for: 1)?.tagMask

        XCTAssertEqual(updated.tagMask, TagSet(two).rawValue)
        XCTAssertEqual(storedTagMask, TagSet(two).rawValue)
    }

    func testToggleAddsAndRemovesTag() async throws {
        let one = try Tag(index: 1)
        let two = try Tag(index: 2)
        let store = WindowStore()
        let assignment = WindowTagAssignment(windowStore: store)
        await store.upsert(window(id: 1, tagMask: TagSet(one).rawValue))

        let added = try await assignment.toggle(window: 1, tag: two)
        let removed = try await assignment.toggle(window: 1, tag: one)

        XCTAssertEqual(added.tagMask, TagSet([one, two]).rawValue)
        XCTAssertEqual(removed.tagMask, TagSet(two).rawValue)
    }

    func testMoveChangesDisplayOnly() async throws {
        let one = try Tag(index: 1)
        let store = WindowStore()
        let assignment = WindowTagAssignment(windowStore: store)
        await store.upsert(window(id: 1, displayID: 1, tagMask: TagSet(one).rawValue))

        let updated = try await assignment.move(window: 1, toDisplay: 9)

        XCTAssertEqual(updated.displayID, 9)
        XCTAssertEqual(updated.tagMask, TagSet(one).rawValue)
    }

    func testMissingWindowThrows() async {
        let store = WindowStore()
        let assignment = WindowTagAssignment(windowStore: store)

        do {
            _ = try await assignment.move(window: 404, toDisplay: 1)
            XCTFail("expected missing window error")
        } catch WindowTagAssignmentError.windowNotFound(404) {
        } catch {
            XCTFail("unexpected error: \(error)")
        }
    }

    private func window(
        id: WindowID,
        displayID: DisplayID = 1,
        tagMask: UInt64 = 0
    ) -> WindowState {
        WindowState(
            id: id,
            processID: 42,
            displayID: displayID,
            tagMask: tagMask,
            frame: CGRect(x: 0, y: 0, width: 100, height: 100),
            title: "window",
            role: "AXWindow",
            subrole: "AXStandardWindow"
        )
    }
}
