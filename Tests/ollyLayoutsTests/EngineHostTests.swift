import CoreGraphics
import XCTest
import ollyCore
import ollyKit
@testable import ollyLayouts

final class EngineHostTests: XCTestCase {
    func testArrangeInvokesBoundEngineAndDiffsPlacements() async throws {
        let tag = try Tag(index: 1)
        let engineID = LayoutEngineID(rawValue: "fixed")
        let windowStore = WindowStore()
        let tagStore = TagStore(defaultActiveTags: TagSet(tag))
        let registry = try LayoutEngineRegistry(factories: [AnyLayoutEngineFactory(FixedLayoutEngineFactory(id: engineID))])
        let recorder = EngineHostPlacementRecorder()
        let eventRecorder = EngineHostEventRecorder()
        let host = EngineHost(
            windowStore: windowStore,
            tagStore: tagStore,
            registry: registry,
            configProvider: { _ in FixedLayoutEngine.Config(width: 320) },
            applyPlacement: { window, placement in
                await recorder.record(windowID: window.id, placement: placement)
            },
            publishEvent: { event in
                await eventRecorder.record(event)
            }
        )
        await tagStore.bindEngine(engineID, to: tag, on: 1)
        await windowStore.upsert(window(id: 1, tagMask: TagSet(tag).rawValue))
        await windowStore.upsert(window(id: 2, tagMask: TagSet(tag).rawValue))

        let first = try await host.arrange(displayID: 1, bounds: CGRect(x: 0, y: 0, width: 800, height: 600))
        let second = try await host.arrange(displayID: 1, bounds: CGRect(x: 0, y: 0, width: 800, height: 600))
        let recordedWindowIDs = await recorder.windowIDs
        let events = await eventRecorder.events

        XCTAssertEqual(first.engineID, engineID)
        XCTAssertEqual(first.placements.map(\.windowID), [1, 2])
        XCTAssertEqual(first.appliedPlacements.map(\.windowID), [1, 2])
        XCTAssertEqual(
            first.events,
            [
                .arranged(
                    EngineArrangedEvent(
                        displayID: 1,
                        engineID: engineID,
                        placementCount: 2,
                        appliedPlacementCount: 2
                    )
                )
            ]
        )
        XCTAssertTrue(second.appliedPlacements.isEmpty)
        XCTAssertEqual(events.count, 2)
        XCTAssertEqual(recordedWindowIDs, [1, 2])
    }

    func testArrangeFallsBackToFloatingWhenNoEngineIsBound() async throws {
        let tag = try Tag(index: 1)
        let windowStore = WindowStore()
        let tagStore = TagStore(defaultActiveTags: TagSet(tag))
        let registry = try LayoutEngineRegistry()
        let host = EngineHost(
            windowStore: windowStore,
            tagStore: tagStore,
            registry: registry,
            configProvider: { _ in nil },
            applyPlacement: { _, _ in }
        )
        let frame = CGRect(x: 10, y: 20, width: 300, height: 200)
        await windowStore.upsert(window(id: 1, tagMask: TagSet(tag).rawValue, frame: frame))

        let result = try await host.arrange(displayID: 1, bounds: .zero)

        XCTAssertEqual(result.engineID, FloatingLayoutEngine.engineID)
        XCTAssertEqual(result.placements, [Placement(windowID: 1, frame: frame, zOrder: 0, hidden: false)])
    }

    func testArrangeExcludesFloatingWindows() async throws {
        let tag = try Tag(index: 1)
        let engineID = LayoutEngineID(rawValue: "fixed")
        let windowStore = WindowStore()
        let tagStore = TagStore(defaultActiveTags: TagSet(tag))
        let registry = try LayoutEngineRegistry(factories: [AnyLayoutEngineFactory(FixedLayoutEngineFactory(id: engineID))])
        let recorder = EngineHostPlacementRecorder()
        let host = EngineHost(
            windowStore: windowStore,
            tagStore: tagStore,
            registry: registry,
            configProvider: { _ in FixedLayoutEngine.Config(width: 320) },
            applyPlacement: { window, placement in
                await recorder.record(windowID: window.id, placement: placement)
            }
        )
        await tagStore.bindEngine(engineID, to: tag, on: 1)
        await windowStore.upsert(window(id: 1, tagMask: TagSet(tag).rawValue))
        await windowStore.upsert(window(id: 2, tagMask: TagSet(tag).rawValue, isFloating: true))

        let result = try await host.arrange(displayID: 1, bounds: CGRect(x: 0, y: 0, width: 800, height: 600))
        let recordedWindowIDs = await recorder.windowIDs

        XCTAssertEqual(result.placements.map(\.windowID), [1])
        XCTAssertEqual(recordedWindowIDs, [1])
    }

    func testStartSubscribesToWindowAndTagChanges() async throws {
        let tag = try Tag(index: 1)
        let engineID = LayoutEngineID(rawValue: "fixed")
        let windowStore = WindowStore()
        let tagStore = TagStore(defaultActiveTags: TagSet(tag))
        let registry = try LayoutEngineRegistry(factories: [AnyLayoutEngineFactory(FixedLayoutEngineFactory(id: engineID))])
        let recorder = EngineHostPlacementRecorder()
        let host = EngineHost(
            windowStore: windowStore,
            tagStore: tagStore,
            registry: registry,
            configProvider: { _ in FixedLayoutEngine.Config(width: 320) },
            applyPlacement: { window, placement in
                await recorder.record(windowID: window.id, placement: placement)
            }
        )
        await tagStore.bindEngine(engineID, to: tag, on: 1)
        let task = host.start(displayID: 1, bounds: CGRect(x: 0, y: 0, width: 800, height: 600))
        defer {
            task.cancel()
        }

        await windowStore.upsert(window(id: 1, tagMask: TagSet(tag).rawValue))
        try await waitUntil {
            await recorder.windowIDs == [1]
        }
    }

    private func waitUntil(
        timeoutNanoseconds: UInt64 = 1_000_000_000,
        condition: () async -> Bool
    ) async throws {
        let deadline = DispatchTime.now().uptimeNanoseconds + timeoutNanoseconds
        while DispatchTime.now().uptimeNanoseconds < deadline {
            if await condition() {
                return
            }
            try await Task.sleep(nanoseconds: 10_000_000)
        }
        XCTFail("timed out waiting for condition")
    }

    private func window(
        id: WindowID,
        tagMask: UInt64,
        isFloating: Bool = false,
        frame: CGRect = CGRect(x: 0, y: 0, width: 100, height: 100)
    ) -> WindowState {
        WindowState(
            id: id,
            processID: 42,
            displayID: 1,
            tagMask: tagMask,
            isFloating: isFloating,
            frame: frame
        )
    }
}

private actor EngineHostPlacementRecorder {
    private(set) var records: [(windowID: WindowID, placement: Placement)] = []

    var windowIDs: [WindowID] {
        records.map(\.windowID)
    }

    func record(windowID: WindowID, placement: Placement) {
        records.append((windowID, placement))
    }
}

private actor EngineHostEventRecorder {
    private(set) var events: [EngineEvent] = []

    func record(_ event: EngineEvent) {
        events.append(event)
    }
}

private struct FixedLayoutEngine: LayoutEngine {
    struct Config {
        let width: CGFloat
    }

    let id: LayoutEngineID
    let displayName = "Fixed"
    let config: Config

    func arrange(windows: [WindowSnapshot], in bounds: CGRect, focus: WindowID?) -> [Placement] {
        windows.map { window in
            Placement(
                windowID: window.windowID,
                frame: CGRect(x: bounds.minX, y: bounds.minY, width: config.width, height: bounds.height)
            )
        }
    }
}

private struct FixedLayoutEngineFactory: LayoutEngineFactory {
    let id: LayoutEngineID
    let displayName = "Fixed"

    func makeEngine(config: FixedLayoutEngine.Config) throws -> FixedLayoutEngine {
        FixedLayoutEngine(id: id, config: config)
    }
}
