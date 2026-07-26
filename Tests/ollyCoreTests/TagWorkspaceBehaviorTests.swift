import CoreGraphics
import XCTest
import ollyKit
@testable import ollyCore

final class TagWorkspaceBehaviorTests: XCTestCase {
    func testTagToggleDoesNotResizeVisibleWindow() async throws {
        let one = try Tag(index: 1)
        let two = try Tag(index: 2)
        let frame = CGRect(x: 10, y: 20, width: 640, height: 480)
        let windowStore = WindowStore()
        let tagStore = TagStore(defaultActiveTags: TagSet(one))
        let assignment = WindowTagAssignment(windowStore: windowStore)
        let recorder = WorkspaceMoveRecorder()
        let dispatcher = TagDispatcher(windowStore: windowStore, tagStore: tagStore) { window, frame in
            await recorder.record(windowID: window.id, frame: frame)
        }
        await windowStore.upsert(window(id: 1, tagMask: TagSet(one).rawValue, frame: frame))

        let initialMoves = await dispatcher.apply(displayID: 1)
        let updated = try await assignment.toggle(window: 1, tag: two)
        let postToggleMoves = await dispatcher.apply(displayID: 1)
        let storedWindow = await windowStore.state(for: 1)
        let recordedWindowIDs = await recorder.windowIDs

        XCTAssertTrue(initialMoves.isEmpty)
        XCTAssertEqual(updated.frame.size, frame.size)
        XCTAssertEqual(storedWindow?.frame.size, frame.size)
        XCTAssertTrue(postToggleMoves.isEmpty)
        XCTAssertTrue(recordedWindowIDs.isEmpty)
    }

    func testHiddenWindowsDoNotStealFocus() async throws {
        let active = try Tag(index: 1)
        let hidden = try Tag(index: 2)
        let windowStore = WindowStore()
        let focusStack = FocusStack()
        await windowStore.upsert(window(id: 1, tagMask: TagSet(active).rawValue))
        await windowStore.upsert(window(id: 2, tagMask: TagSet(hidden).rawValue))
        await focusStack.recordFocus(windowID: 1, displayID: 1, tagMask: TagSet(active).rawValue)
        await focusStack.recordFocus(windowID: 2, displayID: 1, tagMask: TagSet(active).rawValue)

        let visibleWindowIDs = Set(
            await windowStore.windows(onDisplay: 1)
                .filter { TagSet(rawValue: $0.tagMask).intersects(TagSet(active)) }
                .map(\.id)
        )
        var focusedWindowIDs: [WindowID] = []
        let restored = await focusStack.restoreFocus(
            displayID: 1,
            tagMask: TagSet(active).rawValue,
            availableWindows: visibleWindowIDs
        ) { windowID in
            focusedWindowIDs.append(windowID)
            return true
        }

        XCTAssertEqual(restored, 1)
        XCTAssertEqual(focusedWindowIDs, [1])
    }

    func testDispatcherIsIdempotentUnderRandomSequences() async throws {
        var generator = DeterministicGenerator(seed: 0x0A11_2026)

        for sequence in 0..<100 {
            let windowStore = WindowStore()
            let activeTags = try randomTagSet(using: &generator)
            let tagStore = TagStore(defaultActiveTags: activeTags)
            let dispatcher = TagDispatcher(windowStore: windowStore, tagStore: tagStore) { _, _ in }

            for id in 1...6 {
                let frame = CGRect(
                    x: CGFloat(id * 10),
                    y: CGFloat(id * 10),
                    width: CGFloat(200 + id),
                    height: CGFloat(160 + id)
                )
                let tags = try randomTagSet(using: &generator)
                await windowStore.upsert(window(id: WindowID(id), tagMask: tags.rawValue, frame: frame))
            }

            for step in 0..<10 {
                let tags = try randomTagSet(using: &generator)
                await tagStore.setActiveTags(tags, on: 1)
                _ = await dispatcher.apply(displayID: 1)
                let repeatedMoves = await dispatcher.apply(displayID: 1)

                XCTAssertTrue(repeatedMoves.isEmpty, "sequence \(sequence), step \(step)")
            }
        }
    }

    private func randomTagSet(using generator: inout DeterministicGenerator) throws -> TagSet {
        var tags = TagSet()
        for index in 0..<4 where generator.nextBool() {
            try tags.insert(Tag(index: index))
        }
        return tags
    }

    private func window(
        id: WindowID,
        tagMask: UInt64,
        frame: CGRect = CGRect(x: 0, y: 0, width: 100, height: 100)
    ) -> WindowState {
        WindowState(id: id, processID: 42, displayID: 1, tagMask: tagMask, frame: frame)
    }
}

private actor WorkspaceMoveRecorder {
    private(set) var moves: [(windowID: WindowID, frame: CGRect)] = []

    var windowIDs: [WindowID] {
        moves.map(\.windowID)
    }

    func record(windowID: WindowID, frame: CGRect) {
        moves.append((windowID, frame))
    }
}

private struct DeterministicGenerator {
    private var state: UInt64

    init(seed: UInt64) {
        self.state = seed
    }

    mutating func nextBool() -> Bool {
        state = state &* 6_364_136_223_846_793_005 &+ 1
        return state & 1 == 0
    }
}
