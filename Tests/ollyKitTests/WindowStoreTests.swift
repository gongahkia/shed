import CoreGraphics
import XCTest
@testable import ollyKit

final class WindowStoreTests: XCTestCase {
    func testUpsertIndexesByProcessDisplayAndTags() async {
        let store = WindowStore()
        let state = WindowState(
            id: 7,
            processID: 42,
            displayID: 99,
            tagMask: 0b1010,
            frame: CGRect(x: 1, y: 2, width: 3, height: 4)
        )

        await store.upsert(state)

        let processWindows = await store.windows(forProcessID: 42)
        let displayWindows = await store.windows(onDisplay: 99)
        let tagOneWindows = await store.windows(withTagIndex: 1)
        let tagThreeWindows = await store.windows(withTagIndex: 3)
        let intersectingWindows = await store.windows(intersectingTagMask: 0b1000)
        let count = await store.count

        XCTAssertEqual(processWindows, [state])
        XCTAssertEqual(displayWindows, [state])
        XCTAssertEqual(tagOneWindows, [state])
        XCTAssertEqual(tagThreeWindows, [state])
        XCTAssertEqual(intersectingWindows, [state])
        XCTAssertEqual(count, 1)
    }

    func testRemoveEmitsDeltaAndUpdatesIndexes() async throws {
        let store = WindowStore()
        let state = WindowState(
            id: 7,
            processID: 42,
            displayID: 99,
            tagMask: 0b10,
            frame: CGRect(x: 1, y: 2, width: 3, height: 4)
        )
        var iterator = await store.deltas().makeAsyncIterator()

        await store.upsert(state)
        let addedDelta = await iterator.next()
        let removed = await store.remove(id: 7)
        let removedDelta = await iterator.next()

        XCTAssertEqual(addedDelta, .added(state))
        XCTAssertEqual(removed, .removed(state))
        XCTAssertEqual(removedDelta, .removed(state))
        let processWindows = await store.windows(forProcessID: 42)
        let count = await store.count
        XCTAssertEqual(processWindows, [])
        XCTAssertEqual(count, 0)
    }

    func testQueriesUseLayoutOrderBeforeWindowID() async {
        let store = WindowStore()
        await store.upsert(window(id: 20, layoutOrder: 2))
        await store.upsert(window(id: 10, layoutOrder: 0))
        await store.upsert(window(id: 30, layoutOrder: 1))

        let windows = await store.windows(onDisplay: 99)

        XCTAssertEqual(windows.map(\.id), [10, 30, 20])
    }

    func testWindowStateCopyHelpersPreserveFlags() {
        let state = WindowState(
            id: 7,
            processID: 42,
            displayID: 99,
            tagMask: 1,
            isFloating: true,
            isSticky: true,
            isPinned: false,
            engineOverride: LayoutEngineID(rawValue: "floating"),
            layoutOrder: 4,
            frame: CGRect(x: 0, y: 0, width: 100, height: 100)
        )

        XCTAssertTrue(state.withPinned(true).isSticky)
        XCTAssertTrue(state.withPinned(true).isPinned)
        XCTAssertTrue(state.withTagMask(2).isFloating)
        XCTAssertEqual(state.withTagMask(2).engineOverride, LayoutEngineID(rawValue: "floating"))
        XCTAssertEqual(state.withEngineOverride(nil).layoutOrder, 4)
        XCTAssertNil(state.withEngineOverride(nil).engineOverride)
        XCTAssertEqual(state.withTagMask(2).tagMask, 2)
        XCTAssertFalse(state.withSticky(false).isSticky)
    }

    private func window(id: WindowID, layoutOrder: Int) -> WindowState {
        WindowState(
            id: id,
            processID: 42,
            displayID: 99,
            tagMask: 0b1,
            isFloating: false,
            layoutOrder: layoutOrder,
            frame: CGRect(x: 0, y: 0, width: 100, height: 100)
        )
    }
}
