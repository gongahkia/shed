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
        let dispatcher = emptyDisplayDispatcher(windowStore: windowStore, tagStore: tagStore, recorder: recorder)

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
        let dispatcher = emptyDisplayDispatcher(windowStore: windowStore, tagStore: tagStore, recorder: recorder)
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
        let dispatcher = emptyDisplayDispatcher(windowStore: windowStore, tagStore: tagStore, recorder: recorder)

        await windowStore.upsert(window(id: 1, displayID: 1, tagMask: TagSet(one).rawValue))
        await windowStore.upsert(window(id: 2, displayID: 2, tagMask: TagSet(one).rawValue))

        let moves = await dispatcher.apply(displayID: 2)
        let recordedWindowIDs = await recorder.windowIDs

        XCTAssertEqual(moves.map(\.windowID), [2])
        XCTAssertEqual(recordedWindowIDs, [2])
    }

    func testApplyDoesNotParkWindowWhenTagIsActiveOnAnotherDisplay() async throws {
        let one = try Tag(index: 1)
        let two = try Tag(index: 2)
        let windowStore = WindowStore()
        let tagStore = TagStore(defaultActiveTags: [])
        let recorder = TagMoveRecorder()
        let dispatcher = emptyDisplayDispatcher(windowStore: windowStore, tagStore: tagStore, recorder: recorder)

        await tagStore.setActiveTags(TagSet(one), on: 1)
        await tagStore.setActiveTags(TagSet(two), on: 2)
        await windowStore.upsert(window(id: 1, displayID: 2, tagMask: TagSet(one).rawValue))

        let moves = await dispatcher.apply(displayID: 2)
        let recordedWindowIDs = await recorder.windowIDs

        XCTAssertTrue(moves.isEmpty)
        XCTAssertTrue(recordedWindowIDs.isEmpty)

        await tagStore.setActiveTags(TagSet(two), on: 1)
        let hiddenMoves = await dispatcher.apply(displayID: 2)

        XCTAssertEqual(hiddenMoves.map(\.windowID), [1])
        XCTAssertEqual(hiddenMoves.first?.reason, .hide)
    }

    func testApplyDoesNotHideStickyWindowWithoutActiveTags() async throws {
        let active = try Tag(index: 0)
        let windowStore = WindowStore()
        let tagStore = TagStore(defaultActiveTags: TagSet(active))
        let recorder = TagMoveRecorder()
        let dispatcher = emptyDisplayDispatcher(windowStore: windowStore, tagStore: tagStore, recorder: recorder)

        await windowStore.upsert(window(id: 1, displayID: 7, tagMask: 0, isSticky: true))

        let moves = await dispatcher.apply(displayID: 7)
        let recordedWindowIDs = await recorder.windowIDs

        XCTAssertTrue(moves.isEmpty)
        XCTAssertTrue(recordedWindowIDs.isEmpty)
    }

    func testApplyDoesNotHideFullscreenWindowWithoutActiveTags() async throws {
        let active = try Tag(index: 0)
        let inactive = try Tag(index: 1)
        let windowStore = WindowStore()
        let tagStore = TagStore(defaultActiveTags: TagSet(active))
        let recorder = TagMoveRecorder()
        let dispatcher = emptyDisplayDispatcher(windowStore: windowStore, tagStore: tagStore, recorder: recorder)

        await windowStore.upsert(window(
            id: 1,
            displayID: 7,
            tagMask: TagSet(inactive).rawValue,
            isFullscreen: true
        ))

        let moves = await dispatcher.apply(displayID: 7)
        let recordedWindowIDs = await recorder.windowIDs

        XCTAssertTrue(moves.isEmpty)
        XCTAssertTrue(recordedWindowIDs.isEmpty)
    }

    func testApplySkipsOffSpaceWindow() async throws {
        let active = try Tag(index: 0)
        let inactive = try Tag(index: 1)
        let windowStore = WindowStore()
        let tagStore = TagStore(defaultActiveTags: TagSet(active))
        let recorder = TagMoveRecorder()
        let dispatcher = emptyDisplayDispatcher(windowStore: windowStore, tagStore: tagStore, recorder: recorder)

        await windowStore.upsert(window(
            id: 1,
            displayID: 7,
            tagMask: TagSet(inactive).rawValue,
            isOffSpace: true
        ))

        let moves = await dispatcher.apply(displayID: 7)
        let recordedWindowIDs = await recorder.windowIDs

        XCTAssertTrue(moves.isEmpty)
        XCTAssertTrue(recordedWindowIDs.isEmpty)
    }

    func testApplyParksInactiveWindowOutsideProvidedDisplays() async throws {
        let active = try Tag(index: 0)
        let inactive = try Tag(index: 1)
        let display = display(frame: CGRect(x: -33_000, y: -33_000, width: 4_000, height: 4_000))
        let windowStore = WindowStore()
        let tagStore = TagStore(defaultActiveTags: TagSet(active))
        let recorder = TagMoveRecorder()
        let dispatcher = TagDispatcher(
            windowStore: windowStore,
            tagStore: tagStore,
            displayProvider: { [display] }
        ) { window, frame in
            await recorder.record(windowID: window.id, frame: frame)
        }

        await windowStore.upsert(window(id: 1, displayID: display.id, tagMask: TagSet(inactive).rawValue))

        let moves = await dispatcher.apply(displayID: display.id)
        let move = try XCTUnwrap(moves.first)

        XCTAssertTrue(OffscreenParking.default.isOffscreen(move.targetFrame, avoiding: [display]))
        XCTAssertLessThan(move.targetFrame.maxX, display.frame.minX)
        XCTAssertLessThan(move.targetFrame.maxY, display.frame.minY)
    }

    private func window(
        id: WindowID,
        displayID: DisplayID,
        tagMask: UInt64,
        isSticky: Bool = false,
        isFullscreen: Bool = false,
        isOffSpace: Bool = false,
        frame: CGRect = CGRect(x: 0, y: 0, width: 100, height: 100)
    ) -> WindowState {
        WindowState(
            id: id,
            processID: 42,
            displayID: displayID,
            tagMask: tagMask,
            isSticky: isSticky,
            isFullscreen: isFullscreen,
            isOffSpace: isOffSpace,
            frame: frame
        )
    }

    private func emptyDisplayDispatcher(
        windowStore: WindowStore,
        tagStore: TagStore,
        recorder: TagMoveRecorder
    ) -> TagDispatcher {
        TagDispatcher(windowStore: windowStore, tagStore: tagStore, displayProvider: { [] }) { window, frame in
            await recorder.record(windowID: window.id, frame: frame)
        }
    }

    private func display(frame: CGRect) -> Display {
        Display(
            id: 7,
            frame: frame,
            visibleFrame: frame,
            scaleFactor: 1,
            localizedName: "Test",
            isMain: true
        )
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
