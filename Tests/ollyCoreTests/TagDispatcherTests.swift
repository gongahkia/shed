import CoreGraphics
import XCTest
import ollyKit
@testable import ollyCore

final class TagDispatcherTests: XCTestCase {
    func testApplyHidesInactiveTagsAndIsIdempotent() async throws {
        let active = try Tag(index: 0)
        let inactive = try Tag(index: 1)
        let windowStore = WindowStore()
        let tagStore = TagStore(defaultActiveTags: TagSet(active))
        let recorder = TagMoveRecorder()
        let dispatcher = TagDispatcher(windowStore: windowStore, tagStore: tagStore) { window, frame in
            await recorder.record(windowID: window.id, frame: frame)
        }

        await windowStore.upsert(window(id: 1, displayID: 7, tagMask: TagSet(active).rawValue))
        await windowStore.upsert(window(id: 2, displayID: 7, tagMask: TagSet(inactive).rawValue))

        let firstMoves = await dispatcher.apply(displayID: 7)
        let secondMoves = await dispatcher.apply(displayID: 7)
        let recordedWindowIDs = await recorder.windowIDs

        XCTAssertEqual(firstMoves.map(\.windowID), [2])
        XCTAssertEqual(firstMoves.first?.reason, .hide)
        XCTAssertEqual(firstMoves.first?.targetFrame.origin, TagDispatcher.defaultOffscreenOrigin)
        XCTAssertTrue(secondMoves.isEmpty)
        XCTAssertEqual(recordedWindowIDs, [2])
    }

    func testApplyShowsPreviouslyParkedWindowsWhenTagBecomesActive() async throws {
        let one = try Tag(index: 1)
        let two = try Tag(index: 2)
        let windowStore = WindowStore()
        let tagStore = TagStore(defaultActiveTags: TagSet(one))
        let recorder = TagMoveRecorder()
        let dispatcher = TagDispatcher(windowStore: windowStore, tagStore: tagStore) { window, frame in
            await recorder.record(windowID: window.id, frame: frame)
        }
        let firstFrame = CGRect(x: 10, y: 10, width: 100, height: 100)
        let secondFrame = CGRect(x: 20, y: 20, width: 200, height: 200)

        await windowStore.upsert(window(id: 1, displayID: 7, tagMask: TagSet(one).rawValue, frame: firstFrame))
        await windowStore.upsert(window(id: 2, displayID: 7, tagMask: TagSet(two).rawValue, frame: secondFrame))
        await dispatcher.apply(displayID: 7)
        await tagStore.setActiveTags(TagSet(two), on: 7)

        let moves = await dispatcher.apply(displayID: 7)
        let recordedWindowIDs = await recorder.windowIDs

        XCTAssertEqual(
            moves,
            [
                TagDispatchMove(
                    windowID: 1,
                    targetFrame: CGRect(origin: TagDispatcher.defaultOffscreenOrigin, size: firstFrame.size),
                    reason: .hide
                ),
                TagDispatchMove(windowID: 2, targetFrame: secondFrame, reason: .show)
            ]
        )
        XCTAssertEqual(recordedWindowIDs, [2, 1, 2])
    }

    func testApplyOnlyUsesTargetDisplay() async throws {
        let one = try Tag(index: 1)
        let windowStore = WindowStore()
        let tagStore = TagStore(defaultActiveTags: [])
        let recorder = TagMoveRecorder()
        let dispatcher = TagDispatcher(windowStore: windowStore, tagStore: tagStore) { window, frame in
            await recorder.record(windowID: window.id, frame: frame)
        }

        await windowStore.upsert(window(id: 1, displayID: 1, tagMask: TagSet(one).rawValue))
        await windowStore.upsert(window(id: 2, displayID: 2, tagMask: TagSet(one).rawValue))

        let moves = await dispatcher.apply(displayID: 2)
        let recordedWindowIDs = await recorder.windowIDs

        XCTAssertEqual(moves.map(\.windowID), [2])
        XCTAssertEqual(recordedWindowIDs, [2])
    }

    private func window(
        id: WindowID,
        displayID: DisplayID,
        tagMask: UInt64,
        frame: CGRect = CGRect(x: 0, y: 0, width: 100, height: 100)
    ) -> WindowState {
        WindowState(id: id, processID: 42, displayID: displayID, tagMask: tagMask, frame: frame)
    }
}

private actor TagMoveRecorder {
    private(set) var moves: [(windowID: WindowID, frame: CGRect)] = []

    var windowIDs: [WindowID] {
        moves.map(\.windowID)
    }

    func record(windowID: WindowID, frame: CGRect) {
        moves.append((windowID, frame))
    }
}
